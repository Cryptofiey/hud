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

import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.tasks.await

class ScreenScanner(
    private val context: Context,
    private val resultData: Intent?,
    private val resultCode: Int,
    private val stopAfterProfileScan: Boolean = false
) {
    private val pokerHudService = context as? PokerHudService
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private fun isColorfulButton(bitmap: android.graphics.Bitmap, box: android.graphics.Rect): Boolean {
        // Expand the search area slightly to ensure we check the button background
        // 0.2f horizontal and 0.5f vertical expands enough beyond the white text.
        val xExpand = (box.width() * 0.2f).toInt()
        val yExpand = (box.height() * 0.5f).toInt()
        
        val bounds = android.graphics.Rect(
            (box.left - xExpand).coerceAtLeast(0),
            (box.top - yExpand).coerceAtLeast(0),
            (box.right + xExpand).coerceAtMost(bitmap.width - 1),
            (box.bottom + yExpand).coerceAtMost(bitmap.height - 1)
        )
        
        var brightColorPixels = 0
        var totalSamples = 0
        
        // Sample a tighter grid
        val xStep = (bounds.width() / 15).coerceAtLeast(1)
        val yStep = (bounds.height() / 15).coerceAtLeast(1)
        
        for (x in bounds.left..bounds.right step xStep) {
            for (y in bounds.top..bounds.bottom step yStep) {
                val color = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(color)
                val g = android.graphics.Color.green(color)
                val b = android.graphics.Color.blue(color)
                
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val diff = maxC - minC
                
                // Action buttons are BRIGHT and HIGHLY SATURATED.
                // Pre-action buttons are either transparent (background is dark table felt, so maxC is low usually < 60)
                // or they are light grey (so diff is very small).
                // White text has high maxC but very low diff.
                // Adjusted down to 80/35 to support users with "Extra Dim" Android screen overlays
                if (maxC > 80 && diff > 35) {
                    brightColorPixels++
                }
                totalSamples++
            }
        }
        
        // If we found at least 3 pixels matching the bright saturated profile, it's an action button
        return brightColorPixels >= 3
    }

    companion object {
<<<<<<< HEAD
        val recognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
=======
<<<<<<< HEAD
        val recognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
=======
        val recognizer: com.google.mlkit.vision.text.TextRecognizer? by lazy {
            null
>>>>>>> origin/main
>>>>>>> origin/main
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null
    
<<<<<<< HEAD
=======
    var debugLogInfo = ""
    
>>>>>>> origin/main
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
                ScannerConfig.activeProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
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
                delay(200) // Small delay to allow UI to update and hide overlays
                while (isActive) {
                    val gotImage = processLatestImage()
                    if (gotImage) {
                        delay(30) // Fast polling rate for snappier responses
                    } else {
                        delay(10) // Poll faster while waiting for next frame
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Error starting scanner", e)
            scanStatus.value = "Scanner start failed: ${e.message}"
            isScanning.value = false
        }
    }

    private val holeHistory = mutableListOf<List<Card?>>()
    private val commHistory = mutableListOf<List<Card?>>()
    private var emptyOpponentsFrames = 0
    
    private val confirmedHole = mutableListOf<Card?>()
    private val confirmedComm = mutableListOf<Card?>()
    private var lastPasswordScreenTime = 0L

    private fun findCardSlots(bitmap: Bitmap, rect: android.graphics.Rect, expectedMaxCards: Int): List<android.graphics.Rect> {
        if (rect.width() <= 0 || rect.height() <= 0) return emptyList()
        val w = rect.width()
        val h = rect.height()
        val startX = rect.left
        val topY = rect.top

        val yStart = topY + (h * 0.3).toInt()
        val yEnd = topY + (h * 0.7).toInt()

        if (yEnd <= yStart) return emptyList()
        if (startX + w > bitmap.width || yEnd > bitmap.height) return emptyList()

        val columnActivity = IntArray(w)
        for (x in 0 until w) {
            var activity = 0
            for (y in yStart..yEnd step 3) {
                val p = bitmap.getPixel(startX + x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                activity += maxOf(r, maxOf(g, b))
            }
            columnActivity[x] = activity
        }

        var minA = Int.MAX_VALUE
        var maxA = Int.MIN_VALUE
        for (a in columnActivity) {
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }

        val ySteps = ((yEnd - yStart) / 3) + 1
        val maxAvg = maxA / ySteps
        val minAvg = minA / ySteps
        
        if (maxAvg < 40) return emptyList() // Max pixel intensity is < 40 on average, definitely just dark table
        
        // If there's barely any color variation across the row, don't chop it randomly.
        // It could be all table, or all card.
        if (maxAvg - minAvg < 15) {
            // High intensity but no variation means it's likely a single solid block (e.g. zoomed in card)
            // Just return one big slot covering the whole thing.
            return listOf(android.graphics.Rect(startX, topY, startX + w, rect.bottom))
        }

        val threshold = minA + (maxA - minA) / 4
        
        val columnIsCard = BooleanArray(w)
        for (x in 0 until w) {
            val avgPixel = columnActivity[x] / ySteps
            columnIsCard[x] = columnActivity[x] > threshold && avgPixel > 15
        }

        val rawSlots = mutableListOf<android.graphics.Rect>()
        var inCard = false
        var cardStartX = 0

        for (x in 0 until w) {
            if (columnIsCard[x] && !inCard) {
                inCard = true
                cardStartX = x
            } else if (!columnIsCard[x] && inCard) {
                inCard = false
                val cardEndX = x - 1
                rawSlots.add(android.graphics.Rect(startX + cardStartX, rect.top, startX + cardEndX, rect.bottom))
            }
        }
        if (inCard) {
            rawSlots.add(android.graphics.Rect(startX + cardStartX, rect.top, startX + w - 1, rect.bottom))
        }

        val validSlots = rawSlots.filter { it.width() > w * 0.03f }

        val finalSlots = mutableListOf<android.graphics.Rect>()
        val expectedCardWidth = if (expectedMaxCards > 0) w / expectedMaxCards else w
        for (slot in validSlots) {
            val count = maxOf(1, Math.round(slot.width().toFloat() / expectedCardWidth.toFloat()))
            val splitWidth = slot.width() / count
            for (i in 0 until count) {
                val subLeft = slot.left + i * splitWidth
                val subRight = if (i == count - 1) slot.right else subLeft + splitWidth
                finalSlots.add(android.graphics.Rect(subLeft, slot.top, subRight, slot.bottom))
            }
        }
        
        return finalSlots.take(expectedMaxCards)
    }

    fun getSmoothedCards(history: MutableList<List<Card?>>, newCards: List<Card?>, confirmed: MutableList<Card?>, windowSize: Int = 4): List<Card?> {
        var maxHistoryRecords = windowSize
        
        history.add(newCards)
        if (history.size > maxHistoryRecords) {
            history.removeAt(0)
        }
        
        // Dynamically clear history: if the last few frames are entirely empty, wipe everything.
        // For hole cards, we want them to clear out quickly when folded or new hand starts (e.g. 2 empty frames).
        val clearThreshold = if (history === holeHistory) 2 else windowSize - 1
        if (clearThreshold > 0 && history.size >= clearThreshold && history.takeLast(clearThreshold).all { list -> list.all { it == null } }) {
            history.clear()
            confirmed.clear()
            return emptyList()
        }
        
        val maxLen = history.maxOfOrNull { it.size } ?: 0
        while (confirmed.size < maxLen) confirmed.add(null)
        
        val result = mutableListOf<Card?>()
        
        for (i in 0 until maxLen) {
            val cardsAtI = history.mapNotNull { it.getOrNull(i) }
            if (cardsAtI.isEmpty()) {
                result.add(null)
                confirmed[i] = null
                continue
            }
            
            val counts = cardsAtI.groupingBy { it }.eachCount()
            val best = counts.maxByOrNull { it.value }
            
            val prevConfirmed = confirmed[i]
            
            if (best != null) {
                // Confirm the card instantly if it appears in history. To prevent flickering, we only 
                // revert to prevConfirmed if it has EQUAL count to best.
                val prevCount = counts[prevConfirmed] ?: 0
                if (prevConfirmed != null && prevCount >= best.value) {
                    result.add(prevConfirmed)
                } else {
                    result.add(best.key)
                    confirmed[i] = best.key
                }
            } else {
                result.add(null)
                confirmed[i] = null
            }
        }
        return result
    }

    private val bitmapLock = Any()
    private var cachedCleanBitmap: Bitmap? = null

    fun getLatestBitmapCopy(): Bitmap? {
        synchronized(bitmapLock) {
            val bmp = cachedCleanBitmap ?: return null
            if (bmp.isRecycled) return null
            return try {
                bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun applyCardThresholding(bmp: Bitmap, activeHoleRect: android.graphics.Rect?, activeCommRect: android.graphics.Rect?) {
        val rects = listOf(Pair(activeHoleRect, true), Pair(activeCommRect, false))
        for ((rect, isHole) in rects) {
            if (rect == null) continue
            val left = maxOf(0, rect.left)
            val top = maxOf(0, rect.top)
            val right = minOf(bmp.width, rect.right)
            val bottom = minOf(bmp.height, rect.bottom)
            
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) continue
            
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, left, top, width, height)
            
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                
                // CoinPoker cards use solid colored backgrounds with WHITE text for all suits.
                // To feed optimal images to ML Kit (which prefers dark text on light background),
                // we convert to grayscale and invert. This preserves anti-aliasing perfectly.
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val invertedLuma = 255 - Math.min(255, Math.max(0, luminance))
                
                // Enhance contrast slightly by spreading the inverted luminance
                val contrast = Math.min(255, Math.max(0, (invertedLuma - 40) * 255 / 180))
                
                pixels[i] = (0xFF shl 24) or (contrast shl 16) or (contrast shl 8) or contrast
            }
            
            bmp.setPixels(pixels, 0, width, left, top, width, height)
        }
    }

    // Expose processing logic for automated testing in DebugImageActivity
    suspend fun processGivenBitmap(context: Context, testBmp: Bitmap, hRect: android.graphics.Rect, cRect: android.graphics.Rect): Pair<List<Card?>, List<Card?>> = kotlinx.coroutines.coroutineScope {
<<<<<<< HEAD
=======
        debugLogInfo = ""
        debugLogInfo += "[DIAGNOSTICS] Input: ${testBmp.width}x${testBmp.height} | hRect=$hRect, cRect=$cRect\n"
        debugLogInfo += "Templates in memory: ${TemplateManager.templates.size}\n"

>>>>>>> origin/main
        val ocrBitmap = testBmp.copy(Bitmap.Config.ARGB_8888, true)
        
        // Emulate the first pass with Template Manager and OCR
        val templateDeferred = async(kotlinx.coroutines.Dispatchers.Default) {
            val tHole = mutableListOf<Pair<Card, android.graphics.Rect>>()
            val tComm = mutableListOf<Pair<Card, android.graphics.Rect>>()
            try {
                // Initialize using the provided context which works in Debug mode
                TemplateManager.init(context)
                
                val hWidth = Math.min(testBmp.width - hRect.left, hRect.width())
                val hHeight = Math.min(testBmp.height - hRect.top, hRect.height())
                if (hRect.left >= 0 && hRect.top >= 0 && hWidth > 0 && hHeight > 0) {
                    val holeCrop = Bitmap.createBitmap(testBmp, hRect.left, hRect.top, hWidth, hHeight)
                    val matches = TemplateManager.matchMultiple(holeCrop, 2)
                    for (match in matches) {
                        val gLeft = hRect.left + match.rect.left
                        val gTop = hRect.top + match.rect.top
                        val globalRect = android.graphics.Rect(gLeft, gTop, gLeft + match.rect.width(), gTop + match.rect.height())
                        val cleanStr = match.text.replace(Regex("[hdcsHDCS♥♦♣♠]"), "").trim().uppercase()
                        val rank = Rank.values().find { it.symbol.equals(cleanStr, ignoreCase = true) }
                        if (rank != null) {
                            val suit = robustDetectSuit(testBmp, globalRect) ?: Suit.SPADES
                            tHole.add(Pair(Card(rank, suit), globalRect))
                        }
                    }
                    holeCrop.recycle()
                }

                val cWidth = Math.min(testBmp.width - cRect.left, cRect.width())
                val cHeight = Math.min(testBmp.height - cRect.top, cRect.height())
                if (cRect.left >= 0 && cRect.top >= 0 && cWidth > 0 && cHeight > 0) {
                    val commCrop = Bitmap.createBitmap(testBmp, cRect.left, cRect.top, cWidth, cHeight)
                    val matches = TemplateManager.matchMultiple(commCrop, 5)
                    for (match in matches) {
                        val gLeft = cRect.left + match.rect.left
                        val gTop = cRect.top + match.rect.top
                        val globalRect = android.graphics.Rect(gLeft, gTop, gLeft + match.rect.width(), gTop + match.rect.height())
                        val cleanStr = match.text.replace(Regex("[hdcsHDCS♥♦♣♠]"), "").trim().uppercase()
                        val rank = Rank.values().find { it.symbol.equals(cleanStr, ignoreCase = true) }
                        if (rank != null) {
                            val suit = robustDetectSuit(testBmp, globalRect) ?: Suit.SPADES
                            tComm.add(Pair(Card(rank, suit), globalRect))
                        }
                    }
                    commCrop.recycle()
                }
<<<<<<< HEAD
            } catch (ignored: Exception) {}
=======
                debugLogInfo += "Template Matches -> Hole: ${tHole.map { it.first.toString() }}, Comm: ${tComm.map { it.first.toString() }}\n"
            } catch (e: Exception) {
                debugLogInfo += "Template Matching Error: ${e.message}\n"
            }
>>>>>>> origin/main
            Pair(tHole, tComm)
        }

        applyCardThresholding(ocrBitmap, hRect, cRect)
<<<<<<< HEAD
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(ocrBitmap, 0)
=======
<<<<<<< HEAD
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(ocrBitmap, 0)
=======
>>>>>>> origin/main
>>>>>>> origin/main
        
        val templateRes = templateDeferred.await()
        val templateHoleCards = templateRes.first
        val templateCommCards = templateRes.second
        
        val tempHoleCards = mutableListOf<Pair<Card, android.graphics.Rect>>()
        val tempCommCards = mutableListOf<Pair<Card, android.graphics.Rect>>()

        try {
<<<<<<< HEAD
            val result = Tasks.await(recognizer.process(image), 5, java.util.concurrent.TimeUnit.SECONDS)
            result.textBlocks.forEach { block ->
=======
<<<<<<< HEAD
            val result = Tasks.await(recognizer.process(image), 5, java.util.concurrent.TimeUnit.SECONDS)
            debugLogInfo += "OCR Result text: [${result.text.replace("\n", " \" ")}]\n"
            result.textBlocks.forEach { block ->
=======
            val result = if (recognizer != null) {
                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(ocrBitmap, 0)
                Tasks.await(recognizer!!.process(image), 5, java.util.concurrent.TimeUnit.SECONDS)
            } else null
            
            debugLogInfo += "OCR Result text: [${result?.text?.replace("\n", " \" ") ?: "NULL"}]\n"
            result?.textBlocks?.forEach { block ->
>>>>>>> origin/main
>>>>>>> origin/main
                block.lines.forEach { line ->
                    line.elements.forEach { element ->
                        val text = element.text.trim().uppercase()
                        if (text.length > 2 || text.isEmpty()) return@forEach
                        
                        var cleanText = text.replace("&", "8").replace("$", "8").replace("@", "Q").replace("%", "8").replace("?", "7").replace("!", "1")
                            .replace("O", "Q").replace("0", "Q").replace("D", "Q")
                        val rankText = cleanText.take(1)
                        var parsedRank = Rank.values().find { it.symbol.equals(rankText, ignoreCase = true) }
                        
                        if (parsedRank != null) {
                            val rect = element.boundingBox ?: return@forEach
                            parsedRank = refineRankWithPixelCheck(ocrBitmap, rect, parsedRank)
                            if (hRect.contains(rect.centerX(), rect.centerY())) {
                                val detectedSuit = robustDetectSuit(testBmp, rect) ?: Suit.SPADES
                                tempHoleCards.add(Pair(Card(parsedRank, detectedSuit), rect))
                            } else if (cRect.contains(rect.centerX(), rect.centerY())) {
                                val detectedSuit = robustDetectSuit(testBmp, rect) ?: Suit.SPADES
                                tempCommCards.add(Pair(Card(parsedRank, detectedSuit), rect))
                            }
                        }
                    }
                }
            }
<<<<<<< HEAD
        } catch (e: Exception) {
            e.printStackTrace()
=======
            debugLogInfo += "OCR Matches -> Hole: ${tempHoleCards.map { it.first.toString() }}, Comm: ${tempCommCards.map { it.first.toString() }}\n"
        } catch (e: Exception) {
            e.printStackTrace()
            debugLogInfo += "OCR Exception: ${e.javaClass.simpleName} - ${e.message}\n"
>>>>>>> origin/main
        }
        
        fun clusterCards(cards: List<Pair<Card, android.graphics.Rect>>, maxCards: Int, regionRect: android.graphics.Rect): MutableList<Card?> {
            val resultList = MutableList<Card?>(maxCards) { null }
            if (cards.isEmpty()) return resultList
            val sorted = cards.sortedBy { it.second.centerX() }
            val clusters = mutableListOf<MutableList<Pair<Card, android.graphics.Rect>>>()
            val clusterThreshold = if (maxCards == 5) regionRect.width() * 0.14f else regionRect.width() * 0.25f
            for (elem in sorted) {
                if (clusters.isEmpty()) {
                    clusters.add(mutableListOf(elem))
                } else {
                    val lastCluster = clusters.last()
                    val lastCx = lastCluster.map { it.second.centerX() }.average()
                    val matchRank = lastCluster.first().first.rank == elem.first.rank
                    
                    if (matchRank && (elem.second.centerX() - lastCx < clusterThreshold)) {
                        lastCluster.add(elem)
                    } else {
                        clusters.add(mutableListOf(elem))
                    }
                }
            }
            val resolvedCards = clusters.map { cluster ->
                val primaryCard = cluster.sortedBy { it.second.top }.first().first
                val avgX = cluster.map { it.second.centerX() }.average()
                Triple(primaryCard, avgX, cluster.sumOf { it.second.width() * it.second.height() })
            }
            val topCards = resolvedCards.sortedByDescending { it.third }.take(maxCards)
            val expectedSlotWidth = regionRect.width().toFloat() / maxCards
            for (cardInfo in topCards) {
                var slotIdx = ((cardInfo.second - regionRect.left) / expectedSlotWidth).toInt()
                if (slotIdx < 0) slotIdx = 0
                if (slotIdx >= maxCards) slotIdx = maxCards - 1
                if (resultList[slotIdx] == null) resultList[slotIdx] = cardInfo.first
            }
            return resultList
        }

        val safeTempHole = tempHoleCards.filter { ocrM -> 
            templateHoleCards.none { tmplM -> Math.abs(ocrM.second.centerX() - tmplM.second.centerX()) < 30 }
        }.toMutableList()
        safeTempHole.addAll(templateHoleCards)
        
        val safeTempComm = tempCommCards.filter { ocrM -> 
            templateCommCards.none { tmplM -> Math.abs(ocrM.second.centerX() - tmplM.second.centerX()) < 30 }
        }.toMutableList()
        safeTempComm.addAll(templateCommCards)

        val rawComm = clusterCards(safeTempComm, 5, cRect)
        val rawHole = clusterCards(safeTempHole, 2, hRect)

<<<<<<< HEAD
=======
        debugLogInfo += "Final rawHole: $rawHole | rawComm: $rawComm\n"

>>>>>>> origin/main
        ocrBitmap.recycle()
        Pair(rawHole, rawComm)
    }

    private suspend fun processLatestImage(): Boolean {
        var image: Image? = null
        var ocrBitmap: Bitmap? = null
        try {
            image = imageReader?.acquireLatestImage()

            if (image == null) return false
            
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val cleanBitmap: Bitmap
            synchronized(bitmapLock) {
                if (cachedCleanBitmap == null || cachedCleanBitmap!!.width != width || cachedCleanBitmap!!.height != height) {
                    cachedCleanBitmap?.recycle()
                    cachedCleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                cleanBitmap = cachedCleanBitmap!!

                buffer.position(0)
                if (pixelStride == 4 && rowStride == width * 4) {
                    cleanBitmap.copyPixelsFromBuffer(buffer)
                } else {
                    val rowPixels = IntArray(width)
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        val rowBuffer = buffer.asIntBuffer()
                        rowBuffer.get(rowPixels, 0, width)
                        for (i in 0 until width) {
                            val p = rowPixels[i]
                            val r = (p and 0xFF) shl 16
                            val b = (p and 0xFF0000) shr 16
                            val ag = p and 0xFF00FF00.toInt()
                            rowPixels[i] = ag or r or b
                        }
                        cleanBitmap.setPixels(rowPixels, 0, width, 0, row, width, 1)
                    }
                }
            }
            // Must clear limit and position changes
            buffer.clear()
            
            // Release the frame quickly so Producer can queue next frames
            image.close()
            image = null

            val rects = withContext(Dispatchers.Main) {
                Triple(pokerHudService!!.getCommRect(), pokerHudService.getHoleRect(), pokerHudService.getHudRects())
            }
            val commRect = rects.first
            val holeRect = rects.second
            val hudRects = rects.third
            
            val activeCommRect = if (commRect.width() > 20) {
                android.graphics.Rect(
                    commRect.left,
                    commRect.top,
                    commRect.right,
                    commRect.bottom
                )
            } else {
                commRect
            }
            
            val activeHoleRect = if (holeRect.width() > 20) {
                android.graphics.Rect(
                    holeRect.left,
                    holeRect.top,
                    holeRect.right,
                    holeRect.bottom
                )
            } else {
                holeRect
            }
            
            val currentState = PokerHudSharedState.uiState.value

            val templateResAndOcrRes = kotlinx.coroutines.coroutineScope {
                val templateDeferred = async(Dispatchers.Default) {
                    val tHole = mutableListOf<Pair<Card, android.graphics.Rect>>()
                    val tComm = mutableListOf<Pair<Card, android.graphics.Rect>>()
                    try {
                        TemplateManager.init(context)
                        
                        val hWidth = Math.min(cleanBitmap!!.width - activeHoleRect.left, activeHoleRect.width())
                        val hHeight = Math.min(cleanBitmap!!.height - activeHoleRect.top, activeHoleRect.height())
                        if (activeHoleRect.left >= 0 && activeHoleRect.top >= 0 && hWidth > 0 && hHeight > 0) {
                            val holeCrop = Bitmap.createBitmap(cleanBitmap!!, activeHoleRect.left, activeHoleRect.top, hWidth, hHeight)
                            val matches = TemplateManager.matchMultiple(holeCrop, 2)
                            for (match in matches) {
                                val gLeft = activeHoleRect.left + match.rect.left
                                val gTop = activeHoleRect.top + match.rect.top
                                val gRight = activeHoleRect.left + match.rect.right
                                val gBottom = activeHoleRect.top + match.rect.bottom
                                val globalRect = android.graphics.Rect(gLeft, gTop, gRight, gBottom)
                                
                                val cleanStr = match.text.replace(Regex("[hdcsHDCS♥♦♣♠]"), "").trim().uppercase()
                                val rank = Rank.values().find { it.symbol.equals(cleanStr, ignoreCase = true) }
                                
                                if (rank != null) {
                                    val suit = robustDetectSuit(cleanBitmap!!, globalRect) ?: Suit.SPADES
                                    tHole.add(Pair(Card(rank, suit), globalRect))
                                }
                            }
                            holeCrop.recycle()
                        }

                        val cWidth = Math.min(cleanBitmap!!.width - activeCommRect.left, activeCommRect.width())
                        val cHeight = Math.min(cleanBitmap!!.height - activeCommRect.top, activeCommRect.height())
                        if (activeCommRect.left >= 0 && activeCommRect.top >= 0 && cWidth > 0 && cHeight > 0) {
                            val commCrop = Bitmap.createBitmap(cleanBitmap!!, activeCommRect.left, activeCommRect.top, cWidth, cHeight)
                            val matches = TemplateManager.matchMultiple(commCrop, 5)
                            for (match in matches) {
                                val gLeft = activeCommRect.left + match.rect.left
                                val gTop = activeCommRect.top + match.rect.top
                                val gRight = activeCommRect.left + match.rect.right
                                val gBottom = activeCommRect.top + match.rect.bottom
                                val globalRect = android.graphics.Rect(gLeft, gTop, gRight, gBottom)
                                
                                val cleanStr = match.text.replace(Regex("[hdcsHDCS♥♦♣♠]"), "").trim().uppercase()
                                val rank = Rank.values().find { it.symbol.equals(cleanStr, ignoreCase = true) }
                                
                                if (rank != null) {
                                    val suit = robustDetectSuit(cleanBitmap!!, globalRect) ?: Suit.SPADES
                                    tComm.add(Pair(Card(rank, suit), globalRect))
                                }
                            }
                            commCrop.recycle()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    Pair(tHole, tComm)
                }

                // 1. RUN FULL SCREEN OCR
                ocrBitmap = cleanBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
                applyCardThresholding(ocrBitmap!!, activeHoleRect, activeCommRect)
                
<<<<<<< HEAD
=======
<<<<<<< HEAD
>>>>>>> origin/main
                val inputImage = InputImage.fromBitmap(ocrBitmap!!, 0)
                val ocrRes = try {
                    Tasks.await(recognizer.process(inputImage), 5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
<<<<<<< HEAD
=======
=======
                val ocrRes: com.google.mlkit.vision.text.Text? = null
>>>>>>> origin/main
>>>>>>> origin/main
                val templateRes = templateDeferred.await()
                Pair(templateRes, ocrRes)
            }

            val templateResult = templateResAndOcrRes.first
            val result = templateResAndOcrRes.second

            val templateHoleCards = templateResult.first
            val templateCommCards = templateResult.second
            
            // We do NOT recycle ocrBitmap immediately as we will use it for pixel-level rank checks.

            // 2. EXTRACT CARDS BY BOUNDING BOX INTERSECT
            val tempCommCards = mutableListOf<Pair<Card, android.graphics.Rect>>()
            val tempHoleCards = mutableListOf<Pair<Card, android.graphics.Rect>>()
            
            val commElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()
            val holeElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()
            val actionButtonsMap = mutableMapOf<String, android.graphics.Rect>()
            val transitionButtonsMap = mutableMapOf<String, android.graphics.Rect>()
            val sizingButtonsMap = mutableMapOf<String, android.graphics.Rect>()

            // We look for action buttons in the bottom 15% of the screen (to exclude opponent status tags and speech bubbles near the hero)
            val bottomZoneTop = cleanBitmap!!.height * 0.85

            var scannedPotSize: Float? = null
            
            val fullScanText = result?.text?.uppercase() ?: ""
            
            // Abort processing immediately if we are viewing our own settings screen or Android launcher
            if (fullScanText.contains("POKER EQUITY HUD") || fullScanText.contains("ADVISOR ADVANCED FEATURES") || fullScanText.contains("CALIBRATION BOUNDING BOXES")) {
                scanStatus.value = "Агент-Сторож: Открыты настройки HUD. Автокликер приостановлен."
                PokerHudSharedState.externalActions.tryEmit(
                    ExternalAction.UpdateCards(
                        hero1 = null, hero2 = null, board = emptyList(), opponents = emptyList(),
                        profileBoxes = null, updateProfileBoxes = false, rawScannerBoxes = null,
                        potSize = null, heroActionOptions = emptyList(), heroTurn = false,
                        heroStack = null, heroBet = null, tablePosition = null,
                        smallBlind = null, bigBlind = null, tournamentStage = null,
                        isBbDisplay = false
                    )
                )
                image?.close()
                return true
            }
            
            val textUpper = fullScanText
            var isPasswordScreen = false
            if (textUpper.contains("PASSWORD") || textUpper.contains("ПАРОЛЬ") || 
                textUpper.matches(Regex(".*\\bПИН\\b.*")) || textUpper.matches(Regex(".*\\bPIN\\b.*")) || 
                textUpper.contains("ВВЕДИТЕ") || textUpper.contains("ENTER CODE") ||
                textUpper.matches(Regex(".*\\bLOG IN\\b.*")) || textUpper.matches(Regex(".*\\bLOGIN\\b.*"))) {
                isPasswordScreen = true
                lastPasswordScreenTime = System.currentTimeMillis()
            }
            
            val elapsedSinceLastPass = System.currentTimeMillis() - lastPasswordScreenTime
            val triggerHide = isPasswordScreen
            
            if (triggerHide) {
                PokerHudSharedState.triggerPasswordHiding()
            }

            val linesList = mutableListOf<com.google.mlkit.vision.text.Text.Line>()
            result?.textBlocks?.forEach { block ->
                block.lines.forEach { line ->
                    linesList.add(line)
                }
            }

            for (line in linesList) {
                val lineBox = line.boundingBox
                if (lineBox != null && (lineBox.centerY() < cleanBitmap!!.height * 0.20f || lineBox.centerY() > cleanBitmap!!.height * 0.65f)) {
                    continue // POT text is located around the upper-middle of the screen
                }
                
                val lineText = line.text.uppercase()
                if (lineText.contains("POT") || lineText.contains("ПОТ")) {
                    val combinedText = if (lineBox != null) {
                        OpponentScanner.mergeRightsideDigits(lineBox, line.text, linesList)
                    } else {
                        line.text
                    }
                    val potValMatch = Regex("(POT|ПОТ)[^0-9]*([0-9.,KMkmBb]+)").find(combinedText.uppercase())
                    if (potValMatch != null) {
                        val tempStr = potValMatch.groupValues[2].replace(",", ".").uppercase()
                        val multiplier = when {
                            tempStr.contains("K") -> 1000f
                            tempStr.contains("M") -> 1000000f
                            else -> 1f
                        }
                        val numStr = tempStr.replace(Regex("[^0-9.]"), "")
                        if (numStr.isNotEmpty() && numStr.count { it == '.' } <= 1) {
                            val value = (numStr.toFloatOrNull() ?: 0f) * multiplier
                            if (value > 0f) {
                                scannedPotSize = value
                                break
                            }
                        }
                    }
                }
            }
            
            val heroActionOptions = mutableSetOf<String>()
            var hasPreactions = false

            for (block in result?.textBlocks ?: emptyList()) {
                for (line in block.lines) {
                    val lineBox = line.boundingBox
                    if (lineBox != null && lineBox.width() >= 10 && lineBox.height() >= 10) {
                        // Normalize letters & strip whitespace, hyphens etc. for robust matching under OCR errors
                        val lineTextUpper = line.text.uppercase(java.util.Locale.US)
                        val lineNormalized = lineTextUpper
                            .replace(" ", "")
                            .replace("-", "")
                            .replace("_", "")
                            .replace("А", "A") // Cyrillic to Latin mapping
                            .replace("В", "B")
                            .replace("Е", "E")
                            .replace("К", "K")
                            .replace("М", "M")
                            .replace("Н", "H")
                            .replace("О", "O")
                            .replace("Р", "P")
                            .replace("С", "C")
                            .replace("Т", "T")
                            .replace("Х", "X")
                            .replace("У", "Y")
                            .replace("И", "I")

                        if (lineBox.top > cleanBitmap!!.height * 0.55) {
                            if (lineNormalized.contains("X/F") || lineNormalized.contains("X/C") ||
                                lineNormalized.contains("CALLANY") || lineNormalized.contains("КОЛЛЛЮБЫЕ") ||
                                (lineNormalized.contains("CALL") && (lineNormalized.contains("ANY") || lineNormalized.contains("ANV"))) ||
                                lineNormalized.contains("CHECK/FOLD") || lineNormalized.contains("PLAYNEXT") ||
                                lineNormalized.contains("FOLD/ANY") || lineNormalized.contains("CALL3.") || 
                                lineNormalized.contains("CALL2.") || lineNormalized.contains("ЧЕК/ФОЛД") ||
                                (lineNormalized.contains("PLAY") && lineNormalized.contains("NEXT"))) {
                                hasPreactions = true
                            }
                        }

                    }

                        line.elements.forEach { element ->
                        val box = element.boundingBox ?: return@forEach
                        
                        // Ignore tiny text for action/transition buttons to prevent clicking on player avatars/names
                        // 0.008f is approx 19px on 2400h screen, small enough to catch button text but avoid static noise
                        if (box.height() < cleanBitmap!!.height * 0.008f) return@forEach
                        
                        var insideHud = false
                        for (hudRect in hudRects) {
                            if (android.graphics.Rect.intersects(hudRect, box)) {
                                insideHud = true
                                break
                            }
                        }
                        if (insideHud) return@forEach
                        
                        val rawText = element.text.uppercase(java.util.Locale.US)
                        val normalized = rawText
                            .replace(" ", "")
                            .replace("-", "")
                            .replace("_", "")
                            .replace("А", "A") // Cyrillic to Latin mapping
                            .replace("В", "B")
                            .replace("Е", "E")
                            .replace("К", "K")
                            .replace("М", "M")
                            .replace("Н", "H")
                            .replace("О", "O")
                            .replace("Р", "P")
                            .replace("С", "C")
                            .replace("Т", "T")
                            .replace("Х", "X")
                            .replace("У", "Y")
                            .replace("И", "I")

                        var isTransitionButton = false
                        var transitionKey = ""
                        
                        when {
                            // Register
                            normalized == "REGISTER" || normalized.contains("РЕГИСТР") || 
                            normalized.contains("УЧАСТВ") || normalized.contains("ЗАРЕГ") || 
                            normalized.contains("ЗАПИС") -> {
                                isTransitionButton = true
                                transitionKey = "REGISTER"
                            }
                            // Buy In
                            normalized == "BUYIN" || normalized == "BUIIN" || 
                            normalized == "BUY1N" || normalized == "REBUY" || 
                            normalized == "ADDON" || normalized.contains("TOPUP") ||
                            normalized.contains("БАЙИН") || normalized.contains("ПОПОЛНИТЬ") ||
                            normalized.contains("БЙИН") || normalized.contains("КУПИТЬ") || 
                            normalized.contains("РЕБАЙ") || normalized.contains("АДДОН") ||
                            (normalized.contains("BUYIN") && box.height() > 20) -> {
                                isTransitionButton = true
                                transitionKey = "BUY_IN"
                            }
                            // Play
                            normalized == "PLAYNOW" || normalized == "PLAY" || 
                            normalized == "START" || normalized == "ИГРАТЬ" || 
                            normalized == "НАЧАТЬ" || normalized.contains("ИГРАТЬС") -> {
                                isTransitionButton = true
                                transitionKey = "PLAY"
                            }
                            // Take seat
                            normalized == "TAKESEAT" || normalized == "SITDOWN" || 
                            normalized == "GOTOTABLE" || normalized.contains("ЗАНЯТЬМ") || 
                            normalized.contains("ЗАСТОЛ") || normalized.contains("КСТОЛУ") || 
                            normalized.contains("ПЕРЕЙТИК") -> {
                                isTransitionButton = true
                                transitionKey = "TAKE_SEAT"
                            }
                            // Join Back
                            normalized == "JOINBACK" || normalized == "IMBACK" || normalized == "RETURN" || 
                            normalized == "BACK" || normalized.contains("ВЕРНУТЬСЯ") || normalized.contains("ЯВЕРНУ") || 
                            normalized.contains("ПРОДОЛЖИТЬИГРУ") -> {
                                isTransitionButton = true
                                transitionKey = "JOIN_BACK"
                            }
                            // Join
                            normalized == "JOIN" || normalized == "ENTER" || 
                            normalized == "ВОЙТИ" || normalized == "ВХОД" || 
                            normalized.contains("ПРИСОЕД") -> {
                                isTransitionButton = true
                                transitionKey = "JOIN"
                            }
                            // OK / Agree / Yes / Close
                            rawText == "OK" || rawText == "YES" || rawText == "ДА" || 
                            normalized == "AGREE" || normalized == "PROCEED" || 
                            normalized == "CONTINUE" || normalized == "CLOSE" || 
                            normalized.contains("СОГЛАС") || normalized.contains("ПРИНЯТ") || 
                            normalized.contains("ЗАКРЫТ") || normalized.contains("ХОРОШО") -> {
                                isTransitionButton = true
                                transitionKey = "OK"
                            }
                            // Confirm
                            normalized == "CONFIRM" || normalized == "SUBMIT" || 
                            normalized.contains("ПОДТВЕРД") -> {
                                isTransitionButton = true
                                transitionKey = "CONFIRM"
                            }
                        }
                        
                        if (isTransitionButton) {
                            transitionButtonsMap[transitionKey] = line.boundingBox ?: box
                        }
                        
                        val cx = box.centerX()
                        val cy = box.centerY()
                        
                        if (commRect.width() > 20 && android.graphics.Rect.intersects(activeCommRect, box)) {
                            commElements.add(element)
                        } else if (holeRect.width() > 20 && android.graphics.Rect.intersects(activeHoleRect, box)) {
                            holeElements.add(element)
                        }
                        
                            // Action buttons logic
                            if (box.top > bottomZoneTop) {
                                val originalTextUpper = element.text.uppercase()
                                val textUpper = originalTextUpper.replace(" ", "")
                                
                                // Prevent tiny non-button text from being recognized:
                                if (box.width() < cleanBitmap!!.width * 0.01f) continue
                                if (box.height() < cleanBitmap!!.height * 0.005f) continue // Ignore tiny texts
                                
                                val isBright = isColorfulButton(cleanBitmap!!, box)
                                android.util.Log.d("BotActionDetect", "Bottom element: $originalTextUpper | isBright=$isBright")

                                val isAll = textUpper.contains("ALL-IN") || textUpper.contains("ALLIN") || (textUpper.contains("ALL") && !textUpper.contains("CALL")) || textUpper.contains("ОЛЛ") || textUpper.contains("ВЫСТАВИТЬ")
                                val isSizing = textUpper.contains("MAX") || textUpper.contains("МАКС") || textUpper.contains("POT") || textUpper.contains("ПОТ") || isAll || textUpper.contains("1/2") || textUpper.contains("3/4") || textUpper == "+" || textUpper == "-"
                                if (isSizing) {
                                    sizingButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                }

                                val isPrimary = textUpper.contains("FOLD") || textUpper.contains("ФОЛД") || 
                                               textUpper.contains("PАС") || textUpper.contains("CHECK") || 
                                               textUpper.contains("CALL") || textUpper.contains("КОЛЛ") || 
                                               textUpper.contains("RAISE") || textUpper.contains("РЕЙЗ") || 
                                               textUpper.contains("BET") || textUpper.contains("ALL-IN") || textUpper.contains("ALLIN")
                                
                                // Primary actions MUST be brightly colored (solid fill). 
                                // Grey/transparent pre-action buttons will fail此 check.
                                val qualifiesAsAction = isBright
                                
                                if ((textUpper.contains("FOLD") || textUpper.contains("ФОЛД") || textUpper.contains("ПАС")) && !textUpper.contains("ANY")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Fold")
                                        actionButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if ((textUpper.contains("CHECK") || textUpper.contains("ЧЕК")) && !textUpper.contains("FOLD") && !textUpper.contains("ФОЛД")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Check")
                                        actionButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if ((textUpper.contains("CALL") || textUpper.contains("КОЛЛ")) && !textUpper.contains("ANY") && !textUpper.contains("ЛЮБ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Call")
                                        actionButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if (textUpper.contains("RAISE") || textUpper.contains("РЕЙЗ") || 
                                         textUpper.contains("BET") || textUpper.contains("БЕТ") || 
                                         textUpper.contains("CONFIRM") || textUpper.contains("ПОДТВЕРДИТЬ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Raise") // Mapping Confirm to Raise internally
                                        actionButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if (textUpper.contains("ALL-IN") || textUpper.contains("ALLIN") || 
                                         textUpper.contains("ОЛЛ-ИН") || textUpper.contains("ALL") || 
                                         textUpper.contains("ОЛЛ") || textUpper.contains("ВЫСТАВИТЬ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("All-in")
                                        actionButtonsMap[originalTextUpper] = line.boundingBox ?: box
                                    }
                                }
                                else if (textUpper.contains("STRADDLE") || textUpper.contains("СТРАДДЛ")) {
                                    if (isBright) actionButtonsMap[originalTextUpper] = box
                                }
                                
                                // Add common bet sizing multipliers. These are usually grey, so we ignore color.
                                val noSpaceText = textUpper
                                if (noSpaceText == "1/2" || noSpaceText == "1/3" || noSpaceText == "2/3" || noSpaceText == "3/4" || 
                                    noSpaceText == "1/2POT" || noSpaceText == "2/3POT" || noSpaceText == "3/4POT" ||
                                    textUpper.contains("POT") || textUpper.contains("ПОТ") || textUpper.contains("MAX") || textUpper.contains("МАКС") ||
                                    textUpper.contains("MIN") || textUpper.contains("МИН") || textUpper.matches(Regex(".*\\d+X.*")) || textUpper.contains("%") ||
                                    noSpaceText == "+" || noSpaceText == "-") {
                                    actionButtonsMap[originalTextUpper] = box
                                }
                            }
                    }
                }
            }
            
            if (hasPreactions && actionButtonsMap.isEmpty()) {
                heroActionOptions.clear()
            } else if (actionButtonsMap.isNotEmpty()) {
                // If we detected at least one colorful action button, ensure we ignore any false-positive preactions.
                actionButtonsMap.keys.forEach { key ->
                    when {
                        key.contains("FOLD") || key.contains("ФОЛД") || key.contains("ПАС") -> heroActionOptions.add("Fold")
                        key.contains("CHECK") || key.contains("ЧЕК") -> heroActionOptions.add("Check")
                        key.contains("CALL") || key.contains("КОЛЛ") -> heroActionOptions.add("Call")
                        key.contains("RAISE") || key.contains("РЕЙЗ") || key.contains("BET") || key.contains("БЕТ") -> heroActionOptions.add("Raise")
                        key.contains("ALL-IN") || key.contains("ОЛЛ") -> heroActionOptions.add("All-in")
                    }
                }
            }
            
            // Periodically save screenshot if bot has actions available
            if (actionButtonsMap.isNotEmpty() || transitionButtonsMap.isNotEmpty()) {
                DebugLogManager.savePeriodicScreenshot(cleanBitmap!!, context)
            }

            commElements.sortBy { it.boundingBox?.left ?: 0 }
            holeElements.sortBy { it.boundingBox?.left ?: 0 }

            for (element in commElements) {
                val box = element.boundingBox ?: continue
                // Card ranks might be merged; allow up to 8.0 aspect ratio for a full 5-card board.
                if (box.width() > box.height() * 8.0f) continue
                
                // Minimum size threshold to filter out small text like pot size
                if (box.height() < commRect.height() * 0.04f) continue
                
                var rawText = element.text.trim().uppercase(java.util.Locale.US)
                rawText = rawText.replace("COINPOKER", "").replace("COIN", "").replace("POKER", "").trim()
                rawText = rawText.replace("ALL-IN", "").replace("ALL IN", "").replace("ALLIN", "")
                    .replace("ОЛЛ-ИН", "").replace("ОЛЛИН", "").replace("ОЛЛ ИН", "").replace("ALL", "").replace("ОЛЛ", "")
                    .replace("CHECK", "").replace("ЧЕК", "")
                    .replace("FOLD", "").replace("ФОЛД", "").replace("ПАС", "")
                    .replace("CALL", "").replace("КОЛЛ", "")
                    .replace("BET", "").replace("БЕТ", "")
                    .replace("RAISE", "").replace("РЕЙЗ", "")
                    .replace("STRADDLE", "").replace("СТРАДДЛ", "")
                    
                // Clean out typical text words to prevent false card detections
                var safeText = rawText.uppercase(java.util.Locale.US).trim()
                safeText = safeText.replace("POT", " ").replace("ПОТ", " ")
                safeText = safeText.replace("BB", " ").replace("ББ", " ")
                
                // Remove fractional sizes (e.g. 15.1)
                safeText = safeText.replace(Regex("\\b\\d+[.,]\\d+\\b"), " ")
                // Strip large chip stacks from comm rect, but allow 104, 105 etc. by negative lookahead for 10x
                safeText = safeText.replace(Regex("\\b(?!10\\d)\\d{3,}\\b"), " ")
                // DO NOT strip 11-99 for commRect, prevents parsing flops like 444
                safeText = safeText.replace(Regex("\\b[01]\\b"), " ") // Standalone 0 or 1
                
                if (safeText.isEmpty()) continue
                safeText = safeText.replace("OK", " ").replace("WAIT", " ")
                    .replace("OUTS", " ").replace("STRAIGHT", " ")
                    .replace("PAIR", " ").replace("FLUSH", " ").replace("HIGH", " ")
                    .replace("KIND", " ").replace("HOUSE", " ")
                    .replace("SHOW", " ").replace("MUCK", " ").replace("AUTO", " ")
                    .replace("OF", " ")
                    
                if (safeText.trim().isEmpty()) continue

                var parsedRanks = findCardsInText(safeText)
                
                // If a single text block claims exactly identical duplicate ranks (e.g. "KK") 
                // but the physical width of the text block is just one card wide, deduplicate it to avoid narrow slicing.
                if (parsedRanks.size > 1 && parsedRanks.toSet().size == 1 && box.width() < commRect.width() * 0.15f) {
                    parsedRanks = listOf(parsedRanks.first())
                }
                
                for ((idx, rank) in parsedRanks.withIndex()) {
                    val sliceWidth = box.width() / parsedRanks.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    val refinedRank = refineRankWithPixelCheck(ocrBitmap, sliceBox, rank)
                    val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                    tempCommCards.add(Pair(Card(refinedRank, suit), sliceBox))
                }
            }
            
            for (element in holeElements) {
                val box = element.boundingBox ?: continue
                if (box.width() > box.height() * 6.0f) continue
                
                var rawText = element.text.trim().uppercase(java.util.Locale.US)
                rawText = rawText.replace("COINPOKER", "").replace("COIN", "").replace("POKER", "").trim()
                rawText = rawText.replace("ALL-IN", "").replace("ALL IN", "").replace("ALLIN", "")
                    .replace("ОЛЛ-ИН", "").replace("ОЛЛИН", "").replace("ОЛЛ ИН", "").replace("ALL", "").replace("ОЛЛ", "")
                    .replace("CHECK", "").replace("ЧЕК", "")
                    .replace("FOLD", "").replace("ФОЛД", "").replace("ПАС", "")
                    .replace("CALL", "").replace("КОЛЛ", "")
                    .replace("BET", "").replace("БЕТ", "")
                    .replace("RAISE", "").replace("РЕЙЗ", "")
                    .replace("STRADDLE", "").replace("СТРАДДЛ", "")
                    
                // Clean out typical text words to prevent false card detections
                var safeText = rawText.uppercase(java.util.Locale.US).trim()
                safeText = safeText.replace("POT", " ").replace("ПОТ", " ")
                safeText = safeText.replace("BB", " ").replace("ББ", " ")
                
                // Remove fractional sizes (e.g. 15.1)
                safeText = safeText.replace(Regex("\\b\\d+[.,]\\d+\\b"), " ")
                // Do NOT strip \d{3,} for holeRect, because '104' is a valid pocket pair (10, 4) merged by OCR.
                // DO NOT strip 11-99 for holeRect, deletes pocket pairs like 44
                safeText = safeText.replace(Regex("\\b[01]\\b"), " ") // Standalone 0 or 1
                
                if (safeText.isEmpty()) continue
                safeText = safeText.replace("OK", " ").replace("WAIT", " ")
                    .replace("OUTS", " ").replace("STRAIGHT", " ")
                    .replace("PAIR", " ").replace("FLUSH", " ").replace("HIGH", " ")
                    .replace("KIND", " ").replace("HOUSE", " ")
                    .replace("SHOW", " ").replace("MUCK", " ").replace("AUTO", " ")
                    .replace("OF", " ")
                    
                if (safeText.trim().isEmpty()) continue

                var parsedRanksRaw = findCardsInText(safeText, isHoleCard = true)
                
                for ((idx, rankRaw) in parsedRanksRaw.withIndex()) {
                    // Slicing the bounding box if multiple ranks are merged in a single text block
                    val sliceWidth = box.width() / parsedRanksRaw.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    val refinedRank = refineRankWithPixelCheck(ocrBitmap, sliceBox, rankRaw)
                    val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                    tempHoleCards.add(Pair(Card(refinedRank, suit), sliceBox))
                }
            }
            
            fun clusterCards(cards: List<Pair<Card, android.graphics.Rect>>, maxCards: Int, regionRect: android.graphics.Rect): MutableList<Card?> {
                val resultList = MutableList<Card?>(maxCards) { null }
                if (cards.isEmpty()) return resultList
                
                // Group detections by their X center
                val sorted = cards.sortedBy { it.second.centerX() }
                val clusters = mutableListOf<MutableList<Pair<Card, android.graphics.Rect>>>()
                
                // Dynamic threshold based on region width.
                // We use 14% for board and 15% for hole cards to merge multiple scans of the SAME card 
                // (which often detects small top-left rank AND large center rank),
                // but small enough to NOT merge adjacent distinct cards.
                val clusterThreshold = if (maxCards == 5) {
                    regionRect.width() * 0.14f
                } else {
                    regionRect.width() * 0.15f
                }
                
                for (elem in sorted) {
                    if (clusters.isEmpty()) {
                        clusters.add(mutableListOf(elem))
                    } else {
                        val lastCluster = clusters.last()
                        val lastCx = lastCluster.map { it.second.centerX() }.average()
                        val matchRank = lastCluster.first().first.rank == elem.first.rank
                        
                        // If horizontal distance is small AND rank matches, they are the same card (e.g. top rank and bottom rank symbols)
                        if (matchRank && (elem.second.centerX() - lastCx < clusterThreshold)) {
                            lastCluster.add(elem)
                        } else {
                            clusters.add(mutableListOf(elem))
                        }
                    }
                }
                
                // Resolve each cluster (preferring the topmost upright symbol like CoinPoker does)
                val resolvedCards = clusters.map { cluster ->
                    val sortedClusterByTop = cluster.sortedBy { it.second.top }
                    val primaryCard = sortedClusterByTop.first().first
                    val avgX = cluster.map { it.second.centerX() }.average()
                    val area = cluster.sumOf { it.second.width() * it.second.height() }
                    Triple(primaryCard, avgX, area)
                }
                
                // Keep largest areas in case of noise, then map them to physical slots based on distance
                val topCards = resolvedCards.sortedByDescending { it.third }.take(maxCards)
                
                val expectedSlotWidth = regionRect.width().toFloat() / maxCards
                
                for (cardInfo in topCards) {
                    val card = cardInfo.first
                    val cx = cardInfo.second
                    
                    val relX = cx - regionRect.left
                    var slotIdx = (relX / expectedSlotWidth).toInt()
                    
                    // Clamp to valid slots
                    if (slotIdx < 0) slotIdx = 0
                    if (slotIdx >= maxCards) slotIdx = maxCards - 1
                    
                    // Conflict resolution: if slot occupied, place it in the next closest empty slot
                    if (resultList[slotIdx] == null) {
                        resultList[slotIdx] = card
                    } else {
                        // Find closest empty slot
                        var bestSlot = -1
                        var minDistance = Float.MAX_VALUE
                        for (i in 0 until maxCards) {
                            if (resultList[i] == null) {
                                val emptySlotTargetX = regionRect.left + (i * expectedSlotWidth) + (expectedSlotWidth / 2)
                                val dist = Math.abs(cx - emptySlotTargetX)
                                if (dist < minDistance) {
                                    minDistance = dist.toFloat()
                                    bestSlot = i
                                }
                            }
                        }
                        if (bestSlot != -1) {
                            resultList[bestSlot] = card
                        }
                    }
                }
                
                return resultList
            }

            // Filter out OCR detections that fall within the cluster radius of a Template detection
            // This forces `clusterCards` to only see our Template detection for that column
            val safeTempHole = tempHoleCards.filter { ocrM -> 
                !templateHoleCards.any { tM -> Math.abs(ocrM.second.centerX() - tM.second.centerX()) < holeRect.width() * 0.08f }
            }.toMutableList()
            safeTempHole.addAll(templateHoleCards)
            
            val safeTempComm = tempCommCards.filter { ocrM -> 
                !templateCommCards.any { tM -> Math.abs(ocrM.second.centerX() - tM.second.centerX()) < commRect.width() * 0.14f }
            }.toMutableList()
            safeTempComm.addAll(templateCommCards)

            var foundCommCardsRaw = clusterCards(safeTempComm, 5, commRect)
            var foundHoleCardsRaw = clusterCards(safeTempHole, 2, holeRect)

            var rawAll = (foundHoleCardsRaw + foundCommCardsRaw).filterNotNull()
            if (rawAll.size != rawAll.toSet().size) {
                scanStatus.value = "Warning: Duplicate cards detected. Ignoring duplicates in this frame."
                val seen = mutableSetOf<Card>()
                for (i in 0 until 5) {
                    val c = foundCommCardsRaw[i]
                    if (c != null) {
                        if (seen.contains(c)) foundCommCardsRaw[i] = null else seen.add(c)
                    }
                }
                for (i in 0 until 2) {
                    val c = foundHoleCardsRaw[i]
                    if (c != null) {
                        if (seen.contains(c)) foundHoleCardsRaw[i] = null else seen.add(c)
                    }
                }
            }

            var smoothedHole = getSmoothedCards(holeHistory, foundHoleCardsRaw, confirmedHole, windowSize = 3)
            var smoothedComm = getSmoothedCards(commHistory, foundCommCardsRaw, confirmedComm, windowSize = 4)
            
            val smoothedAll = (smoothedHole + smoothedComm).filterNotNull()
            if (smoothedAll.size != smoothedAll.toSet().size) {
                // Warning: Invalid smoothed detection (duplicates)
                // Do not clear history, just ignore duplicates for this frame
                val seen = mutableSetOf<Card>()
                for (i in 0 until 5) {
                    val c = smoothedComm.getOrNull(i)
                    if (c != null && !seen.add(c)) {
                        val mList = smoothedComm.toMutableList()
                        mList[i] = null
                        smoothedComm = mList
                    }
                }
                for (i in 0 until 2) {
                    val c = smoothedHole.getOrNull(i)
                    if (c != null && !seen.add(c)) {
                        val mList = smoothedHole.toMutableList()
                        mList[i] = null
                        smoothedHole = mList
                    }
                }
            }

            // Board never shrinks, but to prevent feedback loops keeping invalid/false extra cards forever,
            // we let the temporal smoothedComm rot out invalid cards naturally.
            val finalComm = smoothedComm
            
            var finalH1 = smoothedHole.getOrNull(0) ?: smoothedHole.firstOrNull() ?: null
            var finalH2 = smoothedHole.getOrNull(1) ?: smoothedHole.drop(1).firstOrNull() ?: null
            
            // Fallback: if we had player hole cards and they vanished but the board is active (non-empty),
            // preserve them to prevent temporary camera/occlusion drops from clearing the player's hand.
            val lastH1 = currentState.heroCard1
            val lastH2 = currentState.heroCard2
            val boardCount = finalComm.count { it != null }
            if (finalH1 == null && finalH2 == null && lastH1 != null && lastH2 != null && boardCount > 0) {
                finalH1 = lastH1
                finalH2 = lastH2
            }
            
            val finalBoard = List(5) { i ->
                finalComm.getOrNull(i)
            }
            
            val scannedOpponents = OpponentScanner.scan(result, cleanBitmap!!, hudRects, commRect, holeRect)
            val finalOpponentsRaw: List<OpponentState>
            if (scannedOpponents.isNotEmpty()) {
                emptyOpponentsFrames = 0
                finalOpponentsRaw = scannedOpponents
            } else {
                emptyOpponentsFrames++
                finalOpponentsRaw = if (emptyOpponentsFrames < 3) currentState.opponents else emptyList()
            }
            
            // Parse blinds and tournament stages from fullScanText
            var parsedSB: Float? = null
            var parsedBB: Float? = null
            var parsedStage: TournamentStage? = null
            
            val nlhMatch = Regex("NLH\\s*-\\s*([0-9.,KMk]+)\\s*/\\s*([0-9.,KMk]+)(?:\\s*\\((?:LEVEL\\s*)?(\\d+)\\))?", RegexOption.IGNORE_CASE).find(fullScanText)
            val levelMatch = Regex("LEVEL\\s*(\\d+)\\s*:\\s*([0-9.,KMk]+)\\s*/\\s*([0-9.,KMk]+)", RegexOption.IGNORE_CASE).find(fullScanText)
            
            if (nlhMatch != null) {
                parsedSB = parseCleanValue(nlhMatch.groupValues[1])
                parsedBB = parseCleanValue(nlhMatch.groupValues[2])
                
                val levelValStr = nlhMatch.groupValues.getOrNull(3)
                if (levelValStr?.isNotEmpty() == true) {
                    val levelVal = levelValStr.toIntOrNull()
                    if (levelVal != null) {
                        parsedStage = when {
                            levelVal <= 6 -> TournamentStage.EARLY
                            levelVal <= 14 -> TournamentStage.MIDDLE
                            else -> TournamentStage.LATE
                        }
                    }
                }
            } else if (levelMatch != null) {
                val levelVal = levelMatch.groupValues[1].toIntOrNull() ?: 1
                parsedStage = when {
                    levelVal <= 6 -> TournamentStage.EARLY
                    levelVal <= 14 -> TournamentStage.MIDDLE
                    else -> TournamentStage.LATE
                }
                parsedSB = parseCleanValue(levelMatch.groupValues[2])
                parsedBB = parseCleanValue(levelMatch.groupValues[3])
            }

            // Hero is usually at the bottom-center of the screen.
            // Exclude hero from opponents and extract hero stack.
            var scannedHeroStack: Float? = null
            var scannedHeroBet: Float? = null
            var heroBoundingBox: android.graphics.Rect? = null
            
            var validOpponents = finalOpponentsRaw.filter { opp ->
                val box = opp.boundingBox
                if (box != null && box.top > cleanBitmap!!.height * 0.72f && box.centerX() > cleanBitmap.width * 0.35f && box.centerX() < cleanBitmap.width * 0.65f) {
                    scannedHeroStack = opp.stackSize
                    scannedHeroBet = opp.betSize
                    heroBoundingBox = box
                    false // EXCLUDE from opponents
                } else {
                    opp.nickname != "Unknown" && opp.nickname != "Player"
                }
            }
            
            // Limit to 5 opponents max for 6-max format, or 8 for tournaments to support 8-max / 9-max properly
            val isTournament = fullScanText.contains("LEVEL", ignoreCase = true) || 
                               fullScanText.contains("RANK", ignoreCase = true) || 
                               fullScanText.contains("FREEROLL", ignoreCase = true) ||
                               fullScanText.contains("REG", ignoreCase = true) ||
                               levelMatch != null
            val maxOpponents = if (isTournament) 8 else 5
            if (validOpponents.size > maxOpponents) {
                validOpponents = validOpponents.sortedByDescending { it.boundingBox?.height() ?: 0 }.take(maxOpponents)
            }
            val finalOpponents = validOpponents

            // Build seated players list clockwise starting from Hero
            val seatedPlayers = mutableListOf<OpponentState>()
            val heroNick = "in2it"
            
            val heroState = OpponentState(
                id = 0,
                nickname = heroNick,
                stackSize = scannedHeroStack ?: currentState.heroStack,
                betSize = scannedHeroBet ?: currentState.heroBet,
                isActive = true,
                isRandom = false,
                boundingBox = heroBoundingBox ?: android.graphics.Rect(
                    (cleanBitmap!!.width * 0.4f).toInt(),
                    (cleanBitmap!!.height * 0.72f).toInt(),
                    (cleanBitmap!!.width * 0.6f).toInt(),
                    (cleanBitmap!!.height * 0.95f).toInt()
                )
            )
            seatedPlayers.add(heroState)
            
            for (opp in finalOpponents) {
                if (opp.nickname != "Unknown" && opp.nickname != "Player") {
                    seatedPlayers.add(opp)
                }
            }
            
            // Identify dynamic Dealer Button position
            var dealerPlayer: OpponentState? = null
            for (player in seatedPlayers) {
                val box = player.boundingBox ?: continue
                if (hasDealerButton(cleanBitmap!!, box, result?.textBlocks ?: emptyList())) {
                    dealerPlayer = player
                    break
                }
            }
            
            // Fallback rules if dealer button is temporarily blocked or hidden
            if (dealerPlayer == null) {
                // Try to find who was dealer last frame
                val prevDealerOpp = currentState.opponents.firstOrNull { it.isDealer }
                if (prevDealerOpp != null) {
                    // Even if they are temporarily missing from seatedPlayers due to OCR flicker, 
                    // we remember they were the dealer.
                    dealerPlayer = seatedPlayers.firstOrNull { it.nickname == prevDealerOpp.nickname } 
                        ?: prevDealerOpp // Use the old opponent state as the dealer
                } else if (currentState.position == TablePosition.BTN) {
                    // Only assume hero is dealer if hero was genuinely the dealer
                    dealerPlayer = heroState
                }
            }
            
            // The table is usually shifted slightly upwards on mobile to leave room for Hero's UI at the bottom.
            val screenCenterX = cleanBitmap!!.width / 2f
            val screenCenterY = cleanBitmap!!.height * 0.43f
            
            val playersWithAngles = seatedPlayers.map { player ->
                val box = player.boundingBox!!
                val dx = box.centerX() - screenCenterX
                val dy = box.centerY() - screenCenterY
                var angleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                angleDeg = (angleDeg + 360.0) % 360.0
                Pair(player, angleDeg)
            }
            
            val heroAngle = playersWithAngles.firstOrNull { it.first.id == 0 }?.second ?: 90.0
            
            val orderedSeated = playersWithAngles.sortedBy { (_, angle) ->
                (angle - heroAngle + 360.0) % 360.0
            }.map { it.first }
            
            var btnIdx = orderedSeated.indexOfFirst { it.nickname == dealerPlayer?.nickname }
            
            val mappedPositions = if (btnIdx != -1) {
                assignPositions(orderedSeated, btnIdx)
            } else {
                // If the dealer is missing entirely from the list (e.g. left table and no D visible),
                // fallback to the previous frame's exact positions to prevent random shuffles.
                val prevMap = mutableMapOf<String, TablePosition>()
                prevMap[heroNick] = currentState.position
                for (op in currentState.opponents) {
                    try {
                        prevMap[op.nickname] = TablePosition.valueOf(op.positionName)
                    } catch (e: Exception) {
                        prevMap[op.nickname] = TablePosition.BTN
                    }
                }
                prevMap
            }
            
            val heroPos = mappedPositions[heroNick] ?: currentState.position
            
            val finalOpponentsWithPositions = finalOpponents.map { opp ->
                val isD = (opp.nickname == dealerPlayer?.nickname)
                val pos = mappedPositions[opp.nickname] ?: TablePosition.BTN
                opp.copy(isDealer = isD, positionName = pos.name)
            }

            var profileBoxesToHighlight: List<ScannedBox>? = null
            if (PokerHudSharedState.triggerProfileScan.value) {
                requestProfileScan = true
                PokerHudSharedState.triggerProfileScan.value = false
            }
            if (requestProfileScan) {
                val scannedProfile = ProfileScanner.scan(result, cleanBitmap!!, hudRects)
                if (scannedProfile != null && scannedProfile.nickname != "Unknown_Profile") {
                    profileBoxesToHighlight = scannedProfile.profileBoundingBoxes
                    val prefsManager = PreferencesManager(context)
                    val existing = prefsManager.loadPlayerStats(scannedProfile.nickname)
                    val updated = existing.copy(
                        histVpip = scannedProfile.histVpip ?: existing.histVpip,
                        histPfr = scannedProfile.histPfr ?: existing.histPfr,
                        hist3Bet = scannedProfile.hist3Bet ?: existing.hist3Bet,
                        histFoldTo3Bet = scannedProfile.histFoldTo3Bet ?: existing.histFoldTo3Bet,
                        histCBet = scannedProfile.histCBet ?: existing.histCBet,
                        histFoldToCBet = scannedProfile.histFoldToCBet ?: existing.histFoldToCBet,
                        histSteal = scannedProfile.histSteal ?: existing.histSteal,
                        histCheckRaise = scannedProfile.histCheckRaise ?: existing.histCheckRaise,
                        histWtsd = scannedProfile.histWtsd ?: existing.histWtsd,
                        histWsd = scannedProfile.histWsd ?: existing.histWsd,
                        lastUpdated = System.currentTimeMillis()
                    )
                    prefsManager.savePlayerStats(updated)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Profile parsed: ${scannedProfile.nickname}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                requestProfileScan = false
            }

            val rawBoxes = if (PokerHudSharedState.showScannerBoxes.value) {
                result?.textBlocks?.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val rect = line.boundingBox
                        if (rect == null) null
                        else {
                            var insideHud = false
                            for (hudRect in hudRects) {
                                if (android.graphics.Rect.intersects(hudRect, rect)) {
                                    insideHud = true
                                    break
                                }
                            }
                            val textUpper = line.text.uppercase()
                            if (insideHud || textUpper.contains("COIN POKER") || textUpper.contains("COINPOKER") || textUpper == "COIN" || textUpper == "POKER") {
                                null
                            } else {
                                ScannedBox(rect, line.text)
                            }
                        }
                    }
                }
            } else {
                null
            }

            val trustedCoinPokerMarker = fullScanText.contains("COINPOKER") || fullScanText.contains("COIN POKER") || 
                fullScanText.contains("NLH") || fullScanText.contains("PLO") || isPasswordScreen || 
                fullScanText.contains("ПОТ") || fullScanText.contains("ALL-IN") || fullScanText.contains("ОЛЛ-ИН")
                
            val isProfileScreen = fullScanText.contains("VPIP") || fullScanText.contains("PFR") || fullScanText.contains("WTSD") || fullScanText.contains("WSD") || fullScanText.contains("C-BET")
            
            val hasTableElements = finalH1 != null || finalH2 != null || finalBoard.any { it != null } || scannedPotSize != null || (actionButtonsMap.isNotEmpty() && !isProfileScreen)
            
            // We only consider lobby transitions valid if we see a trusted marker anywhere on screen, 
            // OR if we already see table elements (so we know we are in the app).
            val validLobby = transitionButtonsMap.isNotEmpty() && (trustedCoinPokerMarker || hasTableElements) && !isProfileScreen

            val currentContext = if (isProfileScreen) {
                AppScreenState.COINPOKER_PROFILE
            } else if (hasTableElements) {
                AppScreenState.COINPOKER_TABLE
            } else if (validLobby || trustedCoinPokerMarker) {
                if (validLobby || isPasswordScreen) {
                    AppScreenState.COINPOKER_LOBBY
                } else {
                    AppScreenState.COINPOKER_UNKNOWN
                }
            } else {
                AppScreenState.APP_UNKNOWN
            }
            
            if (currentContext == AppScreenState.APP_UNKNOWN) {
                emptyOpponentsFrames++
                if (emptyOpponentsFrames < 30) {
                    // Temporary glitch, return and keep previous state
                    image?.close()
                    return true
                }
            } else {
                emptyOpponentsFrames = 0
            }
            
            PokerHudSharedState.appScreenContext.value = currentContext
            
            if (currentContext == AppScreenState.APP_UNKNOWN) {
                RobotPlayer.availableActionButtons = emptyMap()
                RobotPlayer.lobbyTransitionButtons = emptyMap()
                PokerHudSharedState.externalActions.tryEmit(
                    ExternalAction.UpdateCards(
                        hero1 = null, hero2 = null, board = emptyList(), opponents = emptyList(),
                        profileBoxes = null, updateProfileBoxes = false, rawScannerBoxes = null,
                        potSize = null, heroActionOptions = emptyList(), heroTurn = false,
                        heroStack = null, heroBet = null, tablePosition = null,
                        smallBlind = null, bigBlind = null, tournamentStage = null,
                        isBbDisplay = false
                    )
                )
                scanStatus.value = "Агент-Сторож: Приложение не распознано. Автокликер приостановлен."
                image?.close()
                return true
            } else if (currentContext == AppScreenState.COINPOKER_UNKNOWN) {
                RobotPlayer.availableActionButtons = emptyMap()
                RobotPlayer.lobbyTransitionButtons = emptyMap()
                RobotPlayer.sizingButtonsMap = emptyMap()
                scanStatus.value = "Агент-Сторож: Мы в CoinPoker, но неизвестная страница. Ждем..."
                heroActionOptions.clear()
            } else if (currentContext == AppScreenState.COINPOKER_PROFILE) {
                RobotPlayer.availableActionButtons = emptyMap()
                RobotPlayer.lobbyTransitionButtons = emptyMap()
                RobotPlayer.sizingButtonsMap = emptyMap()
                scanStatus.value = "Агент-Сторож: Открыт профиль игрока. Автокликер приостановлен."
                heroActionOptions.clear()
            } else {
                RobotPlayer.availableActionButtons = actionButtonsMap
                RobotPlayer.lobbyTransitionButtons = transitionButtonsMap
                RobotPlayer.sizingButtonsMap = sizingButtonsMap
            }

            PokerHudSharedState.externalActions.tryEmit(
                ExternalAction.UpdateCards(
                    hero1 = finalH1, 
                    hero2 = finalH2, 
                    board = finalBoard, 
                    opponents = finalOpponentsWithPositions, 
                    profileBoxes = profileBoxesToHighlight, 
                    updateProfileBoxes = (profileBoxesToHighlight != null), 
                    rawScannerBoxes = rawBoxes,
                    potSize = scannedPotSize,
                    heroActionOptions = heroActionOptions.toList(),
                    heroTurn = heroActionOptions.isNotEmpty(),
                    heroStack = scannedHeroStack,
                    heroBet = scannedHeroBet,
                    tablePosition = heroPos,
                    smallBlind = parsedSB,
                    bigBlind = parsedBB,
                    tournamentStage = parsedStage,
                    isBbDisplay = Regex("([0-9.,]+)\\s*(BB|ББ)").containsMatchIn(fullScanText)
                )
            )
            
            scanStatus.value = "H:${smoothedHole.filterNotNull().size}(${foundHoleCardsRaw.filterNotNull().size}) C:${smoothedComm.filterNotNull().size}(${foundCommCardsRaw.filterNotNull().size}) Ops:${finalOpponents.size}<br>" +
                               "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
            
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            try { ocrBitmap?.recycle() } catch(ignored: Exception) {}
            try { image?.close() } catch(ignored: Exception) {}
        }
        return true
    }

    private fun findCardsInText(text: String, isHoleCard: Boolean = false): List<Rank> {
        val found = mutableListOf<Rank>()
        // Pre-replace common OCR symbol mistakes before stripping
        var preProcessed = text.uppercase(java.util.Locale.US)
        preProcessed = preProcessed.replace("&", "8").replace("$", "8").replace("@", "Q").replace("%", "8").replace("?", "7").replace("!", "1")
        preProcessed = preProcessed.replace("U", "J").replace("]", "J").replace("[", "J")
        // Replace Cyrillic lookalikes
        preProcessed = preProcessed.replace("А", "A").replace("К", "K").replace("Т", "T").replace("В", "B").replace("О", "O").replace("С", "C").replace("Р", "P")

        // Keep only letters, digits, and suit symbols to form a dense string
        val raw = preProcessed.replace(Regex("[^A-Z0-9\u2660\u2663\u2665\u2666]"), "")
            
        if (raw.length > 20) return emptyList() // Too long, likely noise
        if (raw.isEmpty()) return emptyList()
        
        var i = 0
        while (i < raw.length) {
            var matched = false
            
            // Check for 10 first
            if (i + 1 < raw.length) {
                val sub = raw.substring(i, i + 2)
                if (sub == "10" || sub == "I0" || sub == "1O" || sub == "IO" || sub == "IQ" || sub == "1Q" || sub == "L0" || sub == "LO" || sub == "T0" || sub == "TO") {
                    found.add(Rank.TEN)
                    i += 2
                    matched = true
                }
            }
            
            if (!matched) {
                val c = raw[i]
                val r = when (c) {
                    'A' -> Rank.ACE
                    'K' -> Rank.KING
                    'Q', 'O', '0' -> Rank.QUEEN
                    'J', '1', 'I', 'L' -> Rank.JACK
                    '9' -> Rank.NINE
                    '8' -> Rank.EIGHT
                    '7' -> Rank.SEVEN
                    '6' -> Rank.SIX
                    '5' -> Rank.FIVE
                    '4' -> Rank.FOUR
                    '3' -> Rank.THREE
                    '2' -> Rank.TWO
                    'T' -> Rank.TEN
                    else -> null
                }
                
                if (r != null) {
                    found.add(r)
                    i++
                    matched = true
                }
            }
            
            if (!matched) {
                // Skip the unrecognized character.
                // We no longer invalidate the entire block because OCR often parses suit symbols (♠,♣,♥,♦)
                // as random noise letters (V, Y, S, D, etc.), which used to cause correct cards to be entirely ignored.
                i++
            }
        }
        
        return found
    }



    private fun robustDetectSuit(crop: Bitmap, rankBox: android.graphics.Rect): Suit? {
        val w = crop.width
        val h = crop.height

        // Expand the search area slightly to hit the card background.
        // Avoid excessive expansion to prevent hitting the table felt or avatar glow.
        val expandLeft = maxOf(4, (rankBox.width() * 0.2f).toInt())
        val expandRight = maxOf(4, (rankBox.width() * 0.2f).toInt())
        val expandTop = maxOf(4, (rankBox.height() * 0.2f).toInt())
        val expandBottom = maxOf(4, (rankBox.height() * 0.3f).toInt())

        val left = maxOf(0, rankBox.left - expandLeft)
        val right = minOf(w - 1, rankBox.right + expandRight)
        val top = maxOf(0, rankBox.top - expandTop)
        val bottom = minOf(h - 1, rankBox.bottom + expandBottom)
        
        var totalRed = 0L
        var totalGreen = 0L
        var totalBlue = 0L
        var count = 0
        
        for (x in left..right) {
            for (y in top..bottom) {
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val p = crop.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Skip bright white text pixels and nearly black shadow/border pixels.
                // Suit background is typically a mid-to-dark color but not absolute black or absolute white.
                if (r > 180 && g > 180 && b > 180) continue
                
                // We must NOT discard black pixels completely because Spades are black/gray.
                // But skip absolute #000000 to avoid screen letterboxing or borders
                if (r == 0 && g == 0 && b == 0) continue
                
                totalRed += r
                totalGreen += g
                totalBlue += b
                count++
            }
        }
        
        if (count == 0) return Suit.SPADES
        
        val avgR = (totalRed / count).toInt()
        val avgG = (totalGreen / count).toInt()
        val avgB = (totalBlue / count).toInt()
        
        android.util.Log.d("SuitDetect", "Box: $rankBox | AvgColor=($avgR, $avgG, $avgB) Count=$count")
        
        val maxColor = maxOf(avgR, avgG, avgB)
        val minColor = minOf(avgR, avgG, avgB)
        val chroma = maxColor - minColor
        
        // If the color is essentially gray/black/dark blue with very little color dominance, it's Spades
        // Spades in typical decks are black. In CoinPoker 4-color decks they are dark grey.
        // We raise chroma thresholds to stop false detecting Spade as Heart/Diamond due to antialiasing or table colors.
        if (chroma < 40 || (maxColor < 75 && chroma < 55)) {
            return Suit.SPADES
        }
        
        if (maxColor == avgR) return Suit.HEARTS
        if (maxColor == avgG) return Suit.CLUBS
        if (maxColor == avgB) return Suit.DIAMONDS
        
        return Suit.SPADES // Default fallback to Spade (black/grey)
    }

    private fun detectInkColor(ocrBitmap: Bitmap, rect: android.graphics.Rect): Int {
        val corners = listOf(
            Pair(0.05f, 0.05f),
            Pair(0.95f, 0.05f),
            Pair(0.05f, 0.95f),
            Pair(0.95f, 0.95f)
        )
        var whiteCount = 0
        var blackCount = 0
        for ((rx, ry) in corners) {
            val px = rect.left + (rect.width() * rx).toInt()
            val py = rect.top + (rect.height() * ry).toInt()
            if (px in 0 until ocrBitmap.width && py in 0 until ocrBitmap.height) {
                val color = ocrBitmap.getPixel(px, py)
                if (color == 0xFFFFFFFF.toInt()) whiteCount++
                else if (color == 0xFF000000.toInt()) blackCount++
            }
        }
        return if (whiteCount >= blackCount) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun getInkRatioAt(ocrBitmap: Bitmap, rect: android.graphics.Rect, rx: Float, ry: Float, inkColor: Int, searchRadius: Int = 1): Float {
        val cx = rect.left + (rect.width() * rx).toInt()
        val cy = rect.top + (rect.height() * ry).toInt()
        var inkCount = 0
        var totalCount = 0
        for (dx in -searchRadius..searchRadius) {
            for (dy in -searchRadius..searchRadius) {
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until ocrBitmap.width && py in 0 until ocrBitmap.height) {
                    val color = ocrBitmap.getPixel(px, py)
                    if (color == inkColor) {
                        inkCount++
                    }
                    totalCount++
                }
            }
        }
        return if (totalCount == 0) 0f else inkCount.toFloat() / totalCount
    }

    private fun refineRankWithPixelCheck(ocrBitmap: Bitmap?, rect: android.graphics.Rect, parsedRank: Rank): Rank {
        if (ocrBitmap == null) return parsedRank
        
        // Safety checks for coordinates and size
        if (rect.left < 0 || rect.top < 0 || rect.right > ocrBitmap.width || rect.bottom > ocrBitmap.height || rect.width() <= 0 || rect.height() <= 0) {
            return parsedRank
        }

        val inkColor = detectInkColor(ocrBitmap, rect)

        fun getRatio(rx: Float, ry: Float, radius: Int = 1): Float {
            return getInkRatioAt(ocrBitmap, rect, rx, ry, inkColor, radius)
        }

        return when (parsedRank) {
            Rank.THREE, Rank.EIGHT -> {
                // Verify 3 vs 8
                val midLeft = getRatio(0.20f, 0.50f, radius = 1)
                android.util.Log.d("RankRefiner", "3 vs 8 check for rect $rect: midLeft=$midLeft")
                if (midLeft > 0.35f) {
                    Rank.EIGHT
                } else {
                    Rank.THREE
                }
            }
            Rank.FIVE, Rank.SIX -> {
                // Verify 5 vs 6
                val bottomLeft = getRatio(0.20f, 0.65f, radius = 1)
                android.util.Log.d("RankRefiner", "5 vs 6 check: bottomLeft=$bottomLeft")
                if (bottomLeft > 0.35f) {
                    Rank.SIX
                } else {
                    Rank.FIVE
                }
            }
             Rank.NINE -> {
                // Verify 9 vs 6
                val topRight = getRatio(0.80f, 0.30f, radius = 1)
                val bottomLeft = getRatio(0.20f, 0.70f, radius = 1)
                android.util.Log.d("RankRefiner", "9 vs 6 check: topRight=$topRight, bottomLeft=$bottomLeft")
                if (bottomLeft > topRight + 0.15f) {
                    Rank.SIX
                } else {
                    Rank.NINE
                }
            }
            else -> parsedRank
        }
    }

    private fun parseCleanValue(str: String): Float? {
        val s = str.trim().uppercase()
            .replace(",", ".")
            .replace("A", "0")
            .replace("O", "0")
        val multiplier = when {
            s.endsWith("K") -> 1000f
            s.endsWith("M") -> 1000000f
            else -> 1f
        }
        val numOnly = s.filter { it.isDigit() || it == '.' }
        val parsed = numOnly.toFloatOrNull() ?: return null
        return parsed * multiplier
    }

    private fun hasDealerButton(bitmap: Bitmap, box: android.graphics.Rect, textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>): Boolean {
        // Expand the search box slightly to catch the dealer button which sits outside the main profile box
        val searchRegion = android.graphics.Rect(
            box.left - (bitmap.width * 0.08f).toInt(),
            box.top - (bitmap.height * 0.08f).toInt(),
            box.right + (bitmap.width * 0.08f).toInt(),
            box.bottom + (bitmap.height * 0.08f).toInt()
        )
        
        for (block in textBlocks) {
            for (line in block.lines) {
                val txt = line.text.trim().uppercase()
                val lineBox = line.boundingBox ?: continue
                if ((txt == "D" || txt == "DEALER" || txt == "BTN") && 
                    android.graphics.Rect.intersects(searchRegion, lineBox)) {
                    return true
                }
            }
        }
        
        val searchLeft = maxOf(0, searchRegion.left)
        val searchTop = maxOf(0, searchRegion.top)
        val searchRight = minOf(bitmap.width - 1, searchRegion.right)
        val searchBottom = minOf(bitmap.height - 1, searchRegion.bottom)
        
        var redPixelsCount = 0
        for (x in searchLeft..searchRight step 2) {
            for (y in searchTop..searchBottom step 2) {
                val p = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                if (r > 165 && g < 65 && b < 65) {
                    redPixelsCount++
                    if (redPixelsCount > 15) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun assignPositions(orderedPlayers: List<OpponentState>, dealerIdx: Int): Map<String, TablePosition> {
        val mapping = mutableMapOf<String, TablePosition>()
        val n = orderedPlayers.size
        if (n == 0) return emptyMap()
        
        val positionsPattern = when (n) {
            2 -> listOf(TablePosition.BTN, TablePosition.BB) // In Heads-Up, BTN is also SB, but post-flop BTN has positional advantage. For logic purposes, the dealer acts last post-flop. BB acts first.
            3 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB)
            4 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG)
            5 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.CO)
            6 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.MP, TablePosition.CO)
            7 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.MP, TablePosition.MP, TablePosition.CO)
            8 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.UTG, TablePosition.MP, TablePosition.MP, TablePosition.CO)
            9 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.UTG, TablePosition.MP, TablePosition.MP, TablePosition.CO, TablePosition.CO)
            else -> List(n) { i ->
                when (i) {
                    0 -> TablePosition.BTN
                    1 -> TablePosition.SB
                    2 -> TablePosition.BB
                    n - 1 -> TablePosition.CO
                    n - 2 -> TablePosition.MP
                    else -> TablePosition.UTG
                }
            }
        }
        
        for (i in 0 until n) {
            val player = orderedPlayers[i]
            val relIdx = (i - dealerIdx + n) % n
            val pos = positionsPattern.getOrNull(relIdx) ?: TablePosition.CO
            mapping[player.nickname] = pos
        }
        return mapping
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
                cachedCleanBitmap?.recycle()
                cachedCleanBitmap = null
            } catch (e: Exception) {
                android.util.Log.e("ScreenScanner", "Error stopping scanner", e)
            }
        }
    }
}
