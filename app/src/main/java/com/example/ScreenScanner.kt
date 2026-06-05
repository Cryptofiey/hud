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
                delay(200) // Small delay to allow UI to update and hide overlays
                while (isActive) {
                    val gotImage = processLatestImage()
                    if (gotImage) {
                        delay(500) // Normal polling rate
                    } else {
                        delay(50) // Poll faster while waiting for first frame
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
            columnIsCard[x] = columnActivity[x] > threshold && avgPixel > 35
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

    private fun getSmoothedCards(history: MutableList<List<Card?>>, newCards: List<Card?>, confirmed: MutableList<Card?>, windowSize: Int = 4): List<Card?> {
        history.add(newCards)
        if (history.size > windowSize) {
            history.removeAt(0)
        }
        
        // If last 4 frames were completely empty, reset history
        if (history.size >= 4 && history.takeLast(4).all { list -> list.all { it == null } }) {
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
                // If seen twice, or if we have no history to build 2 votes yet
                if (best.value >= 2 || history.size < 2) {
                    result.add(best.key)
                    confirmed[i] = best.key
                } else {
                    if (prevConfirmed != null) {
                        result.add(prevConfirmed)
                    } else {
                        result.add(null)
                        confirmed[i] = null
                    }
                }
            } else {
                result.add(null)
                confirmed[i] = null
            }
        }
        return result
    }

    private var cachedCleanBitmap: Bitmap? = null

    private suspend fun processLatestImage(): Boolean {
        var image: Image? = null
        try {
            image = imageReader?.acquireLatestImage()

            if (image == null) return false
            
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            if (cachedCleanBitmap == null || cachedCleanBitmap!!.width != width || cachedCleanBitmap!!.height != height) {
                cachedCleanBitmap?.recycle()
                cachedCleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val cleanBitmap = cachedCleanBitmap!!

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
            // Must clear limit and position changes
            buffer.clear()
            
            // Release the frame quickly so Producer can queue next frames
            image.close()
            image = null

            val rects = withContext(Dispatchers.Main) {
                Triple(pokerHudService.getCommRect(), pokerHudService.getHoleRect(), pokerHudService.getHudRects())
            }
            val commRect = rects.first
            val holeRect = rects.second
            val hudRects = rects.third
            
            val currentState = PokerHudSharedState.uiState.value

            // 1. RUN FULL SCREEN OCR
            val inputImage = InputImage.fromBitmap(cleanBitmap!!, 0)
            val result = recognizer.process(inputImage).await()

            // 2. EXTRACT CARDS BY BOUNDING BOX INTERSECT
            val tempCommCards = mutableListOf<Pair<Card, android.graphics.Rect>>()
            val tempHoleCards = mutableListOf<Pair<Card, android.graphics.Rect>>()
            
            val commElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()
            val holeElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()
            val actionButtonsMap = mutableMapOf<String, android.graphics.Rect>()

            // We look for action buttons in the bottom 20% of the screen
            val bottomZoneTop = cleanBitmap!!.height * 0.80

            var scannedPotSize: Float? = null
            
            val fullScanText = result.text.uppercase()
            val potMatch = Regex("(POT|ПОТ)[^0-9]*([0-9.,]+)").find(fullScanText)
            if (potMatch != null) {
                val numStr = potMatch.groupValues[2].replace(",", "")
                if (numStr.count { it == '.' } <= 1) {
                    scannedPotSize = numStr.toFloatOrNull()
                }
            }

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        
                        var insideHud = false
                        for (hudRect in hudRects) {
                            if (android.graphics.Rect.intersects(hudRect, box)) {
                                insideHud = true
                                break
                            }
                        }
                        if (insideHud) continue

                        val cx = box.centerX()
                        val cy = box.centerY()
                        
                        val expandedCommRect = android.graphics.Rect(
                            commRect.left - commRect.width() / 20,
                            commRect.top - commRect.height() / 10,
                            commRect.right + commRect.width() / 20,
                            commRect.bottom + commRect.height() / 10
                        )
                        
                        val expandedHoleRect = android.graphics.Rect(
                            holeRect.left - holeRect.width() / 20,
                            holeRect.top - holeRect.height() / 10,
                            holeRect.right + holeRect.width() / 20,
                            holeRect.bottom + holeRect.height() / 10
                        )
                        
                        if (commRect.width() > 20 && expandedCommRect.contains(cx, cy)) {
                            commElements.add(element)
                        } else if (holeRect.width() > 20 && expandedHoleRect.contains(cx, cy)) {
                            holeElements.add(element)
                        }
                        
                        if (box.top > bottomZoneTop) {
                            val txt = element.text.uppercase()
                            
                            // Prevent tiny non-button text from being recognized:
                            // Even short action buttons like "BET" or "Fold" are larger than 5% of screen width.
                            if (box.width() < cleanBitmap!!.width * 0.05f) continue
                            
                            if (txt.contains("FOLD") || txt.contains("ФОЛД") ||
                                txt.contains("CHECK") || txt.contains("ЧЕК") ||
                                txt.contains("CALL") || txt.contains("КОЛЛ") ||
                                txt.contains("BET") || txt.contains("БЕТ") ||
                                txt.contains("RAISE") || txt.contains("РЕЙЗ") ||
                                txt.contains("ALL-IN") || txt.contains("ОЛЛ-ИН") ||
                                txt.contains("ALL") || txt.contains("ОЛЛ") || 
                                txt.contains("STRADDLE") || txt.contains("СТРАДДЛ")) {
                                actionButtonsMap[txt] = box
                            }
                        }
                    }
                }
            }
            
            // Pass action buttons to RobotPlayer
            RobotPlayer.availableActionButtons = actionButtonsMap

            commElements.sortBy { it.boundingBox?.left ?: 0 }
            holeElements.sortBy { it.boundingBox?.left ?: 0 }

            for (element in commElements) {
                val box = element.boundingBox ?: continue
                // Card ranks might be merged, but still shouldn't be extremely wide like a full sentence.
                if (box.width() > box.height() * 4.0f) continue
                
                // Minimum size threshold to filter out small text like pot size
                if (box.height() < commRect.height() * 0.08f) continue
                
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
                    
                val safeText = rawText.trim()
                if (safeText.contains("OK") || safeText.contains("WAIT") || 
                    safeText.contains("OUTS") || safeText.contains("STRAIGHT") ||
                    safeText.contains("PAIR") || safeText.contains("FLUSH") || safeText.contains("HIGH") ||
                    safeText.contains("KIND") || safeText.contains("HOUSE") ||
                    safeText.contains("POT") || safeText.contains("BB") ||
                    safeText.contains("SHOW") || safeText.contains("MUCK") || safeText.contains("AUTO") ||
                    safeText.contains("OF") || safeText.isEmpty()) continue

                // Removed isCardBackground to avoid missing shiny/shadowed cards
                val parsedRanks = findCardsInText(safeText)
                for ((idx, rank) in parsedRanks.withIndex()) {
                    val sliceWidth = box.width() / parsedRanks.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                    tempCommCards.add(Pair(Card(rank, suit), sliceBox))
                }
            }
            
            for (element in holeElements) {
                val box = element.boundingBox ?: continue
                if (box.width() > box.height() * 4.0f) continue
                
                // Minimum size threshold to filter out tiny text. Hole cards usually large.
                if (box.height() < holeRect.height() * 0.05f) continue
                
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
                    
                val safeText = rawText.trim()
                if (safeText.contains("OK") || safeText.contains("WAIT") || 
                    safeText.contains("OUTS") || safeText.contains("STRAIGHT") ||
                    safeText.contains("PAIR") || safeText.contains("FLUSH") || safeText.contains("HIGH") ||
                    safeText.contains("KIND") || safeText.contains("HOUSE") ||
                    safeText.contains("POT") || safeText.contains("BB") ||
                    safeText.contains("SHOW") || safeText.contains("MUCK") || safeText.contains("AUTO") ||
                    safeText.contains("OF") || safeText.isEmpty()) continue

                // We skip isCardBackground for hole cards because they can be covered by player graphics or shadows.
                val parsedRanks = findCardsInText(safeText)
                for ((idx, rank) in parsedRanks.withIndex()) {
                    // Slicing the bounding box if multiple ranks are merged in a single text block
                    val sliceWidth = box.width() / parsedRanks.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                    tempHoleCards.add(Pair(Card(rank, suit), sliceBox))
                }
            }
            
            fun clusterCards(cards: List<Pair<Card, android.graphics.Rect>>, maxCards: Int, regionRect: android.graphics.Rect): MutableList<Card?> {
                val resultList = MutableList<Card?>(maxCards) { null }
                if (cards.isEmpty()) return resultList
                
                // Group detections by their X center
                val clusterThreshold = regionRect.width() / 12f
                val sorted = cards.sortedBy { it.second.centerX() }
                val clusters = mutableListOf<MutableList<Pair<Card, android.graphics.Rect>>>()
                
                for (elem in sorted) {
                    if (clusters.isEmpty()) {
                        clusters.add(mutableListOf(elem))
                    } else {
                        val lastCluster = clusters.last()
                        val lastCx = lastCluster.map { it.second.centerX() }.average()
                        if (elem.second.centerX() - lastCx < clusterThreshold) {
                            lastCluster.add(elem)
                        } else {
                            clusters.add(mutableListOf(elem))
                        }
                    }
                }
                
                val finalCards = clusters.map { cluster ->
                    val ranks = cluster.map { it.first.rank }
                    val finalRank = ranks.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    
                    val suits = cluster.map { it.first.suit }
                    val finalSuit = suits.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    
                    Card(finalRank, finalSuit)
                }
                
                for (i in 0 until minOf(maxCards, finalCards.size)) {
                    resultList[i] = finalCards[i]
                }
                
                return resultList
            }

            var foundCommCardsRaw = clusterCards(tempCommCards, 5, commRect)
            var foundHoleCardsRaw = clusterCards(tempHoleCards, 2, holeRect)

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

            var smoothedHole = getSmoothedCards(holeHistory, foundHoleCardsRaw, confirmedHole, windowSize = 5)
            var smoothedComm = getSmoothedCards(commHistory, foundCommCardsRaw, confirmedComm, windowSize = 5)
            
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

            // Board never shrinks during a hand. If we saw N cards, and now see fewer (but >0), keep N cards if possible.
            // If it drops to 0, getSmoothedCards will eventually clear it (after 2 empty frames).
            val currentCommCount = currentState.board.count { it != null }
            val smoothedCommCount = smoothedComm.count { it != null }
            
            val finalH1 = smoothedHole.getOrNull(0) ?: smoothedHole.firstOrNull() ?: null
            val finalH2 = smoothedHole.getOrNull(1) ?: smoothedHole.drop(1).firstOrNull() ?: null
            
            val finalBoard = List(5) { i ->
                smoothedComm.getOrNull(i) ?: smoothedComm.getOrNull(i)
            }
            
            val scannedOpponents = OpponentScanner.scan(result, cleanBitmap!!, hudRects, commRect, holeRect)
            val finalOpponents: List<OpponentState>
            if (scannedOpponents.isNotEmpty()) {
                emptyOpponentsFrames = 0
                finalOpponents = scannedOpponents
            } else {
                emptyOpponentsFrames++
                finalOpponents = if (emptyOpponentsFrames < 3) currentState.opponents else emptyList()
            }

            var profileBoxesToHighlight: List<ScannedBox>? = null
            if (requestProfileScan) {
                val scannedProfile = ProfileScanner.scan(result, cleanBitmap!!, hudRects)
                if (scannedProfile != null && scannedProfile.nickname != "Unknown_Profile") {
                    profileBoxesToHighlight = scannedProfile.profileBoundingBoxes
                    val prefsManager = PreferencesManager(pokerHudService)
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
                result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        line.boundingBox?.let { rect -> ScannedBox(rect, line.text) }
                    }
                }
            } else {
                null
            }

            PokerHudSharedState.externalActions.tryEmit(
                ExternalAction.UpdateCards(
                    hero1 = finalH1, 
                    hero2 = finalH2, 
                    board = finalBoard, 
                    opponents = finalOpponents, 
                    profileBoxes = profileBoxesToHighlight, 
                    updateProfileBoxes = (profileBoxesToHighlight != null), 
                    rawScannerBoxes = rawBoxes,
                    potSize = scannedPotSize
                )
            )
            
            scanStatus.value = "H:${smoothedHole.filterNotNull().size}(${foundHoleCardsRaw.filterNotNull().size}) C:${smoothedComm.filterNotNull().size}(${foundCommCardsRaw.filterNotNull().size}) Ops:${finalOpponents.size}<br>" +
                               "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
            
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
        }
        return true
    }

    // isCardBackground removed because it was aggressively dropping cards with shiny or dark backgrounds

    private fun findCardsInText(text: String): List<Rank> {
        val found = mutableListOf<Rank>()
        // Pre-replace common OCR symbol mistakes before stripping
        var preProcessed = text.uppercase(java.util.Locale.US)
        preProcessed = preProcessed.replace("&", "8").replace("$", "8").replace("@", "Q").replace("%", "8").replace("?", "7").replace("!", "1")
        // Replace Cyrillic lookalikes
        preProcessed = preProcessed.replace("А", "A").replace("К", "K").replace("Т", "T").replace("В", "B").replace("О", "O").replace("С", "C").replace("Р", "P")

        // Keep only letters, digits, and suit symbols to form a dense string
        val raw = preProcessed.replace(Regex("[^A-Z0-9\u2660\u2663\u2665\u2666]"), "")
            
        if (raw.length > 8) return emptyList() // Too long to be just 1-2 cards
        if (raw.isEmpty()) return emptyList()
        
        // Block obvious chip stacks or bets
        val noSymbols = text.uppercase(java.util.Locale.US).replace(Regex("[^A-Z0-9 ]"), "")
        if (noSymbols.matches(Regex(".*\\d{3,}.*"))) return emptyList() // 100, 500 etc.
        // Ignore obvious standalone numbers/stacks
        val digits = raw.count { it.isDigit() }
        val hasTen = Regex("10|I0|1O|IO|L0|LO|IQ|1Q|T0|TO").containsMatchIn(raw)
        
        if (digits > 4) return emptyList()
        if (digits > 2 && !hasTen) return emptyList() // E.g., "125", "500", "250" 

        var i = 0
        while (i < raw.length) {
            var matched = false
            
            // Check for 10 first
            if (i + 1 < raw.length) {
                val sub = raw.substring(i, i + 2)
                if (sub == "10" || sub == "I0" || sub == "1O" || sub == "IQ" || sub == "1Q" || sub == "L0" || sub == "LO" || sub == "T0" || sub == "TO") {
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
                    'J' -> Rank.JACK
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
                // If it's not a rank, it might be a suit symbol or harmless noise
                val c = raw[i]
                val validSuitsOrNoise = setOf('♠', '♣', '♥', '♦', 'C', 'H')
                if (c in validSuitsOrNoise) {
                    i++
                } else {
                    // Invalid character encountered in this block.
                    // To prevent random letters in words from being parsed as cards,
                    // we invalidate the ENTIRE block.
                    return emptyList()
                }
            }
        }
        
        return found
    }



    private fun robustDetectSuit(crop: Bitmap, rankBox: android.graphics.Rect): Suit? {
        val w = crop.width
        val h = crop.height
        
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        
        // Scan just around the rank and look down below it where the suit symbol is located
        val adjustX = (rankBox.width() * 0.1).toInt()
        val left = maxOf(0, rankBox.left - adjustX)
        val right = minOf(w - 1, rankBox.right + adjustX)
        val top = maxOf(0, rankBox.top + (rankBox.height() * 0.35).toInt())
        val bottom = minOf(h - 1, rankBox.bottom + (rankBox.height() * 1.5).toInt())
        
        for (px in left..right step 2) {
            for (py in top..bottom step 2) {
                val p = crop.getPixel(px, py)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Ignore text/icon bright pixels (white background)
                if (r > 180 && g > 180 && b > 180) continue
                // Ignore pitch black background shadows
                if (r < 15 && g < 15 && b < 15) continue
                
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0 else (max - min) * 255 / max
                
                val isPurple = (r > g + 30 && b > g + 30 && r > 40 && b > 40)
                val isOrange = (r > g + 20 && g > b + 20 && r > 80 && r - b > 50)
                
                if (saturation > 45 && max > 35 && !isPurple && !isOrange) {
                    if (r == max && r - g > 20 && r - b > 20) {
                        // Additional check to ensure Orange isn't counted as Red Hearts
                        if (g < max * 0.75f) rC++
                    }
                    else if (g == max && g - r > 20 && g - b > 20) gC++
                    else if (b == max && b - r > 20 && b - g > 20) bC++
                } else if (max < 100 && saturation < 45 && !isPurple && !isOrange) {
                    blkC++
                }
            }
        }
        
        val totalChroma = rC + gC + bC
        if (totalChroma >= 5) {
            if (rC > gC && rC > bC) return Suit.HEARTS
            if (gC > rC && gC > bC) return Suit.CLUBS
            if (bC > rC && bC > gC) return Suit.DIAMONDS
        }
        
        if (totalChroma + blkC < 2) return null
        
        return Suit.SPADES
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
