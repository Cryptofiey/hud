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
                    val result = recognizer.process(inputImage).await()
                
                val commRect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pokerHudService.getCommRect() }
                val holeRect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pokerHudService.getHoleRect() }
                val hudRects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pokerHudService.getHudRects() }
                
                val foundCommCardsRaw = mutableListOf<Pair<Card, Int>>()
                var foundHoleCardsRaw = mutableListOf<Pair<Card, Int>>()
                
                val cardPattern = Regex("^(10|T|[AKQJ]|[2-9])$")
                val debugLogs = mutableListOf<String>()
                
                
                // --- TARGETED COMM CARD SCAN ---
                if (commRect.width() > 0 && commRect.height() > 0) {
                    val safeComm = android.graphics.Rect(
                        maxOf(0, commRect.left - 10),
                        maxOf(0, commRect.top - 10),
                        minOf(cleanBitmap.width, commRect.right + 10),
                        minOf(cleanBitmap.height, commRect.bottom + 10)
                    )
                    if (safeComm.width() > 10 && safeComm.height() > 10) {
                        var croppedComm: Bitmap? = null
                        var scaledComm: Bitmap? = null
                        try {
                            croppedComm = Bitmap.createBitmap(cleanBitmap, safeComm.left, safeComm.top, safeComm.width(), safeComm.height())
                            val scale = 2
                            scaledComm = Bitmap.createScaledBitmap(croppedComm, croppedComm.width * scale, croppedComm.height * scale, true)
                            
                            val commInputImage = com.google.mlkit.vision.common.InputImage.fromBitmap(scaledComm, 0)
                            val commResult = recognizer.process(commInputImage).await()
                            
                            val rankPatternRegex = Regex("(10|1|T|[AKQJGBSYZE46]|[2-9])")
                            for (block in commResult.textBlocks) {
                                for (line in block.lines) {
                                    for (element in line.elements) {
                                        var rawText = element.text.uppercase(java.util.Locale.US).trim()
                                        rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")
                                        if (rawText == "O" || rawText == "D" || rawText == "0" || rawText == "Q") rawText = "Q"
                                        if (rawText == "Z" || rawText == "2") rawText = "2"
                                        if (rawText == "B" || rawText == "8") rawText = "8"
                                        if (rawText == "S" || rawText == "s" || rawText == "5") rawText = "5"
                                        if (rawText == "I" || rawText == "l" || rawText == "L" || rawText == "1") rawText = "1"
                                        if (rawText == "E" || rawText == "3") rawText = "3"
                                        if (rawText == "T" || rawText == "7" || rawText == "Y") rawText = "7"
                                        
                                        val match = rankPatternRegex.find(rawText)
                                        if (match != null) {
                                            val text = match.value
                                            val rank = parseRank(text) ?: continue
                                            val box = element.boundingBox ?: continue
                                            
                                            // Take only first matched rank from element to avoid trailing suit ghosts
                                            val matchRatio = if (rawText.isNotEmpty()) (match.range.first + match.range.last) / 2.0 / rawText.length else 0.5
                                            val localCenterX = box.left + (box.width() * matchRatio).toInt()
                                            
                                            val approxCenterBoxX = safeComm.left + (localCenterX / scale)
                                            
                                            var redCount = 0
                                            var greenCount = 0
                                            var blueCount = 0
                                            var blackCount = 0
                                            
                                            val startX = maxOf(0, approxCenterBoxX - (box.width() / scale))
                                            val endX = minOf(cleanBitmap.width - 1, approxCenterBoxX + (box.width() / scale / 4))
                                            val startY = maxOf(0, safeComm.top + (box.top / scale) - 5)
                                            val endY = minOf(cleanBitmap.height - 1, safeComm.top + (box.bottom / scale) + (box.height() / scale))
                                            
                                            if (startX <= endX && startY <= endY) {
                                                for (px in startX..endX step 2) {
                                                    for (py in startY..endY step 2) {
                                                        val pixel = cleanBitmap.getPixel(px, py)
                                                        val r = android.graphics.Color.red(pixel)
                                                        val g = android.graphics.Color.green(pixel)
                                                        val b = android.graphics.Color.blue(pixel)
                                                        
                                                        // Red Suits (Hearts)
                                                        if (r > 120 && r > g + 50 && r > b + 50) redCount++
                                                        // Green Suits (Clubs - CoinPoker 4-color)
                                                        else if (g > 100 && g > r + 40 && g > b + 30) greenCount++
                                                        // Blue Suits (Diamonds - CoinPoker 4-color)
                                                        else if (b > 100 && b > r + 40 && b > g + 15) blueCount++
                                                        // Black Suits (Spades)
                                                        else if (r < 75 && g < 75 && b < 75) blackCount++
                                                    }
                                                }
                                            }
                                            
                                            var suit = Suit.SPADES 
                                            if (redCount > greenCount && redCount > blueCount && redCount > blackCount && redCount > 5) suit = Suit.HEARTS 
                                            else if (greenCount > redCount && greenCount > blueCount && greenCount > blackCount && greenCount > 5) suit = Suit.CLUBS
                                            else if (blueCount > redCount && blueCount > greenCount && blueCount > blackCount && blueCount > 5) suit = Suit.DIAMONDS
                                            else if (blackCount > redCount && blackCount > greenCount && blackCount > blueCount && blackCount > 5) suit = Suit.SPADES
                                            else {
                                                if (redCount > 0) suit = Suit.HEARTS
                                                else if (greenCount > 0) suit = Suit.CLUBS
                                                else if (blueCount > 0) suit = Suit.DIAMONDS
                                            }
                                            
                                            val card = Card(rank, suit)
                                            foundCommCardsRaw.add(Pair(card, approxCenterBoxX))
                                        }
                                    }
                                }
                            }
                        } catch(e: Throwable) {} finally { 
                            croppedComm?.recycle()
                            scaledComm?.recycle()
                        }
                    }
                }

                // --- TARGETED HOLE CARD SCAN ---
                // Due to overlapping cards inside the hole rectangle, we split it into left and right halves,
                // space them out, and scale them up for much better OCR accuracy on the ranks.
                if (holeRect.width() > 0 && holeRect.height() > 0) {
                    val safeHole = android.graphics.Rect(
                        maxOf(0, holeRect.left - 10),
                        maxOf(0, holeRect.top - 10),
                        minOf(cleanBitmap.width, holeRect.right + 10),
                        minOf(cleanBitmap.height, holeRect.bottom + 10)
                    )
                    if (safeHole.width() > 10 && safeHole.height() > 10) {
                                var leftHalf: Bitmap? = null
                                var rightHalf: Bitmap? = null
                                var leftScaled: Bitmap? = null
                                var rightScaled: Bitmap? = null
                                var combinedScaled: Bitmap? = null
                                var croppedHole: Bitmap? = null
                        try {
                            croppedHole = Bitmap.createBitmap(cleanBitmap, safeHole.left, safeHole.top, safeHole.width(), safeHole.height())
                            val halfW = croppedHole.width / 2
                            leftHalf = Bitmap.createBitmap(croppedHole, 0, 0, halfW, croppedHole.height)
                            rightHalf = Bitmap.createBitmap(croppedHole, halfW, 0, croppedHole.width - halfW, croppedHole.height)
                            
                            val scale = 2
                            leftScaled = Bitmap.createScaledBitmap(leftHalf, leftHalf.width * scale, leftHalf.height * scale, true)
                            rightScaled = Bitmap.createScaledBitmap(rightHalf, rightHalf.width * scale, rightHalf.height * scale, true)
                            
                            val combinedW = leftScaled.width + rightScaled.width + 60
                            val combinedH = maxOf(leftScaled.height, rightScaled.height)
                            combinedScaled = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(combinedScaled)
                            canvas.drawColor(android.graphics.Color.BLACK)
                            canvas.drawBitmap(leftScaled, 0f, 0f, null)
                            canvas.drawBitmap(rightScaled, leftScaled.width.toFloat() + 60f, 0f, null)
                            
                            val holeInputImage = InputImage.fromBitmap(combinedScaled, 0)
                            val holeResult = recognizer.process(holeInputImage).await()
                            
                            val rankPatternRegex = Regex("(10|1|T|[AKQJGBSYZE46]|[2-9])")
                            for (block in holeResult.textBlocks) {
                                for (line in block.lines) {
                                    for (element in line.elements) {
                                        var rawText = element.text.uppercase(java.util.Locale.US).trim()
                                        rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")
                                        if (rawText == "O" || rawText == "D" || rawText == "0" || rawText == "Q") rawText = "Q"
                                        if (rawText == "Z" || rawText == "2") rawText = "2"
                                        if (rawText == "B" || rawText == "8") rawText = "8"
                                        if (rawText == "S" || rawText == "s" || rawText == "5") rawText = "5"
                                        if (rawText == "I" || rawText == "l" || rawText == "L" || rawText == "1") rawText = "1"
                                        if (rawText == "E" || rawText == "3") rawText = "3"
                                        if (rawText == "T" || rawText == "7" || rawText == "Y") rawText = "7"
                                        val match = rankPatternRegex.find(rawText)
                                        if (match != null) {
                                            val text = match.value
                                            val rank = parseRank(text) ?: continue
                                            val box = element.boundingBox ?: continue
                                            
                                            // Determine if it came from left or right half
                                            val isLeftHalf = box.centerX() < leftScaled.width + 30
                                            val approxCenterBoxX = if (isLeftHalf) {
                                                safeHole.left + (box.centerX() / scale)
                                            } else {
                                                safeHole.left + halfW + ((box.centerX() - leftScaled.width - 60) / scale)
                                            }
                                            
                                            var redCount = 0
                                            var greenCount = 0
                                            var blueCount = 0
                                            var blackCount = 0
                                            
                                            val startX = maxOf(0, approxCenterBoxX - (box.width() / scale))
                                            val endX = minOf(cleanBitmap.width - 1, approxCenterBoxX + (box.width() / scale / 4))
                                            val startY = maxOf(0, safeHole.top + (box.top / scale) - 5)
                                            val endY = minOf(cleanBitmap.height - 1, safeHole.top + (box.bottom / scale) + (box.height() / scale))
                                            
                                            if (startX <= endX && startY <= endY) {
                                                for (px in startX..endX step 2) {
                                                    for (py in startY..endY step 2) {
                                                        val pixel = cleanBitmap.getPixel(px, py)
                                                        val r = android.graphics.Color.red(pixel)
                                                        val g = android.graphics.Color.green(pixel)
                                                        val b = android.graphics.Color.blue(pixel)
                                                        
                                                        // Red Suits (Hearts)
                                                        if (r > 120 && r > g + 50 && r > b + 50) redCount++
                                                        // Green Suits (Clubs - CoinPoker 4-color)
                                                        else if (g > 100 && g > r + 40 && g > b + 30) greenCount++
                                                        // Blue Suits (Diamonds - CoinPoker 4-color)
                                                        else if (b > 100 && b > r + 40 && b > g + 15) blueCount++
                                                        // Black Suits (Spades)
                                                        else if (r < 75 && g < 75 && b < 75) blackCount++
                                                    }
                                                }
                                            }
                                            
                                            var suit = Suit.SPADES 
                                            if (redCount > greenCount && redCount > blueCount && redCount > blackCount && redCount > 5) suit = Suit.HEARTS 
                                            else if (greenCount > redCount && greenCount > blueCount && greenCount > blackCount && greenCount > 5) suit = Suit.CLUBS
                                            else if (blueCount > redCount && blueCount > greenCount && blueCount > blackCount && blueCount > 5) suit = Suit.DIAMONDS
                                            else if (blackCount > redCount && blackCount > greenCount && blackCount > blueCount && blackCount > 5) suit = Suit.SPADES
                                            else {
                                                if (redCount > 0) suit = Suit.HEARTS
                                                else if (greenCount > 0) suit = Suit.CLUBS
                                                else if (blueCount > 0) suit = Suit.DIAMONDS
                                            }
                                            
                                            val card = Card(rank, suit)
                                            foundHoleCardsRaw.add(Pair(card, approxCenterBoxX))
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            leftHalf?.recycle()
                            rightHalf?.recycle()
                            leftScaled?.recycle()
                            rightScaled?.recycle()
                            combinedScaled?.recycle()
                            croppedHole?.recycle()
                        }
                    }
                }
                // --- END TARGETED HOLE CARD SCAN ---
                
                val filteredBlocks = result.textBlocks.filter { block ->
                    val boundingBox = block.boundingBox
                    if (boundingBox == null) true
                    else !hudRects.any { android.graphics.Rect.intersects(it, boundingBox) || it.contains(boundingBox) }
                }
                
                for (block in filteredBlocks) {
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
                            
                            var rawText = element.text.uppercase(java.util.Locale.US).trim()
                            rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")
                            if (rawText == "O" || rawText == "D" || rawText == "0" || rawText == "Q") rawText = "Q"
                            if (rawText == "Z" || rawText == "2") rawText = "2"
                            if (rawText == "B" || rawText == "8") rawText = "8"
                            if (rawText == "S" || rawText == "s" || rawText == "5") rawText = "5"
                            if (rawText == "I" || rawText == "l" || rawText == "L" || rawText == "1") rawText = "1"
                            if (rawText == "E" || rawText == "3") rawText = "3"
                            if (rawText == "T" || rawText == "7" || rawText == "Y") rawText = "7"
                            
                            val rankPatternRegex = Regex("(10|1|T|[AKQJGBSYZE46]|[2-9])")
                            val matches = rankPatternRegex.findAll(rawText).toList()

                            for (match in matches) {
                                val text = match.value
                                val rank = parseRank(text) ?: continue

                                val matchRatio = if (rawText.isNotEmpty()) (match.range.first + match.range.last) / 2.0 / rawText.length else 0.5
                                val approxCenterBoxX = box.left + (box.width() * matchRatio).toInt()

                                var redCount = 0
                                var greenCount = 0
                                var blueCount = 0
                                var blackCount = 0
                                
                                val startX = maxOf(0, approxCenterBoxX - (box.width() * 1.2).toInt())
                                val endX = minOf(cleanBitmap.width - 1, approxCenterBoxX + (box.width() / 2))
                                val startY = maxOf(0, box.top - (box.height() / 4))
                                val endY = minOf(cleanBitmap.height - 1, box.bottom + (box.height() * 1.5).toInt())
                                
                                if (startX <= endX && startY <= endY) {
                                    for (px in startX..endX step 2) {
                                        for (py in startY..endY step 2) {
                                            val pixel = cleanBitmap.getPixel(px, py)
                                            val r = android.graphics.Color.red(pixel)
                                            val g = android.graphics.Color.green(pixel)
                                            val b = android.graphics.Color.blue(pixel)
                                            
                                            // Red Suits (Hearts)
                                            if (r > 120 && r > g + 50 && r > b + 50) redCount++
                                            // Green Suits (Clubs - CoinPoker 4-color)
                                            else if (g > 100 && g > r + 40 && g > b + 30) greenCount++
                                            // Blue Suits (Diamonds - CoinPoker 4-color)
                                            else if (b > 100 && b > r + 40 && b > g + 15) blueCount++
                                            // Black Suits (Spades)
                                            else if (r < 75 && g < 75 && b < 75) blackCount++
                                        }
                                    }
                                }
                                
                                var suit = Suit.SPADES 
                                if (redCount > greenCount && redCount > blueCount && redCount > blackCount && redCount > 5) suit = Suit.HEARTS 
                                else if (greenCount > redCount && greenCount > blueCount && greenCount > blackCount && greenCount > 5) suit = Suit.CLUBS
                                else if (blueCount > redCount && blueCount > greenCount && blueCount > blackCount && blueCount > 5) suit = Suit.DIAMONDS
                                else if (blackCount > redCount && blackCount > greenCount && blackCount > blueCount && blackCount > 5) suit = Suit.SPADES
                                else {
                                    if (redCount > 0) suit = Suit.HEARTS
                                    else if (greenCount > 0) suit = Suit.CLUBS
                                    else if (blueCount > 0) suit = Suit.DIAMONDS
                                }
                                
                                val card = Card(rank, suit)
                                
                                if (inComm) {
                                    // Check if we already have a card at similar X position in comm area
                                    if (!foundCommCardsRaw.any { Math.abs(it.second - approxCenterBoxX) < (box.width() / 2) }) {
                                        foundCommCardsRaw.add(Pair(card, approxCenterBoxX))
                                    }
                                } else if (inHole) {
                                    // Check if we already have a card at similar X position in hole area
                                    if (!foundHoleCardsRaw.any { Math.abs(it.second - approxCenterBoxX) < (box.width() / 2) }) {
                                        foundHoleCardsRaw.add(Pair(card, approxCenterBoxX))
                                    }
                                }
                            }
                        }
                    }
                }
                
                val foundCommCards = foundCommCardsRaw.sortedBy { it.second }.map { it.first }.distinct().toMutableList()
                val foundHoleCards = foundHoleCardsRaw.sortedBy { it.second }.map { it.first }.distinct().toMutableList()
                
                val commW = commRect.width()
                val holeW = holeRect.width()
                val h1 = foundHoleCards.getOrNull(0)
                val h2 = foundHoleCards.getOrNull(1)
                
                val currentState = PokerHudSharedState.uiState.value
                
                if (foundHoleCards.isEmpty()) {
                    consecutiveEmptyHole++
                } else {
                    consecutiveEmptyHole = 0
                }
                
                if (foundCommCards.isEmpty()) {
                    consecutiveEmptyComm++
                } else {
                    consecutiveEmptyComm = 0
                }
                
                val finalH1 = h1 ?: if (consecutiveEmptyHole < 3) currentState.heroCard1 else null
                val finalH2 = h2 ?: if (consecutiveEmptyHole < 3) currentState.heroCard2 else null
                val finalBoard = List(5) { i ->
                    foundCommCards.getOrNull(i) ?: if (consecutiveEmptyComm < 3) currentState.board.getOrNull(i) else null
                }
                
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
                            histPfr = scannedProfile.histPfr ?: existing.histPfr,
                            hist3Bet = scannedProfile.hist3Bet ?: existing.hist3Bet,
                            histFoldTo3Bet = scannedProfile.histFoldTo3Bet ?: existing.histFoldTo3Bet,
                            histCBet = scannedProfile.histCBet ?: existing.histCBet,
                            histFoldToCBet = scannedProfile.histFoldToCBet ?: existing.histFoldToCBet,
                            histSteal = scannedProfile.histSteal ?: existing.histSteal,
                            histCheckRaise = scannedProfile.histCheckRaise ?: existing.histCheckRaise,
                            histWtsd = scannedProfile.histWtsd ?: existing.histWtsd,
                            histWsd = scannedProfile.histWsd ?: existing.histWsd
                        )
                        prefsManager.savePlayerStats(updated)
                        
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Profile parsed for ${scannedProfile.nickname}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Failed to parse Profile", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    requestProfileScan = false
                    
                    if (stopAfterProfileScan) {
                        PokerHudSharedState.externalActions.tryEmit(
                            ExternalAction.UpdateCards(finalH1, finalH2, finalBoard, finalOpponents, profileBoxesToHighlight, updateProfileBoxes = true)
                        )
                        stop()
                        return
                    }
                }

                scanStatus.value = "H:${foundHoleCards.size} C:${foundCommCards.size} (${commW},${holeW})<br>" +
                                   "Opps: ${finalOpponents.size}<br>" +
                                   "Board: ${finalBoard.joinToString("") { it?.toHtmlString() ?: "[?]" }}"
                
                PokerHudSharedState.externalActions.tryEmit(
                    ExternalAction.UpdateCards(finalH1, finalH2, finalBoard, finalOpponents, profileBoxesToHighlight, updateProfileBoxes = (profileBoxesToHighlight != null))
                )
                
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
            "A", "4", "6" -> Rank.ACE
            "K" -> Rank.KING
            "Q" -> Rank.QUEEN
            "J" -> Rank.JACK
            "10", "T", "1" -> Rank.TEN
            "9", "G" -> Rank.NINE
            "8", "B" -> Rank.EIGHT
            "7", "Y" -> Rank.SEVEN
            "6" -> Rank.SIX
            "5", "S" -> Rank.FIVE
            "4" -> Rank.FOUR
            "3", "E" -> Rank.THREE
            "2", "Z" -> Rank.TWO
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
