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
import kotlinx.coroutines.async
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
                    delay(1100) // Slightly slower scan for better UX (less flicker)
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
        var cleanBitmap: Bitmap? = null
        try {
            withContext(Dispatchers.Main) { PokerHudSharedState.isScanning.value = true }
            delay(120) // Give more time to hide overlays for a clean frame
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

            val bitmap = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            image = null

            cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()

            val rects = withContext(Dispatchers.Main) {
                Triple(pokerHudService.getCommRect(), pokerHudService.getHoleRect(), pokerHudService.getHudRects())
            }
            val commRect = rects.first
            val holeRect = rects.second
            val hudRects = rects.third
            
            val currentState = PokerHudSharedState.uiState.value

            // 1. COMBINED CARD DETECTION
            val cardOCRTask = scope.async {
                val combinedCards = mutableListOf<CardTemplate>()
                
                // Scan Comm
                if (commRect.width() > 20 && commRect.height() > 20) {
                    combinedCards.addAll(detectCardsInRegion(cleanBitmap!!, commRect, isHole = false))
                }
                // Scan Hole
                if (holeRect.width() > 20 && holeRect.height() > 20) {
                    combinedCards.addAll(detectCardsInRegion(cleanBitmap!!, holeRect, isHole = true))
                }
                
                if (combinedCards.isEmpty()) return@async Pair(emptyList<Card>(), emptyList<Card>())
                
                // Batch OCR for ALL cards found
                performBatchCardOCR(cleanBitmap!!, combinedCards)
            }

            // 2. FULL SCREEN OCR (for opponents)
            val fullScreenTask = scope.async {
                val inputImage = InputImage.fromBitmap(cleanBitmap!!, 0)
                recognizer.process(inputImage).await()
            }

            // 3. WAIT FOR RESULTS
            val (foundCommCards, foundHoleCards) = cardOCRTask.await()
            val result = fullScreenTask.await()

            // 4. PERSISTENCE/SMOOTHING
            if (foundHoleCards.isEmpty()) consecutiveEmptyHole++ else consecutiveEmptyHole = 0
            if (foundCommCards.isEmpty()) consecutiveEmptyComm++ else consecutiveEmptyComm = 0
            
            val finalH1 = foundHoleCards.getOrNull(0) ?: if (consecutiveEmptyHole < 3) currentState.heroCard1 else null
            val finalH2 = foundHoleCards.getOrNull(1) ?: if (consecutiveEmptyHole < 3) currentState.heroCard2 else null
            val finalBoard = List(5) { i ->
                foundCommCards.getOrNull(i) ?: if (consecutiveEmptyComm < 3) currentState.board.getOrNull(i) else null
            }
            
            // 5. OPPONENTS & PROFILE
            val scannedOpponents = OpponentScanner.scan(result, cleanBitmap!!, hudRects)
            val finalOpponents = if (scannedOpponents.isNotEmpty()) scannedOpponents else currentState.opponents

            var profileBoxesToHighlight: List<ScannedBox>? = null
            if (requestProfileScan) {
                val scannedProfile = ProfileScanner.scan(result, cleanBitmap!!, hudRects)
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
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            cleanBitmap?.recycle()
            try { image?.close() } catch(ignored: Exception) {}
        }
    }

    private data class CardTemplate(
        val rectInSource: android.graphics.Rect,
        val isHole: Boolean,
        val island: android.graphics.Rect
    )

    private fun detectCardsInRegion(source: Bitmap, rect: android.graphics.Rect, isHole: Boolean): List<CardTemplate> {
        val safeRect = android.graphics.Rect(
            maxOf(0, rect.left),
            maxOf(0, rect.top),
            minOf(source.width, rect.right),
            minOf(source.height, rect.bottom)
        )
        if (safeRect.width() < 10 || safeRect.height() < 10) return emptyList()
        
        val crop = try { Bitmap.createBitmap(source, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()) } catch(e: Exception) { return emptyList() }
        val islands = findCardIslands(crop)
        crop.recycle()
        
        return islands.map { CardTemplate(safeRect, isHole, it) }
    }

    private suspend fun performBatchCardOCR(source: Bitmap, templates: List<CardTemplate>): Pair<List<Card>, List<Card>> {
        val scale = 2.0f
        val margin = 30
        var totalW = 0
        var maxH = 0
        
        val cardBitmaps = mutableListOf<Bitmap>()
        val bitmapToTemplateIndex = mutableListOf<Int>()

        for (i in templates.indices) {
            val t = templates[i]
            val cardBmp = try {
                val b = Bitmap.createBitmap(source, t.rectInSource.left + t.island.left, t.rectInSource.top + t.island.top, t.island.width(), t.island.height())
                val rankW = (b.width * 0.8).toInt()
                val rankH = (b.height * 0.8).toInt()
                if (rankW < 2 || rankH < 2) {
                    b.recycle()
                    null
                } else {
                    val rankArea = Bitmap.createBitmap(b, 0, 0, rankW, rankH)
                    val scaled = Bitmap.createScaledBitmap(rankArea, (rankArea.width * scale).toInt(), (rankArea.height * scale).toInt(), true)
                    if (rankArea != b) rankArea.recycle()
                    b.recycle()
                    scaled
                }
            } catch(e: Exception) { null }

            if (cardBmp != null) {
                cardBitmaps.add(cardBmp)
                bitmapToTemplateIndex.add(i)
                totalW += cardBmp.width + margin
                maxH = maxOf(maxH, cardBmp.height)
            }
        }

        if (cardBitmaps.isEmpty()) return Pair(emptyList(), emptyList())

        val stitched = Bitmap.createBitmap(totalW, maxH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(stitched)
        canvas.drawColor(android.graphics.Color.BLACK)
        
        val offsets = mutableListOf<Int>()
        var currentX = 0
        for (bmp in cardBitmaps) {
            offsets.add(currentX)
            canvas.drawBitmap(bmp, currentX.toFloat(), 0f, null)
            currentX += bmp.width + margin
        }

        val input = InputImage.fromBitmap(stitched, 0)
        val result = try { recognizer.process(input).await() } catch(e: Exception) { null }

        val foundComm = mutableListOf<Pair<Card, Int>>()
        val foundHole = mutableListOf<Pair<Card, Int>>()

        if (result != null) {
            for (i in cardBitmaps.indices) {
                val offX = offsets[i]
                val cardW = cardBitmaps[i].width
                val rightBound = offX + cardW
                val t = templates[bitmapToTemplateIndex[i]]
                
                val cardText = StringBuilder()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (box.centerX() >= offX && box.centerX() <= rightBound) {
                            cardText.append(line.text).append(" ")
                        }
                    }
                }

                val rankStr = cardText.toString()
                if (rankStr.isNotEmpty()) {
                    val bestRank = matchRank(rankStr)
                    if (bestRank != null) {
                        val suitBmp = try { Bitmap.createBitmap(source, t.rectInSource.left + t.island.left, t.rectInSource.top + t.island.top, t.island.width(), t.island.height()) } catch(e: Exception) { null }
                        if (suitBmp != null) {
                            val suit = detectSuitInCard(suitBmp)
                            val card = Card(bestRank, suit)
                            val centerX = t.rectInSource.left + t.island.centerX()
                            if (t.isHole) foundHole.add(card to centerX) else foundComm.add(card to centerX)
                            suitBmp.recycle()
                        }
                    }
                }
            }
        }

        stitched.recycle()
        cardBitmaps.forEach { it.recycle() }

        val finalComm = foundComm.sortedBy { it.second }.map { it.first }.distinct()
        val finalHole = foundHole.sortedBy { it.second }.map { it.first }.distinct()

        return Pair(finalComm, finalHole)
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
        val queueX = IntArray(3000)
        val queueY = IntArray(3000)
        var head = 0
        var tail = 0
        
        queueX[tail] = startX
        queueY[tail] = startY
        tail++
        visited[startY * width + startX] = true
        
        var minX = startX; var maxX = startX; var minY = startY; var maxY = startY
        
        while (head < tail && tail < 3000) {
            val px = queueX[head]
            val py = queueY[head]
            head++
            
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
            
            val step = 2
            val dx = intArrayOf(step, -step, 0, 0)
            val dy = intArrayOf(0, 0, step, -step)
            
            for (i in 0 until 4) {
                val nx = px + dx[i]
                val ny = py + dy[i]
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!visited[nIdx]) {
                        visited[nIdx] = true
                        if (isWhiteCardPixel(bitmap.getPixel(nx, ny))) {
                            if (tail < 3000) {
                                queueX[tail] = nx
                                queueY[tail] = ny
                                tail++
                            }
                        }
                    }
                }
            }
        }
        val rect = android.graphics.Rect(minX, minY, maxX, maxY)
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
