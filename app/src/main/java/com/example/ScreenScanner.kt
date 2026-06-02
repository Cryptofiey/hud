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
                // Card ranks represent a single character (or '10'). They shouldn't be very wide.
                if (box.width() > box.height() * 2.5f) continue
                if (element.text.trim().length > 3) continue
                
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
                if (box.width() > box.height() * 2.5f) continue
                if (element.text.trim().length > 3) continue
                
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
            
            val foundCommCardsRaw = tempCommCards.distinctBy { it.first }.sortedBy { it.second }.map { it.first }
            val foundHoleCardsRaw = tempHoleCards.distinctBy { it.first }.sortedBy { it.second }.map { it.first }
            
            var smoothedHole = getSmoothedCards(holeHistory, foundHoleCardsRaw, windowSize = 3)
            var smoothedComm = getSmoothedCards(commHistory, foundCommCardsRaw, windowSize = 3)

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
        val t = text.uppercase(java.util.Locale.US)
        
        // Split the text into potential card tokens (e.g. "K", "10", "A")
        // We look for all possible rank occurrences in the string
        val parts = t.split(Regex("[^A-Z0-9]")).filter { it.isNotEmpty() }
        for (part in parts) {
            val rank = matchRank(part)
            if (rank != null) found.add(rank)
        }
        
        // If we found nothing by split, try the whole text (it might have symbols)
        if (found.isEmpty()) {
            val rank = matchRank(t)
            if (rank != null) found.add(rank)
        }
        
        return found
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


    private fun matchRank(text: String): Rank? {
        val t = text.uppercase(java.util.Locale.US).replace(" ", "").replace("|", "I").replace("(", "").replace(")", "").replace(":", "").trim()
        
        if (t.isEmpty()) return null

        if (t.length > 3) return null

        if (t.startsWith("10") || t.startsWith("IO") || t.startsWith("IQ") || t.startsWith("1O") || t.startsWith("1Q") || t.startsWith("I0") || t.startsWith("L0") || t.startsWith("LO")) {
            return Rank.TEN
        }
        
        if (t.length == 2) {
            val second = t[1]
            val allowedSeconds = listOf('S', 'C', 'H', 'D', 'O', '0', 'X', '♠', '♣', '♥', '♦', 'G', 'Q')
            if (second.isLetterOrDigit() && second !in allowedSeconds) {
                return null
            }
        }
        
        val firstChar = t.firstOrNull() ?: return null
        
        // Exclude clear numbers > 10 that were read as ranks (e.g. 50, 40 etc.)
        if (t.length >= 2 && firstChar.isDigit() && t[1].isDigit()) {
            return null // Like "50", "25", "42" - these are not single ranks
        }

        return when (firstChar) {
            'A' -> Rank.ACE
            'K', 'X' -> Rank.KING
            'Q', '0', 'O', 'C', 'D' -> Rank.QUEEN
            'J', 'I', 'L', '1' -> Rank.JACK
            'T' -> Rank.TEN
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
