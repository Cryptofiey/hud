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

    private var consecutiveEmptyHole = 0
    private var consecutiveEmptyComm = 0

    private var cachedTotalBitmap: Bitmap? = null
    private var cachedCleanBitmap: Bitmap? = null

    private suspend fun processLatestImage() {
        var image: Image? = null
        try {
            // Empty the ImageReader queue to get a NEW frame
            while (true) {
                val img = try { imageReader?.acquireLatestImage() } catch(e: Exception) { null } ?: break
                img.close()
            }
            
            // Allow a small window for the next frame to be captured
            delay(60)
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

            if (cachedTotalBitmap == null || cachedTotalBitmap!!.width != totalWidth || cachedTotalBitmap!!.height != height) {
                cachedTotalBitmap?.recycle()
                cachedTotalBitmap = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888)
            }
            
            buffer.position(0)
            cachedTotalBitmap!!.copyPixelsFromBuffer(buffer)
            image.close()
            image = null

            if (cachedCleanBitmap == null || cachedCleanBitmap!!.width != width || cachedCleanBitmap!!.height != height) {
                cachedCleanBitmap?.recycle()
                cachedCleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            
            // Draw cropped area into clean bitmap
            val canvas = android.graphics.Canvas(cachedCleanBitmap!!)
            val srcRect = android.graphics.Rect(0, 0, width, height)
            val destRect = android.graphics.Rect(0, 0, width, height)
            canvas.drawBitmap(cachedTotalBitmap!!, srcRect, destRect, null)

            val cleanBitmap = cachedCleanBitmap!!

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
            val tempCommCards = mutableListOf<Card>()
            val tempHoleCards = mutableListOf<Card>()
            
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

                if (!isCardBackground(cleanBitmap!!, box)) continue

                val parsedRanks = findCardsInText(element.text)
                for (rank in parsedRanks) {
                    val suit = robustDetectSuit(cleanBitmap, box.centerX(), box.centerY())
                    tempCommCards.add(Card(rank, suit))
                }
            }
            
            for (element in holeElements) {
                val box = element.boundingBox ?: continue
                if (box.width() > box.height() * 2.5f) continue
                if (element.text.trim().length > 3) continue
                
                // Minimum size threshold to filter out tiny text
                if (box.height() < holeRect.height() * 0.08f) continue
                
                val lowerCount = element.text.count { it.isLowerCase() }
                if (lowerCount >= 2) continue
                
                val rawText = element.text.trim().uppercase(java.util.Locale.US)
                if (rawText.contains("OK") || rawText.contains("WAIT") || rawText.contains("COIN")) continue

                if (!isCardBackground(cleanBitmap!!, box)) continue

                val parsedRanks = findCardsInText(element.text)
                for (rank in parsedRanks) {
                    val suit = robustDetectSuit(cleanBitmap, box.centerX(), box.centerY())
                    tempHoleCards.add(Card(rank, suit))
                }
            }
            
            val foundCommCards = tempCommCards.distinct()
            val foundHoleCards = tempHoleCards.distinct()

            if (foundHoleCards.size < 2) consecutiveEmptyHole++ else consecutiveEmptyHole = 0
            if (foundCommCards.isEmpty()) consecutiveEmptyComm++ else consecutiveEmptyComm = 0
            
            val useOldHole = consecutiveEmptyHole < 3
            val useOldComm = consecutiveEmptyComm < 3

            val finalH1 = foundHoleCards.getOrNull(0) ?: if (useOldHole) currentState.heroCard1 else null
            // Only fallback for H2 if H1 was also successfully recovered or found. If we only found 1 card today and our fallback is expired, don't use old H2.
            val finalH2 = foundHoleCards.getOrNull(1) ?: if (useOldHole) currentState.heroCard2 else null
            
            val finalBoard = List(5) { i ->
                foundCommCards.getOrNull(i) ?: if (useOldComm) currentState.board.getOrNull(i) else null
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
            
            scanStatus.value = "H:${foundHoleCards.size} C:${foundCommCards.size} Ops:${finalOpponents.size}<br>" +
                               "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
            
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
        }
    }

    private fun isCardBackground(bitmap: Bitmap, box: android.graphics.Rect): Boolean {
        // Simple sign of a card: the area just above or around the rank has a relatively solid, uniform color (the card face background).
        // The table felt, on the other hand, is usually heavily textured (high variance) or very dark.
        // We'll sample a small horizontal line just above the bounding box (where the card background should be plain).
        val w = bitmap.width
        val h = bitmap.height
        
        val y = Math.min(h - 1, Math.max(0, box.top - 5))
        val startX = Math.min(w - 1, Math.max(0, box.left))
        val endX = Math.min(w - 1, Math.max(0, box.right))
        
        if (startX >= endX) return true
        
        var sumR = 0; var sumG = 0; var sumB = 0
        var count = 0
        val pixels = mutableListOf<Int>()
        
        for (x in startX..endX) {
            val p = bitmap.getPixel(x, y)
            pixels.add(p)
            sumR += android.graphics.Color.red(p)
            sumG += android.graphics.Color.green(p)
            sumB += android.graphics.Color.blue(p)
            count++
        }
        
        if (count == 0) return true
        
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        
        // Calculate variance
        var varR = 0f; var varG = 0f; var varB = 0f
        for (p in pixels) {
            val r = android.graphics.Color.red(p)
            val g = android.graphics.Color.green(p)
            val b = android.graphics.Color.blue(p)
            varR += (r - avgR) * (r - avgR)
            varG += (g - avgG) * (g - avgG)
            varB += (b - avgB) * (b - avgB)
        }
        
        val totalStdDev = Math.sqrt(((varR + varG + varB) / count).toDouble())
        
        // If the color is very bright (white/light grey), it's almost certainly a card face.
        if (avgR > 180 && avgG > 180 && avgB > 180) return true
        
        // Too dark to be a standard card face (even dark Spades are usually > 25)
        if (avgR < 25 && avgG < 25 && avgB < 25) return false
        
        // If it's very textured (StdDev > 35), it's likely felt or background noise, not a flat card face.
        if (totalStdDev > 35.0) return false
        
        return true
    }

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

    private fun robustDetectSuit(crop: Bitmap, x: Int, y: Int): Suit {
        val w = crop.width
        val h = crop.height
        
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        
        val left = maxOf(0, x - 50)
        val right = minOf(w - 1, x + 50)
        val top = maxOf(0, y - 50)
        val bottom = minOf(h - 1, y + 100)
        
        for (px in left..right step 4) {
            for (py in top..bottom step 4) {
                val p = crop.getPixel(px, py)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Ignore text/icon bright pixels
                if (r > 160 && g > 160 && b > 160) continue
                // Ignore extremely dark edge pixels
                if (r < 25 && g < 25 && b < 25) continue
                
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = max - min
                
                if (saturation < 20) {
                    blkC++ // grayscale/spades
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
                cachedTotalBitmap?.recycle()
                cachedTotalBitmap = null
                cachedCleanBitmap?.recycle()
                cachedCleanBitmap = null
            } catch (e: Exception) {
                android.util.Log.e("ScreenScanner", "Error stopping scanner", e)
            }
        }
    }
}
