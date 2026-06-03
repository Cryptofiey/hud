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
                    processLatestImage()
                    delay(500) // Lower delay for faster reaction (was 1100)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Error starting scanner", e)
            scanStatus.value = "Scanner start failed: ${e.message}"
            isScanning.value = false
        }
    }

    private val holeHistory = mutableListOf<List<Card>>()
    private val commHistory = mutableListOf<List<Card>>()

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

    private fun getSmoothedCards(history: MutableList<List<Card>>, newCards: List<Card>, windowSize: Int = 4): List<Card> {
        history.add(newCards)
        if (history.size > windowSize) {
            history.removeAt(0)
        }
        
        // If last 2 frames were completely empty, reset history
        if (history.size >= 2 && history.takeLast(2).all { it.isEmpty() }) {
            history.clear()
            return emptyList()
        }
        
        val maxLen = history.maxOfOrNull { it.size } ?: 0
        val result = mutableListOf<Card>()
        
        for (i in 0 until maxLen) {
            val cardsAtI = history.mapNotNull { it.getOrNull(i) }
            if (cardsAtI.isEmpty()) continue
            val modeCount = cardsAtI.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (modeCount != null) {
                result.add(modeCount.key)
            }
        }
        return result.distinct()
    }

    private var cachedCleanBitmap: Bitmap? = null

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
            val tempCommCards = mutableListOf<Pair<Card, Int>>()
            val tempHoleCards = mutableListOf<Pair<Card, Int>>()
            
            val commElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()
            val holeElements = mutableListOf<com.google.mlkit.vision.text.Text.Element>()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        if (commRect.width() > 20 && android.graphics.Rect.intersects(commRect, box)) {
                            commElements.add(element)
                        } else if (holeRect.width() > 20 && android.graphics.Rect.intersects(holeRect, box)) {
                            holeElements.add(element)
                        }
                    }
                }
            }
            
            commElements.sortBy { it.boundingBox?.left ?: 0 }
            holeElements.sortBy { it.boundingBox?.left ?: 0 }

            for (element in commElements) {
                val box = element.boundingBox ?: continue
                // Card ranks might be merged, but still shouldn't be extremely wide like a full sentence.
                if (box.width() > box.height() * 4.0f) continue
                
                // Minimum size threshold to filter out small text like pot size
                if (box.height() < commRect.height() * 0.08f) continue
                
                // If it contains mostly lowercase, it's a word.
                val lowerCount = element.text.count { it.isLowerCase() }
                if (lowerCount >= 2) continue
                
                val rawText = element.text.trim().uppercase(java.util.Locale.US)
                if (rawText.contains("OK") || rawText.contains("WAIT") || rawText.contains("COIN")) continue

                // Removed isCardBackground to avoid missing shiny/shadowed cards
                val parsedRanks = findCardsInText(element.text)
                for (rank in parsedRanks) {
                    val suit = robustDetectSuit(cleanBitmap, box)
                    val xOffset = box.left + (parsedRanks.indexOf(rank) * 10)
                    tempCommCards.add(Pair(Card(rank, suit), xOffset))
                }
            }
            
            for (element in holeElements) {
                val box = element.boundingBox ?: continue
                if (box.width() > box.height() * 4.0f) continue
                
                // Minimum size threshold to filter out tiny text. Hole cards usually large.
                if (box.height() < holeRect.height() * 0.05f) continue
                
                val lowerCount = element.text.count { it.isLowerCase() }
                if (lowerCount >= 2) continue
                
                val rawText = element.text.trim().uppercase(java.util.Locale.US)
                if (rawText.contains("OK") || rawText.contains("WAIT") || rawText.contains("COIN")) continue

                // We skip isCardBackground for hole cards because they can be covered by player graphics or shadows.

                val parsedRanks = findCardsInText(element.text)
                for (rank in parsedRanks) {
                    val suit = robustDetectSuit(cleanBitmap, box)
                    // If multiple ranks are in same text block, offset the X coordinate slightly to preserve their read order
                    val xOffset = box.left + (parsedRanks.indexOf(rank) * 10) 
                    tempHoleCards.add(Pair(Card(rank, suit), xOffset))
                }
            }
            
            val commSlots = findCardSlots(cleanBitmap, commRect, 5)
            
            val physicalCommCount = commSlots.size
            if (physicalCommCount == 1 || physicalCommCount == 2 || physicalCommCount > 5) {
                scanStatus.value = "Warning: Invalid number of community slots: $physicalCommCount. Trusting OCR."
            }

            var foundCommCardsRaw = mutableListOf<Card>()
            var foundHoleCardsRaw = mutableListOf<Card>()

            if (physicalCommCount in 3..5) {
                // Assign each OCR element to the slot whose center is closest to the element
                val elementsBySlot = tempCommCards.groupBy { element ->
                    commSlots.minByOrNull { slot ->
                        Math.abs(element.second + 15 - (slot.left + slot.right) / 2)
                    }
                }
                for (slot in commSlots) {
                    val elementsInSlot = elementsBySlot[slot] ?: emptyList()
                    if (elementsInSlot.isNotEmpty()) {
                        val ranks = elementsInSlot.map { it.first.rank }
                        val duplicateRank = ranks.groupBy { it }.maxByOrNull { it.value.size }?.let { if (it.value.size >= 2) it.key else null }
                        val finalRank = duplicateRank ?: ranks.groupBy { it }.maxByOrNull { it.value.size }!!.key
                        val suit = detectSuitFromSlotBackground(cleanBitmap!!, slot)
                        foundCommCardsRaw.add(Card(finalRank, suit))
                    }
                }
            } else {
                foundCommCardsRaw = tempCommCards.distinctBy { it.first }.sortedBy { it.second }.map { it.first }.toMutableList()
            }

            // Hole cards are fixed to exactly 2 cards max.
            // Since the first card is partially covered, we split them by their relative X position in the bounding box.
            if (tempHoleCards.isNotEmpty()) {
                val card1Elements = mutableListOf<Pair<Card, Int>>()
                val card2Elements = mutableListOf<Pair<Card, Int>>()
                
                for (item in tempHoleCards) {
                    val relativeX = (item.second - holeRect.left).toFloat() / holeRect.width()
                    // Card 1's rank is typically at 0% - 15%. Card 2 is fully visible, its left rank is around 25% - 40%.
                    if (relativeX < 0.26f) {
                        card1Elements.add(item)
                    } else {
                        card2Elements.add(item)
                    }
                }
                
                if (card1Elements.isNotEmpty()) {
                    val ranks = card1Elements.map { it.first.rank }
                    val finalRank = ranks.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    // Extract synthetic slot for robust suit detection
                    val minX = card1Elements.minOf { it.second }
                    val slotW = (holeRect.width() * 0.25f).toInt()
                    val synSlot = android.graphics.Rect(minX, holeRect.top, minX + slotW, holeRect.bottom)
                    val finalSuit = detectSuitFromSlotBackground(cleanBitmap!!, synSlot)
                    foundHoleCardsRaw.add(Card(finalRank, finalSuit))
                }
                
                if (card2Elements.isNotEmpty()) {
                    val ranks = card2Elements.map { it.first.rank }
                    // Look for duplicate ranks to confirm
                    val duplicateRank = ranks.groupBy { it }.maxByOrNull { it.value.size }?.let { if (it.value.size >= 2) it.key else null }
                    val finalRank = duplicateRank ?: ranks.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    val minX = card2Elements.minOf { it.second }
                    val slotW = (holeRect.width() * 0.35f).toInt()
                    val synSlot = android.graphics.Rect(minX, holeRect.top, minX + slotW, holeRect.bottom)
                    val finalSuit = detectSuitFromSlotBackground(cleanBitmap!!, synSlot)
                    foundHoleCardsRaw.add(Card(finalRank, finalSuit))
                }
            }

            // If physical slot logic was valid, use it to deduplicate or constraint OCR
            if (physicalCommCount in 3..5 && physicalCommCount > foundCommCardsRaw.size) {
                 scanStatus.value = "Info: Physical slots ($physicalCommCount) > OCR (${foundCommCardsRaw.size}). May be missing cards."
            }
            if (physicalCommCount == 0 && foundCommCardsRaw.size <= 2) {
                 // Empty table, clear ghost texts
                 foundCommCardsRaw.clear()
            }

            var rawAll = foundHoleCardsRaw + foundCommCardsRaw
            if (rawAll.size != rawAll.toSet().size) {
                scanStatus.value = "Warning: Duplicate cards detected. Ignoring duplicates in this frame."
                foundCommCardsRaw = foundCommCardsRaw.distinct().toMutableList()
                foundHoleCardsRaw = foundHoleCardsRaw.filter { it !in foundCommCardsRaw }.distinct().toMutableList()
            }

            var smoothedHole = getSmoothedCards(holeHistory, foundHoleCardsRaw, windowSize = 3)
            var smoothedComm = getSmoothedCards(commHistory, foundCommCardsRaw, windowSize = 3)
            
            val smoothedAll = smoothedHole + smoothedComm
            if (smoothedAll.size != smoothedAll.toSet().size) {
                // Invalid smoothed detection
                // Clear history to recover
                holeHistory.clear()
                commHistory.clear()
                scanStatus.value = "Error: Invalid duplicate cards in smoothed state. Clearing history."
                return
            }

            // Board never shrinks during a hand. If we saw N cards, and now see fewer (but >0), keep N cards if possible.
            // If it drops to 0, getSmoothedCards will eventually clear it (after 2 empty frames).
            val currentComm = currentState.board.filterNotNull()
            if (smoothedComm.isNotEmpty() && smoothedComm.size < currentComm.size) {
                // Temporary drop in detected cards. Use previous known board if it's larger length.
                smoothedComm = currentComm
                // Overwrite the last history entry to keep it alive
                if (commHistory.isNotEmpty()) commHistory[commHistory.lastIndex] = currentComm
            }
            
            val finalH1 = smoothedHole.getOrNull(0)
            val finalH2 = smoothedHole.getOrNull(1)
            
            val finalBoard = List(5) { i ->
                smoothedComm.getOrNull(i)
            }
            
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
            
            scanStatus.value = "H:${smoothedHole.size}(${foundHoleCardsRaw.size}) C:${smoothedComm.size}(${foundCommCardsRaw.size}) Ops:${finalOpponents.size}<br>" +
                               "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
            
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
        }
    }

    // isCardBackground removed because it was aggressively dropping cards with shiny or dark backgrounds

    private fun findCardsInText(text: String): List<Rank> {
        val found = mutableListOf<Rank>()
        // Keep only letters, digits, and suit symbols to form a dense string
        val raw = text.uppercase(java.util.Locale.US)
            .replace(Regex("[^A-Z0-9\u2660\u2663\u2665\u2666]"), "")
            
        if (raw.length > 6) return emptyList() // Too long to be just 1-2 cards
        if (raw.isEmpty()) return emptyList()
        
        // Ignore obvious standalone numbers/stacks unless they are a single 10, or two 10s
        val digits = raw.count { it.isDigit() }
        if (digits >= 2) {
            val hasTen = Regex("10|I0|1O|IO").containsMatchIn(raw)
            if (!hasTen) return emptyList() // E.g., "50", "25", "42"
        }

        var i = 0
        while (i < raw.length) {
            var matched = false
            
            // Check for 10 first
            if (i + 1 < raw.length) {
                val sub = raw.substring(i, i + 2)
                if (sub == "10" || sub == "I0" || sub == "1O" || sub == "IO" || sub == "IQ" || sub == "1Q" || sub == "L0" || sub == "LO") {
                    found.add(Rank.TEN)
                    i += 2
                    matched = true
                }
            }
            
            if (!matched) {
                val c = raw[i]
                val r = when (c) {
                    'A' -> Rank.ACE
                    'K', 'X' -> Rank.KING
                    'Q', 'D', '0', 'O' -> Rank.QUEEN
                    'J', 'L', '1', 'I' -> Rank.JACK
                    '9', 'G' -> Rank.NINE
                    '8', 'B' -> Rank.EIGHT
                    '7' -> Rank.SEVEN
                    '6' -> Rank.SIX
                    '5', 'S' -> Rank.FIVE
                    '4' -> Rank.FOUR
                    '3' -> Rank.THREE
                    '2', 'Z' -> Rank.TWO
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

    private fun detectSuitFromSlotBackground(crop: Bitmap, slot: android.graphics.Rect): Suit {
        val w = crop.width
        val h = crop.height
        
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        
        // Sample points across the whole slot horizontally, but restrict vertically to middle
        // to avoid top/bottom shadows or non-card background if the slot is slightly large.
        val left = maxOf(0, slot.left + (slot.width() * 0.1).toInt())
        val right = minOf(w - 1, slot.right - (slot.width() * 0.1).toInt())
        val top = maxOf(0, slot.top + (slot.height() * 0.2).toInt())
        val bottom = minOf(h - 1, slot.bottom - (slot.height() * 0.2).toInt())
        
        if (left >= right || top >= bottom) return Suit.SPADES
        
        for (px in left..right step 3) {
            for (py in top..bottom step 3) {
                val p = crop.getPixel(px, py)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Ignore text/icon bright pixels (white text on face)
                if (r > 160 && g > 160 && b > 160) continue
                // Ignore pitch black background shadows
                if (r < 25 && g < 25 && b < 25) continue
                
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0 else (max - min) * 255 / max
                
                // For solid color cards:
                // Red = Hearts, Blue = Diamonds, Green = Clubs, Dark grey/Black = Spades
                if (saturation < 30) {
                    blkC++
                } else if (r == max && r - g > 30 && r - b > 30) {
                    rC++ // Red -> Hearts
                } else if (g == max && g - r > 20 && g - b > 20) {
                    gC++ // Green -> Clubs
                } else if (b == max && b - r > 20 && b - g > 20) {
                    bC++ // Blue -> Diamonds
                } else {
                    blkC++ // Fallback
                }
            }
        }
        
        return when {
            rC > gC && rC > bC && rC > blkC -> Suit.HEARTS
            bC > rC && bC > gC && bC > blkC -> Suit.DIAMONDS
            gC > rC && gC > bC && gC > blkC -> Suit.CLUBS
            else -> Suit.SPADES
        }
    }

    private fun robustDetectSuit(crop: Bitmap, rankBox: android.graphics.Rect): Suit {
        val w = crop.width
        val h = crop.height
        
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        
        // Scan around and mostly below the rank's bounding box
        val left = maxOf(0, rankBox.left - rankBox.width())
        val right = minOf(w - 1, rankBox.right + rankBox.width())
        val top = maxOf(0, rankBox.top - rankBox.height() / 2)
        val bottom = minOf(h - 1, rankBox.bottom + rankBox.height() * 2)
        
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
                val saturation = max - min
                
                if (saturation < 30) {
                    // Very low saturation = gray/black -> Spades (or Clubs in 2-color)
                    blkC++
                } else {
                    if (r == max) {
                        rC++
                    } else if (g == max) {
                        gC++
                    } else {
                        bC++
                    }
                }
            }
        }
        
        return when {
            rC > gC && rC > bC && rC > blkC -> Suit.HEARTS
            bC > rC && bC > gC && bC > blkC -> Suit.DIAMONDS
            gC > rC && gC > bC && gC > blkC -> Suit.CLUBS
            else -> Suit.SPADES
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
                cachedCleanBitmap?.recycle()
                cachedCleanBitmap = null
            } catch (e: Exception) {
                android.util.Log.e("ScreenScanner", "Error stopping scanner", e)
            }
        }
    }
}
