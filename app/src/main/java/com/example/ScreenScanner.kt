package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.coroutines.tasks.await

class ScreenScanner(
    private val pokerHudService: PokerHudService,
    private val resultData: Intent,
    private val resultCode: Int
) {
    private val context: Context = pokerHudService
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null
    
    val isScanning = MutableStateFlow(false)
    val scanStatus = MutableStateFlow("Scanner idle.")

    @SuppressLint("WrongConstant")
    fun start() {
        if (isScanning.value) return
        isScanning.value = true
        scanStatus.value = "Starting modern ML Kit scanner..."

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenScanner",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        scanJob = scope.launch {
            while (isActive) {
                processLatestImage()
                delay(2000) // Scan every 2 seconds
            }
        }
    }

    private suspend fun processLatestImage() {
        val image: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }

        if (image != null) {
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val totalWidth = width + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(
                totalWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            try {
                val result = recognizer.process(inputImage).await()
                
                val commRect = pokerHudService.getCommRect()
                val holeRect = pokerHudService.getHoleRect()
                
                val foundCommCards = mutableListOf<Card>()
                val foundHoleCards = mutableListOf<Card>()
                
                android.util.Log.d("ScreenScanner", "MLKit raw text:\n${result.text}")
                
                val cardPattern = Regex("^(10|[AKQJ]|[2-9])\$")
                
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val text = element.text.uppercase(java.util.Locale.US).replace(Regex("[^10AKQJ2-9]"), "")
                            if (cardPattern.matches(text)) {
                                val box = element.boundingBox
                                if (box != null) {
                                    // Try to determine suit from pixel color right below the text
                                    // We need to look below the text where the suit symbol typically is
                                    val checkX = box.centerX()
                                    // Let's check a range of pixels below the box, or inside the box if it includes the suit
                                    val checkY = minOf(bitmap.height - 1, box.bottom + (box.height() / 4))
                                    
                                    var isRed = false
                                    var isGreen = false
                                    var isBlue = false
                                    var isBlack = false
                                    
                                    // Check a 5x5 grid around the check point
                                    for (dx in -10..10 step 5) {
                                        for (dy in -5..15 step 5) {
                                            val px = (checkX + dx).coerceIn(0, bitmap.width - 1)
                                            val py = (checkY + dy).coerceIn(0, bitmap.height - 1)
                                            
                                            val pixel = bitmap.getPixel(px, py)
                                            val r = android.graphics.Color.red(pixel)
                                            val g = android.graphics.Color.green(pixel)
                                            val b = android.graphics.Color.blue(pixel)
                                            
                                            if (r > 150 && g < 100 && b < 100) isRed = true
                                            if (g > 120 && r < 100 && b < 100) isGreen = true
                                            if (b > 120 && r < 100 && g < 100) isBlue = true
                                            if (r < 80 && g < 80 && b < 80 && (r+g+b) < 150) isBlack = true
                                        }
                                    }
                                    
                                    val rank = parseRank(text) ?: continue
                                    var suit = Suit.SPADES // Default
                                    
                                    if (isRed) suit = Suit.HEARTS 
                                    else if (isGreen) suit = Suit.CLUBS
                                    else if (isBlue) suit = Suit.DIAMONDS
                                    else if (isBlack) suit = Suit.SPADES
                                    
                                    val card = Card(rank, suit)
                                    android.util.Log.d("ScreenScanner", "Detected Card: $text ($suit) at bounds $box")
                                    
                                    if (commRect.contains(box.centerX(), box.centerY())) {
                                        foundCommCards.add(card)
                                        android.util.Log.d("ScreenScanner", "-> Assigned to Comm")
                                    } else if (holeRect.contains(box.centerX(), box.centerY())) {
                                        foundHoleCards.add(card)
                                        android.util.Log.d("ScreenScanner", "-> Assigned to Hole")
                                    }
                                }
                            }
                        }
                    }
                }
                
                val commW = pokerHudService.getCommRect().width()
                val holeW = pokerHudService.getHoleRect().width()
                scanStatus.value = "H:${foundHoleCards.size} C:${foundCommCards.size} (${commW},${holeW})"

                
                if (foundHoleCards.isNotEmpty() || foundCommCards.isNotEmpty()) {
                    val h1 = foundHoleCards.getOrNull(0)
                    val h2 = foundHoleCards.getOrNull(1)
                    val paddedBoard = foundCommCards.take(5).toMutableList<Card?>()
                    while (paddedBoard.size < 5) paddedBoard.add(null)
                    
                    PokerHudSharedState.externalActions.tryEmit(
                        ExternalAction.UpdateCards(h1, h2, paddedBoard)
                    )
                }
                
            } catch (e: Exception) {
                scanStatus.value = "OCR Failed: \${e.message}"
            }
        }
    }

    private fun parseRank(rankStr: String): Rank? {
        return when (rankStr) {
            "A" -> Rank.ACE
            "K" -> Rank.KING
            "Q" -> Rank.QUEEN
            "J" -> Rank.JACK
            "10", "T" -> Rank.TEN
            "9" -> Rank.NINE
            "8" -> Rank.EIGHT
            "7" -> Rank.SEVEN
            "6" -> Rank.SIX
            "5" -> Rank.FIVE
            "4" -> Rank.FOUR
            "3" -> Rank.THREE
            "2" -> Rank.TWO
            else -> null
        }
    }

    private fun parseCardsFromText(text: String) {
        // Very basic mock parser: look for patterns like Ah, Kd, 10c, 9s
        val cardPattern = Regex("([AKQJTaktqj2-9]|10)\\s*(?i)(h|d|c|s)")
        val foundCards = cardPattern.findAll(text).map { match ->
            val rankStr = match.groupValues[1].uppercase()
            val suitStr = match.groupValues[2].lowercase()
            
            val rank = when (rankStr) {
                "A" -> Rank.ACE
                "K" -> Rank.KING
                "Q" -> Rank.QUEEN
                "J" -> Rank.JACK
                "T", "10" -> Rank.TEN
                "9" -> Rank.NINE
                "8" -> Rank.EIGHT
                "7" -> Rank.SEVEN
                "6" -> Rank.SIX
                "5" -> Rank.FIVE
                "4" -> Rank.FOUR
                "3" -> Rank.THREE
                "2" -> Rank.TWO
                else -> Rank.TWO
            }
            val suit = when (suitStr) {
                "h" -> Suit.HEARTS
                "d" -> Suit.DIAMONDS
                "c" -> Suit.CLUBS
                "s" -> Suit.SPADES
                else -> Suit.SPADES
            }
            Card(rank, suit)
        }.toList()

        if (foundCards.size >= 2) {
            val h1 = foundCards[0]
            val h2 = foundCards[1]
            val board = if (foundCards.size > 2) foundCards.drop(2).take(5) else emptyList()
            
            val paddedBoard = board.toMutableList<Card?>()
            while (paddedBoard.size < 5) paddedBoard.add(null)
            
            val success = PokerHudSharedState.externalActions.tryEmit(
                ExternalAction.UpdateCards(h1, h2, paddedBoard)
            )
            if (success) {
                scanStatus.value = "Updated cards successfully from Scanner."
            }
        }
    }

    fun stop() {
        isScanning.value = false
        scanJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        scanStatus.value = "Scanner stopped."
    }
}
