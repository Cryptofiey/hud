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
        try {
            isScanning.value = true
            scanStatus.value = "Starting modern ML Kit scanner..."

            if (ScannerConfig.activeProjection == null) {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                ScannerConfig.activeProjection = projectionManager.getMediaProjection(resultCode, resultData)
            }
            mediaProjection = ScannerConfig.activeProjection

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
        } catch (e: Exception) {
            android.util.Log.e("ScreenScanner", "Error starting scanner", e)
            scanStatus.value = "Scanner start failed: ${e.message}"
            isScanning.value = false
        }
    }

    private suspend fun processLatestImage() {
        var image: Image? = null
        try {
            image = imageReader?.acquireLatestImage()
            if (image == null) return
            
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

            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()

            val inputImage = InputImage.fromBitmap(cleanBitmap, 0)
            
            try {
                val result = recognizer.process(inputImage).await()
                
                val commRect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pokerHudService.getCommRect() }
                val holeRect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pokerHudService.getHoleRect() }
                
                val foundCommCards = mutableListOf<Card>()
                val foundHoleCards = mutableListOf<Card>()
                
                val cardPattern = Regex("^(10|T|[AKQJ]|[2-9])$")
                val debugLogs = mutableListOf<String>()
                
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            val expCommRect = android.graphics.Rect(commRect.left - 40, commRect.top - 40, commRect.right + 40, commRect.bottom + 40)
                            val expHoleRect = android.graphics.Rect(holeRect.left - 40, holeRect.top - 40, holeRect.right + 40, holeRect.bottom + 40)
                            
                            val inComm = expCommRect.contains(box.centerX(), box.centerY())
                            val inHole = expHoleRect.contains(box.centerX(), box.centerY())
                            
                            if (inComm || inHole) {
                                debugLogs.add(element.text)
                            }
                            
                            val rawText = element.text.uppercase(java.util.Locale.US)
                            val text = rawText.replace(Regex("[^10AKQJT2-9]"), "")
                            if (cardPattern.matches(text) && rawText.length <= 4) { 
                                val checkX = box.centerX()
                                val checkY = box.centerY()
                                
                                var isRed = false
                                var isGreen = false
                                var isBlue = false
                                var isBlack = false
                                
                                var redCount = 0
                                var greenCount = 0
                                var blueCount = 0
                                var blackCount = 0
                                
                                val startX = maxOf(0, box.left - box.width())
                                val endX = minOf(cleanBitmap.width - 1, box.right + (box.width() / 4))
                                val startY = maxOf(0, box.top - (box.height() / 4))
                                val endY = minOf(cleanBitmap.height - 1, box.bottom + (box.height() / 2))
                                
                                for (px in startX..endX step 3) {
                                    for (py in startY..endY step 3) {
                                        val pixel = cleanBitmap.getPixel(px, py)
                                        val r = android.graphics.Color.red(pixel)
                                        val g = android.graphics.Color.green(pixel)
                                        val b = android.graphics.Color.blue(pixel)
                                        
                                        if (r > 150 && g < 100 && b < 100) redCount++
                                        if (g > 120 && r < 100 && b < 100) greenCount++
                                        if (b > 120 && r < 100 && g < 100) blueCount++
                                        if (r < 80 && g < 80 && b < 80 && (r+g+b) < 150) blackCount++
                                    }
                                }
                                
                                if (redCount > 5) isRed = true
                                if (greenCount > 5) isGreen = true
                                if (blueCount > 5) isBlue = true
                                if (blackCount > 10) isBlack = true
                                
                                val rank = parseRank(text) ?: continue
                                var suit = Suit.SPADES 
                                
                                if (isRed) suit = Suit.HEARTS 
                                else if (isGreen) suit = Suit.CLUBS
                                else if (isBlue) suit = Suit.DIAMONDS
                                else if (isBlack) suit = Suit.SPADES
                                
                                val card = Card(rank, suit)
                                
                                if (inComm) {
                                    foundCommCards.add(card)
                                } else if (inHole) {
                                    foundHoleCards.add(card)
                                }
                            }
                        }
                    }
                }
                
                val commW = commRect.width()
                val holeW = holeRect.width()
                val paddedBoard = foundCommCards.take(5) + List(maxOf(0, 5 - foundCommCards.size)) { null }
                val h1 = foundHoleCards.getOrNull(0)
                val h2 = foundHoleCards.getOrNull(1)
                scanStatus.value = "H:${foundHoleCards.size} C:${foundCommCards.size} (${commW},${holeW})\n" +
                                   "Box: ${debugLogs.joinToString(", ")}\n" + 
                                   "Board: ${paddedBoard.joinToString("") { it?.toString() ?: "[?]" }}"
                
                if (foundHoleCards.isNotEmpty() || foundCommCards.isNotEmpty()) {
                    PokerHudSharedState.externalActions.tryEmit(
                        ExternalAction.UpdateCards(h1, h2, paddedBoard)
                    )
                }
                
            } catch (e: Exception) {
                scanStatus.value = "OCR Failed: ${e.message}"
            } finally {
                cleanBitmap.recycle()
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Fatal Error", e)
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
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

    fun stop() {
        isScanning.value = false
        scanJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        scanStatus.value = "Scanner stopped."
    }
}
