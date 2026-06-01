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
    private val resultCode: Int,
    private val stopAfterProfileScan: Boolean = false
) {
    private val context: Context = pokerHudService
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null
    
    var requestProfileScan = stopAfterProfileScan // if true initially, immediately run profile scan
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

    private var consecutiveEmptyHole = 0
    private var consecutiveEmptyComm = 0

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
                val commRect = withContext(Dispatchers.Main) { pokerHudService.getCommRect() }
                val holeRect = withContext(Dispatchers.Main) { pokerHudService.getHoleRect() }
                val hudRects = withContext(Dispatchers.Main) { pokerHudService.getHudRects() }
                
                val foundCommCards = mutableListOf<Card>()
                val foundHoleCards = mutableListOf<Card>()
                
                // 1. PROCESS COMMUNITY CARDS
                if (commRect.width() > 20 && commRect.height() > 20) {
                    val parsed = scanRegionForCards(cleanBitmap, commRect)
                    foundCommCards.addAll(parsed)
                }

                // 2. PROCESS HOLE CARDS
                if (holeRect.width() > 20 && holeRect.height() > 20) {
                    val parsed = scanRegionForCards(cleanBitmap, holeRect)
                    foundHoleCards.addAll(parsed)
                }

                val currentState = PokerHudSharedState.uiState.value
                
                // 3. PERSISTENCE/SMOOTHING
                if (foundHoleCards.isEmpty()) consecutiveEmptyHole++ else consecutiveEmptyHole = 0
                if (foundCommCards.isEmpty()) consecutiveEmptyComm++ else consecutiveEmptyComm = 0
                
                val finalH1 = foundHoleCards.getOrNull(0) ?: if (consecutiveEmptyHole < 3) currentState.heroCard1 else null
                val finalH2 = foundHoleCards.getOrNull(1) ?: if (consecutiveEmptyHole < 3) currentState.heroCard2 else null
                val finalBoard = List(5) { i ->
                    foundCommCards.getOrNull(i) ?: if (consecutiveEmptyComm < 3) currentState.board.getOrNull(i) else null
                }
                
                // 4. OPPONENTS & PROFILE (ML Kit pass on whole screen only if needed, but here we reuse original result for stats)
                // We'll do a focused ML Kit pass for Opponents/Profiles to avoid full screen drag
                val inputImage = InputImage.fromBitmap(cleanBitmap, 0)
                val result = recognizer.process(inputImage).await()
                
                val scannedOpponents = OpponentScanner.scan(result, cleanBitmap, hudRects)
                val finalOpponents = if (scannedOpponents.isNotEmpty()) scannedOpponents else currentState.opponents

                var profileBoxesToHighlight: List<ScannedBox>? = null
                if (requestProfileScan) {
                    val scannedProfile = ProfileScanner.scan(result, cleanBitmap, hudRects)
                    if (scannedProfile != null && scannedProfile.nickname != "Unknown_Profile") {
                        profileBoxesToHighlight = scannedProfile.profileBoundingBoxes
                        val prefsManager = PreferencesManager(pokerHudService)
                        val existing = prefsManager.loadPlayerStats(scannedProfile.nickname)
                        val updated = existing.copy(
                            histVpip = scannedProfile.histVpip ?: existing.histVpip,
                            histPfr = scannedProfile.histPfr ?: existing.histPfr
                        )
                        prefsManager.savePlayerStats(updated)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Profile parsed: ${scannedProfile.nickname}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    requestProfileScan = false
                }

                PokerHudSharedState.externalActions.tryEmit(
                    ExternalAction.UpdateCards(finalH1, finalH2, finalBoard, finalOpponents, profileBoxesToHighlight, updateProfileBoxes = (profileBoxesToHighlight != null))
                )
                
                scanStatus.value = "H:${foundHoleCards.size} C:${foundCommCards.size} Ops:${finalOpponents.size}<br>" +
                                   "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
                
            } catch (e: Exception) {
                scanStatus.value = "Scan Error: ${e.message}"
            } finally {
                cleanBitmap.recycle()
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Fatal Scanner Error", e)
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
        }
    }

    /**
     * Efficiently scans a cropped region (Comm or Hole) for cards.
     * Uses direct OCR and color sampling for robust multi-color deck detection.
     */
    private suspend fun scanRegionForCards(source: Bitmap, rect: android.graphics.Rect): List<Card> {
        val safeRect = android.graphics.Rect(
            maxOf(0, rect.left),
            maxOf(0, rect.top),
            minOf(source.width, rect.right),
            minOf(source.height, rect.bottom)
        )
        if (safeRect.width() < 10 || safeRect.height() < 10) return emptyList()
        
        val crop = Bitmap.createBitmap(source, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
        // Scale up crops for significantly better OCR performance on small card text
        val scale = 2
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
        val input = com.google.mlkit.vision.common.InputImage.fromBitmap(scaled, 0)
        
        val result = try { recognizer.process(input).await() } catch(e: Exception) { null }
        val foundCards = mutableListOf<Triple<Rank, Suit, Int>>()
        
        if (result != null) {
            for (block in result.textBlocks) {
                val blockText = block.text.uppercase(java.util.Locale.US)
                // Filter out HUD labels that might be in the crop area
                if (blockText.contains("CROP") || blockText.contains("BOX") || blockText.contains("CARDS")) continue
                
                for (line in block.lines) {
                    for (element in line.elements) {
                        val text = element.text.uppercase(java.util.Locale.US).replace(" ", "")
                        val rank = matchRank(text) ?: continue
                        val elementBox = element.boundingBox ?: continue
                        
                        // Detect suit based on color of text itself or nearby pixels
                        val suit = detectSuitInElement(scaled, elementBox)
                        foundCards.add(Triple(rank, suit, elementBox.centerX()))
                    }
                }
            }
        }
        
        crop.recycle()
        scaled.recycle()
        
        // Sort by X position and deduplicate
        return foundCards.sortedBy { it.third }
            .map { Card(it.first, it.second) }
            .distinct()
    }

    private fun detectSuitInElement(bitmap: Bitmap, box: android.graphics.Rect): Suit {
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        // Sample in and slightly below the rank element to catch suit color/icons
        val scan = android.graphics.Rect(
            maxOf(0, box.left),
            maxOf(0, box.top),
            minOf(bitmap.width - 1, box.right),
            minOf(bitmap.height - 1, box.bottom + (box.height() * 0.4).toInt())
        )
        
        for (x in scan.left until scan.right step 2) {
            for (y in scan.top until scan.bottom step 2) {
                val p = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Color thresholds for CoinPoker 4-color deck
                if (r > 150 && r > g + 60 && r > b + 60) rC++
                else if (g > 130 && g > r + 60 && g > b + 50) gC++
                else if (b > 130 && b > r + 60 && b > g + 15) bC++
                else if (r < 80 && g < 80 && b < 80) blkC++
            }
        }
        
        return when {
            rC > gC && rC > bC && rC > 5 -> Suit.HEARTS
            gC > rC && gC > bC && gC > 5 -> Suit.CLUBS
            bC > rC && bC > gC && bC > 5 -> Suit.DIAMONDS
            else -> Suit.SPADES
        }
    }

    private fun matchRank(text: String): Rank? {
        val t = text.uppercase(java.util.Locale.US).trim()
        return when {
            t.contains("10") || t.contains("T") -> Rank.TEN
            t.contains("A") -> Rank.ACE
            t.contains("K") -> Rank.KING
            t.contains("Q") || t == "0" -> Rank.QUEEN
            t.contains("J") -> Rank.JACK
            t == "9" -> Rank.NINE
            t == "8" || t == "B" -> Rank.EIGHT
            t == "7" -> Rank.SEVEN
            t == "6" -> Rank.SIX
            t == "5" || t == "S" -> Rank.FIVE
            t == "4" -> Rank.FOUR
            t == "3" -> Rank.THREE
            t == "2" -> Rank.TWO
            else -> if (t.length == 1) {
                when (t[0]) {
                    'A','4' -> Rank.ACE
                    'K' -> Rank.KING
                    'Q','0' -> Rank.QUEEN
                    'J','I','L','1' -> Rank.JACK
                    '9' -> Rank.NINE
                    '8','B' -> Rank.EIGHT
                    '7' -> Rank.SEVEN
                    '6' -> Rank.SIX
                    '5','S' -> Rank.FIVE
                    '4' -> Rank.FOUR
                    '3' -> Rank.THREE
                    '2','Z' -> Rank.TWO
                    else -> null
                }
            } else null
        }
    }

    fun stop() {
        isScanning.value = false
        scanStatus.value = "Scanner stopped."
        
        scope.launch(Dispatchers.Main) {
            scanJob?.cancel()
            scanJob?.join()
            scanJob = null
            
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
            } catch (e: Exception) {
                android.util.Log.e("ScreenScanner", "Error stopping scanner", e)
            }
        }
    }
}
