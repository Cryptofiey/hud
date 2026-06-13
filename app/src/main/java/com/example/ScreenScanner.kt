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
        val recognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
    
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
                // Confirm the card only if it has been seen at least twice.
                if (best.value >= 2) {
                    result.add(best.key)
                    confirmed[i] = best.key
                } else {
                    if (prevConfirmed != null) {
                        result.add(prevConfirmed)
                    } else {
                        result.add(null)
                    }
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

    private fun applyCardThresholding(bmp: Bitmap, vararg rects: android.graphics.Rect?) {
        for (rect in rects) {
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
                
                val threshold = ScannerConfig.ocrThreshold
                // If it's very bright white (as rank text and suit symbols on CoinPoker are solid white),
                // it should be black text for OCR. Anything else is card/table background and should be white.
                val color = if (r > threshold && g > threshold && b > threshold) {
                    0xFF000000.toInt() // Black text
                } else {
                    0xFFFFFFFF.toInt() // White background
                }
                pixels[i] = color
            }
            
            bmp.setPixels(pixels, 0, width, left, top, width, height)
        }
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
                Triple(pokerHudService.getCommRect(), pokerHudService.getHoleRect(), pokerHudService.getHudRects())
            }
            val commRect = rects.first
            val holeRect = rects.second
            val hudRects = rects.third
            
            val activeCommRect = if (commRect.width() > 20) {
                // Buffer zones inside the frame window
                val bufferX = (commRect.width() * 0.05f).toInt()
                val bufferY = (commRect.height() * 0.05f).toInt()
                // Right side exclusion for the drag/close symbols (approx 15% of width or minimum 50px)
                val rightExclusion = maxOf((commRect.width() * 0.15f).toInt(), 50)
                
                android.graphics.Rect(
                    commRect.left + bufferX,
                    commRect.top + bufferY,
                    commRect.right - rightExclusion,
                    commRect.bottom - bufferY
                )
            } else {
                commRect
            }
            
            val activeHoleRect = if (holeRect.width() > 20) {
                // Buffer zones inside the frame window
                val bufferX = (holeRect.width() * 0.05f).toInt()
                val bufferY = (holeRect.height() * 0.05f).toInt()
                // Right side exclusion for the drag/close symbols
                val rightExclusion = maxOf((holeRect.width() * 0.15f).toInt(), 50)
                
                android.graphics.Rect(
                    holeRect.left + bufferX,
                    holeRect.top + bufferY,
                    holeRect.right - rightExclusion,
                    holeRect.bottom - bufferY
                )
            } else {
                holeRect
            }
            
            val currentState = PokerHudSharedState.uiState.value

            // 1. RUN FULL SCREEN OCR
            ocrBitmap = cleanBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
            applyCardThresholding(ocrBitmap!!, activeHoleRect, activeCommRect)
            
            val inputImage = InputImage.fromBitmap(ocrBitmap!!, 0)
            val result = recognizer.process(inputImage).await()
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
            
            val fullScanText = result.text.uppercase()
            
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

            val potMatch = Regex("(POT|ПОТ)[^0-9]*([0-9.,]+)").find(fullScanText)
            if (potMatch != null) {
                val numStr = potMatch.groupValues[2].replace(",", "")
                if (numStr.count { it == '.' } <= 1) {
                    scannedPotSize = numStr.toFloatOrNull()
                }
            }
            
            val heroActionOptions = mutableSetOf<String>()
            var hasPreactions = false

            for (block in result.textBlocks) {
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

                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        
                        // Ignore tiny text for action/transition buttons to prevent clicking on player avatars/names
                        // 0.008f is approx 19px on 2400h screen, small enough to catch button text but avoid static noise
                        if (box.height() < cleanBitmap!!.height * 0.008f) continue
                        
                        var insideHud = false
                        for (hudRect in hudRects) {
                            if (android.graphics.Rect.intersects(hudRect, box)) {
                                insideHud = true
                                break
                            }
                        }
                        if (insideHud) continue
                        
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
                        
                        if (commRect.width() > 20 && activeCommRect.contains(cx, cy)) {
                            commElements.add(element)
                        } else if (holeRect.width() > 20 && activeHoleRect.contains(cx, cy)) {
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

                val parsedRanks = findCardsInText(safeText)
                for ((idx, rank) in parsedRanks.withIndex()) {
                    val sliceWidth = box.width() / parsedRanks.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    if (isCardBackground(cleanBitmap!!, sliceBox)) {
                        val refinedRank = refineRankWithPixelCheck(ocrBitmap, sliceBox, rank)
                        val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                        tempCommCards.add(Pair(Card(refinedRank, suit), sliceBox))
                    } else {
                        android.util.Log.d("CardBgDetect", "Ignoring community element '$rank' due to non-card background.")
                    }
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

                val parsedRanksRaw = findCardsInText(safeText, isHoleCard = true)
                for ((idx, rankRaw) in parsedRanksRaw.withIndex()) {
                    // Slicing the bounding box if multiple ranks are merged in a single text block
                    val sliceWidth = box.width() / parsedRanksRaw.size
                    val sliceLeft = box.left + (idx * sliceWidth)
                    val sliceRight = sliceLeft + sliceWidth
                    val sliceBox = android.graphics.Rect(sliceLeft, box.top, sliceRight, box.bottom)
                    
                    if (isCardBackground(cleanBitmap!!, sliceBox)) {
                        val refinedRank = refineRankWithPixelCheck(ocrBitmap, sliceBox, rankRaw)
                        val suit = robustDetectSuit(cleanBitmap, sliceBox) ?: Suit.SPADES
                        tempHoleCards.add(Pair(Card(refinedRank, suit), sliceBox))
                    } else {
                        android.util.Log.d("CardBgDetect", "Ignoring hole element '$rankRaw' due to non-card background.")
                    }
                }
            }
            
            fun clusterCards(cards: List<Pair<Card, android.graphics.Rect>>, maxCards: Int, regionRect: android.graphics.Rect): MutableList<Card?> {
                val resultList = MutableList<Card?>(maxCards) { null }
                if (cards.isEmpty()) return resultList
                
                // Group detections by their physical geometric slots (bins) within the region.
                // This guarantees that adjacent symbols are NEVER grouped as the same card,
                // while correctly merging the top and bottom symbols of a SINGLE card.
                val slotWidth = regionRect.width().toFloat() / maxCards
                val slots = Array<MutableList<Pair<Card, android.graphics.Rect>>>(maxCards) { mutableListOf() }
                
                for (elem in cards) {
                    val relativeX = elem.second.centerX() - regionRect.left
                    val slotIndex = (relativeX / slotWidth).toInt().coerceIn(0, maxCards - 1)
                    slots[slotIndex].add(elem)
                }
                
                for (i in 0 until maxCards) {
                    val cluster = slots[i]
                    if (cluster.isNotEmpty()) {
                        // On CoinPoker, the large rank character is at the top-left (low Y / 'top').
                        // By sorting by Y coordinate ascending, we prioritize the upright top-left rank detection.
                        val sortedClusterByTop = cluster.sortedBy { it.second.top }
                        val primaryCard = sortedClusterByTop.first().first
                        resultList[i] = primaryCard
                    }
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
            
            // Limit to 5 opponents max for 6-max format to prevent breaking position assignments
            if (validOpponents.size > 5) {
                validOpponents = validOpponents.sortedByDescending { it.boundingBox?.height() ?: 0 }.take(5)
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
                if (hasDealerButton(cleanBitmap!!, box, result.textBlocks)) {
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
                    isBbDisplay = Regex("([0-9.,]+)\\s*(BB|ББ)").containsMatchIn(result.text.uppercase())
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

    private fun isCardBackground(crop: Bitmap, rect: android.graphics.Rect): Boolean {
        val left = maxOf(0, rect.left)
        val right = minOf(crop.width - 1, rect.right)
        val top = maxOf(0, rect.top)
        val bottom = minOf(crop.height - 1, rect.bottom)
        
        val totalPixels = (right - left + 1) * (bottom - top + 1)
        if (totalPixels <= 0) return false
        
        var brightCount = 0
        for (x in left..right) {
            for (y in top..bottom) {
                val p = crop.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Bright card background check (95 is perfectly safe for shadows, while rejecting table)
                if (r > 95 && g > 95 && b > 95) {
                    brightCount++
                }
            }
        }
        
        val ratio = brightCount.toFloat() / totalPixels
        val isCard = ratio >= 0.35f
        android.util.Log.d("CardBgDetect", "Box: $rect | Bright=$brightCount Total=$totalPixels Ratio=$ratio | isCard=$isCard")
        return isCard
    }

    private fun findCardsInText(text: String, isHoleCard: Boolean = false): List<Rank> {
        val found = mutableListOf<Rank>()
        // Pre-replace common OCR symbol mistakes before stripping
        var preProcessed = text.uppercase(java.util.Locale.US)
        preProcessed = preProcessed.replace("&", "8").replace("$", "8").replace("@", "Q").replace("%", "8").replace("?", "7").replace("!", "1")
        // Replace Cyrillic lookalikes
        preProcessed = preProcessed.replace("А", "A").replace("К", "K").replace("Т", "T").replace("В", "B").replace("О", "O").replace("С", "C").replace("Р", "P")

        // Keep only letters, digits, and suit symbols to form a dense string
        val raw = preProcessed.replace(Regex("[^A-Z0-9\u2660\u2663\u2665\u2666]"), "")
            
        if (raw.length > 20) return emptyList() // Too long, likely noise
        if (raw.isEmpty()) return emptyList()
        
        // Block obvious chip stacks or bets, but ONLY for community cards (hole cards might be '104' without space)
        if (!isHoleCard) {
            val noSymbols = text.uppercase(java.util.Locale.US).replace(Regex("[^A-Z0-9 ]"), "")
            if (noSymbols.matches(Regex(".*\\d{3,}.*"))) return emptyList() // 100, 500 etc.
        }

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

        // Expand the search area to make sure we hit the card background
        // CRITICAL FOR HOLE CARDS: Do not expand much to the right, as the left hole card
        // is overlapped by the right hole card. The safest area to sample color is 
        // to the left and directly below the rank text.
        val expandLeft = maxOf(10, (rankBox.width() * 0.5f).toInt())
        val expandRight = maxOf(5, (rankBox.width() * 0.2f).toInt()) // Restrict right expansion!
        val expandTop = maxOf(5, (rankBox.height() * 0.2f).toInt())
        val expandBottom = maxOf(15, (rankBox.height() * 1.5f).toInt()) // Expand more downwards towards the suit symbol

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
                if (r < 20 && g < 20 && b < 20) continue
                
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
        
        // If the color is essentially gray/black with very little saturation, it's Spades
        if (chroma < 15 || (maxColor < 60 && chroma < 25)) {
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
            Rank.SEVEN, Rank.TWO -> {
                // Verify 7 vs 2
                val bottomLeft = getRatio(0.22f, 0.85f, radius = 1)
                android.util.Log.d("RankRefiner", "7 vs 2 check: bottomLeft=$bottomLeft")
                if (bottomLeft > 0.35f) {
                    Rank.TWO
                } else {
                    Rank.SEVEN
                }
            }
            Rank.ACE, Rank.FOUR -> {
                // Verify Ace vs 4
                val topCenter = getRatio(0.50f, 0.15f, radius = 1)
                android.util.Log.d("RankRefiner", "A vs 4 check: topCenter=$topCenter")
                if (topCenter > 0.35f) {
                    Rank.ACE
                } else {
                    Rank.FOUR
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
