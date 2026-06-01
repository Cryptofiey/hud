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
                delay(400) // Small delay to allow UI to update and hide overlays
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
            withContext(Dispatchers.Main) { PokerHudSharedState.isScanning.value = true }
            delay(150) // Short delay to ensure overlays are hidden
            image = imageReader?.acquireLatestImage()
            withContext(Dispatchers.Main) { PokerHudSharedState.isScanning.value = false }
            
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
                val (commRect, holeRect, hudRects) = withContext(Dispatchers.Main) {
                    Triple(pokerHudService.getCommRect(), pokerHudService.getHoleRect(), pokerHudService.getHudRects())
                }
                
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
                
                // 4. OPPONENTS & PROFILE
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
     * Uses island detection to isolate cards and then OCR/color for identification.
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
        
        // 1. Find white islands (cards)
        val islands = findCardIslands(crop)
        val foundCards = mutableListOf<Triple<Rank, Suit, Int>>()
        
            for (island in islands) {
                val cardBmp = try { Bitmap.createBitmap(crop, island.left, island.top, island.width(), island.height()) } catch(e: Exception) { continue }
                
                // Use a single optimized scale pass
                val scale = 2.0f
                val rankW = (cardBmp.width * 0.8).toInt()
                val rankH = (cardBmp.height * 0.8).toInt()
                if (rankW < 2 || rankH < 2) {
                    cardBmp.recycle()
                    continue
                }
                
                val rankArea = try { Bitmap.createBitmap(cardBmp, 0, 0, rankW, rankH) } catch(e: Exception) { cardBmp }
                val scaled = Bitmap.createScaledBitmap(rankArea, (rankArea.width * scale).toInt(), (rankArea.height * scale).toInt(), true)
                
                val input = InputImage.fromBitmap(scaled, 0)
                val result = try { recognizer.process(input).await() } catch(e: Exception) { null }
                
                var bestRank: Rank? = null
                if (result != null && result.text.isNotEmpty()) {
                    bestRank = matchRank(result.text)
                }
                
                if (bestRank != null) {
                    val suit = detectSuitInCard(cardBmp)
                    foundCards.add(Triple(bestRank, suit, safeRect.left + island.centerX()))
                }
                
                if (rankArea != cardBmp && rankArea != crop) rankArea.recycle()
                scaled.recycle()
                if (cardBmp != crop) cardBmp.recycle()
            }
        
        crop.recycle()
        
        // Sort by X position and deduplicate
        return foundCards.sortedBy { it.third }
            .map { Card(it.first, it.second) }
            .distinct()
    }

    private fun findCardIslands(bitmap: Bitmap): List<android.graphics.Rect> {
        val islands = mutableListOf<android.graphics.Rect>()
        val width = bitmap.width
        val height = bitmap.height
        val visited = BooleanArray(width * height)
        
        // Minimum size of a card relative to box
        val minIslandW = 12
        val minIslandH = 15
        
        for (y in 2 until height - 2 step 3) {
            for (x in 2 until width - 2 step 3) {
                val idx = y * width + x
                if (visited[idx]) continue
                
                val pixel = bitmap.getPixel(x, y)
                if (isWhiteCardPixel(pixel)) {
                    val rect = floodFillIsland(bitmap, x, y, visited)
                    if (rect.width() >= 10 && rect.height() >= 12) {
                        // Split wide islands (potential overlapping cards)
                        val ratio = rect.width().toFloat() / rect.height().toFloat()
                        if (ratio > 1.0f) {
                            val numCards = (ratio + 0.5f).toInt()
                            val cardWidth = rect.width() / numCards
                            for (i in 0 until numCards) {
                                islands.add(android.graphics.Rect(rect.left + i * cardWidth, rect.top, rect.left + (i + 1) * cardWidth, rect.bottom))
                            }
                        } else {
                            islands.add(rect)
                        }
                    }
                }
            }
        }
        return islands.sortedBy { it.left }
    }

    private fun isWhiteCardPixel(pixel: Int): Boolean {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)
        // Card white can be dim (150+) or tinted (e.g. green table reflection)
        return r > 150 && g > 150 && b > 140 && 
               kotlin.math.abs(r - g) < 45 && 
               kotlin.math.abs(r - b) < 50
    }

    private fun floodFillIsland(bitmap: Bitmap, startX: Int, startY: Int, visited: BooleanArray): android.graphics.Rect {
        val width = bitmap.width
        val height = bitmap.height
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        queue.add(startX to startY)
        visited[startY * width + startX] = true
        
        var minX = startX; var maxX = startX; var minY = startY; var maxY = startY
        
        var count = 0
        while (queue.isNotEmpty() && count < 3000) {
            val (px, py) = queue.poll()!!
            count++
            
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
            
            // Search neighbors with step to speed up (smaller step for precision)
            val step = 2
            val neighbors = arrayOf(px + step to py, px - step to py, px to py + step, px to py - step)
            for ((nx, ny) in neighbors) {
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!visited[nIdx]) {
                        visited[nIdx] = true
                        if (isWhiteCardPixel(bitmap.getPixel(nx, ny))) {
                            queue.add(nx to ny)
                        }
                    }
                }
            }
        }
        val rect = android.graphics.Rect(minX, minY, maxX, maxY)
        // Ensure some minimum padding for recognition
        return android.graphics.Rect(
            maxOf(0, rect.left - 1),
            maxOf(0, rect.top - 1),
            minOf(width, rect.right + 1),
            minOf(height, rect.bottom + 1)
        )
    }

    private fun detectSuitInCard(bitmap: Bitmap): Suit {
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        val w = bitmap.width
        val h = bitmap.height
        
        // Sample in the suit area
        for (x in (w * 0.05).toInt() until (w * 0.95).toInt() step 2) {
            for (y in (h * 0.2).toInt() until (h * 0.95).toInt() step 2) {
                val p = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // CoinPoker 4-color deck
                if (r > 160 && r > g + 70 && r > b + 70) rC++
                else if (g > 140 && g > r + 70 && g > b + 60) gC++
                else if (b > 130 && b > r + 70 && b > g + 20) bC++
                else if (r < 70 && g < 70 && b < 70) blkC++
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
        val t = text.uppercase(java.util.Locale.US).replace(" ", "").replace("|", "I").replace("(", "").replace(")", "").trim()
        
        return when {
            t.contains("10") || t.contains("T") || t.contains("IO") || t.contains("IQ") -> Rank.TEN
            t.contains("A") -> Rank.ACE
            t.contains("K") || t.contains("X") -> Rank.KING
            t.contains("Q") || t.contains("0") || (t.length == 1 && t == "O") -> Rank.QUEEN
            t.contains("J") || (t.length == 1 && (t == "I" || t == "L" || t == "1")) -> Rank.JACK
            t.contains("9") || (t.length == 1 && t == "G") -> Rank.NINE
            t.contains("8") || t.contains("B") || t.contains("&") -> Rank.EIGHT
            t.contains("7") -> Rank.SEVEN
            t.contains("6") -> Rank.SIX
            t.contains("5") || t.contains("S") -> Rank.FIVE
            t.contains("4") -> Rank.FOUR
            t.contains("3") -> Rank.THREE
            t.contains("2") || t.contains("Z") -> Rank.TWO
            else -> {
                if (t.length == 1) {
                    when (t[0]) {
                        'A','4' -> Rank.ACE
                        'K' -> Rank.KING
                        'Q','0','O' -> Rank.QUEEN
                        'J','I','L','1' -> Rank.JACK
                        '9','G' -> Rank.NINE
                        '8','B','&' -> Rank.EIGHT
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
    }

    fun stop() {
        PokerHudSharedState.isScanning.value = false
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
