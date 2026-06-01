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
                    val safeComm = android.graphics.Rect(
                        maxOf(0, commRect.left),
                        maxOf(0, commRect.top),
                        minOf(cleanBitmap.width, commRect.right),
                        minOf(cleanBitmap.height, commRect.bottom)
                    )
                    val cropped = Bitmap.createBitmap(cleanBitmap, safeComm.left, safeComm.top, safeComm.width(), safeComm.height())
                    val parsed = scanRegionForCards(cropped)
                    foundCommCards.addAll(parsed)
                    cropped.recycle()
                }

                // 2. PROCESS HOLE CARDS
                if (holeRect.width() > 20 && holeRect.height() > 20) {
                    val safeHole = android.graphics.Rect(
                        maxOf(0, holeRect.left),
                        maxOf(0, holeRect.top),
                        minOf(cleanBitmap.width, holeRect.right),
                        minOf(cleanBitmap.height, holeRect.bottom)
                    )
                    val cropped = Bitmap.createBitmap(cleanBitmap, safeHole.left, safeHole.top, safeHole.width(), safeHole.height())
                    val parsed = scanRegionForCards(cropped)
                    foundHoleCards.addAll(parsed)
                    cropped.recycle()
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
     * Use simple brightness-based island detection for cards.
     */
    private suspend fun scanRegionForCards(region: Bitmap): List<Card> {
        val foundCards = mutableListOf<Card>()
        val width = region.width
        val height = region.height
        
        // Find potential card starts by scanning for white pixels
        val discoveredRects = mutableListOf<android.graphics.Rect>()
        val cardWidthGuess = width / 5
        val minCardH = height / 3
        
        for (x in 2 until width - 2 step 12) {
            for (y in 2 until height - 2 step 12) {
                if (isWhiteCardPixel(region.getPixel(x, y))) {
                    if (discoveredRects.any { it.contains(x, y) }) continue
                    
                    val rect = floodFillCardBounds(region, x, y)
                    if (rect.width() > cardWidthGuess / 3 && rect.height() > minCardH) {
                        discoveredRects.add(rect)
                    }
                }
            }
        }
        
        // Sort by X
        val sortedRects = discoveredRects.sortedBy { it.left }
        
        for (rect in sortedRects) {
            // Target the Top-Left of the card for Rank/Suit
            val rankBox = android.graphics.Rect(
                rect.left + (rect.width() * 0.05).toInt(),
                rect.top + (rect.height() * 0.05).toInt(),
                rect.left + (rect.width() * 0.45).toInt(),
                rect.top + (rect.height() * 0.60).toInt()
            )
            
            if (rankBox.width() > 10 && rankBox.height() > 10) {
                try {
                    val cardBmp = Bitmap.createBitmap(region, rankBox.left, rankBox.top, rankBox.width(), rankBox.height())
                    val card = identifyCardSimplified(cardBmp)
                    if (card != null) foundCards.add(card)
                    cardBmp.recycle()
                } catch(e: Exception) {}
            }
        }
        
        return foundCards.distinct()
    }

    private fun isWhiteCardPixel(pixel: Int): Boolean {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)
        return r > 200 && g > 200 && b > 200 // Clear white card base
    }

    private fun floodFillCardBounds(bitmap: Bitmap, startX: Int, startY: Int): android.graphics.Rect {
        var minX = startX; var maxX = startX; var minY = startY; var maxY = startY
        // Fast horizontal scan
        for (x in startX downTo 0) if (isWhiteCardPixel(bitmap.getPixel(x, startY))) minX = x else break
        for (x in startX until bitmap.width) if (isWhiteCardPixel(bitmap.getPixel(x, startY))) maxX = x else break
        // Fast vertical scan at center of horizontal
        val midX = minX + (maxX - minX) / 2
        for (y in startY downTo 0) if (isWhiteCardPixel(bitmap.getPixel(midX, y))) minY = y else break
        for (y in startY until bitmap.height) if (isWhiteCardPixel(bitmap.getPixel(midX, y))) maxY = y else break
        return android.graphics.Rect(minX, minY, maxX, maxY)
    }

    private suspend fun identifyCardSimplified(rankBmp: Bitmap): Card? {
        val w = rankBmp.width
        val h = rankBmp.height
        
        // 1. Color Suit Detection (focused on the middle-bottom)
        var red = 0; var green = 0; var blue = 0; var black = 0
        for (x in (w * 0.2).toInt() until (w * 0.9).toInt()) {
            for (y in (h * 0.45).toInt() until (h * 0.95).toInt()) {
                val p = rankBmp.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                val lum = (r + g + b) / 3
                
                if (r > 160 && r > g + 60 && r > b + 60) red++
                else if (g > 140 && g > r + 60 && g > b + 50) green++
                else if (b > 140 && b > r + 60 && b > g + 25) blue++
                else if (lum < 80) black++
            }
        }
        
        val suit = when {
            red > 8 && red >= green && red >= blue && red > black -> Suit.HEARTS
            green > 8 && green >= red && green >= blue && green > black -> Suit.CLUBS
            blue > 8 && blue >= red && blue >= green && blue > black -> Suit.DIAMONDS
            black > 8 && black >= red && black >= green && black >= blue -> Suit.SPADES
            else -> null
        } ?: return null // Require a suit to be confident it's a card

        // 2. Rank OCR (Clean and Scale)
        val rankOnly = Bitmap.createBitmap(rankBmp, 0, 0, w, (h * 0.55).toInt())
        val scaled = Bitmap.createScaledBitmap(rankOnly, rankOnly.width * 3, rankOnly.height * 3, true)
        
        val input = InputImage.fromBitmap(scaled, 0)
        val result = try { recognizer.process(input).await() } catch(e: Exception) { null }
        
        rankOnly.recycle()
        scaled.recycle()
        
        if (result != null && result.text.isNotEmpty()) {
            val text = result.text.uppercase(java.util.Locale.US).replace(" ", "").replace("\n", "")
            val rank = when {
                text.contains("10") || text.contains("T") -> Rank.TEN
                text.contains("A") || text.contains("4") -> Rank.ACE // "4" is a common OCR error for Ace in some fonts
                text.contains("K") -> Rank.KING
                text.contains("Q") || text.contains("0") -> Rank.QUEEN
                text.contains("J") || text.contains("1") || text.contains("I") -> Rank.JACK
                text.contains("9") -> Rank.NINE
                text.contains("8") || text.contains("B") -> Rank.EIGHT
                text.contains("7") || text.contains("Y") -> Rank.SEVEN
                text.contains("6") -> Rank.SIX
                text.contains("5") || text.contains("S") -> Rank.FIVE
                text.contains("3") -> Rank.THREE
                text.contains("2") -> Rank.TWO
                else -> null
            }
            if (rank != null) return Card(rank, suit)
        }
        
        return null
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
