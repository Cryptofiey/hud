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
        // Expand the search area slightly to ensure we check the background, not just the white text
        val bounds = android.graphics.Rect(
            (box.left - box.width() / 4).coerceAtLeast(0),
            (box.top - box.height() / 4).coerceAtLeast(0),
            (box.right + box.width() / 4).coerceAtMost(bitmap.width - 1),
            (box.bottom + box.height() / 4).coerceAtMost(bitmap.height - 1)
        )
        
        var brightPixelsCount = 0
        var totalSamples = 0
        
        // Sample a grid across the expanded area
        val xStep = (bounds.width() / 6).coerceAtLeast(1)
        val yStep = (bounds.height() / 6).coerceAtLeast(1)
        
        for (x in bounds.left..bounds.right step xStep) {
            for (y in bounds.top..bounds.bottom step yStep) {
                val color = bitmap.getPixel(x, y)
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(color, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                val hue = hsv[0]
                
                // Solid bright color (e.g. green, orange, red) vs dark grey/white
                // Exclude Purple/Blue table felt hues (approx 240 to 320)
                val isPurpleFelt = hue in 240f..320f
                if (saturation > 0.15f && value > 0.15f && !isPurpleFelt) {
                    brightPixelsCount++
                }
                totalSamples++
            }
        }
        
        // If at least 10% of sampled pixels are colorful, it's a bright button
        return brightPixelsCount.toFloat() / totalSamples > 0.10f
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
            val transitionButtonsMap = mutableMapOf<String, android.graphics.Rect>()

            // We look for action buttons in the bottom 25% of the screen (to exclude opponent status tags near the hero)
            val bottomZoneTop = cleanBitmap!!.height * 0.75

            var scannedPotSize: Float? = null
            
            val fullScanText = result.text.uppercase()
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
                        if (box.height() < cleanBitmap!!.height * 0.015f) continue
                        
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
                            transitionButtonsMap[transitionKey] = box
                        }
                        
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
                        
                            // Action buttons logic
                            if (box.top > bottomZoneTop) {
                                val textUpper = element.text.uppercase()
                                
                                // Prevent tiny non-button text from being recognized:
                                if (box.width() < cleanBitmap!!.width * 0.01f) continue
                                if (box.height() < cleanBitmap!!.height * 0.005f) continue // Ignore tiny texts
                                
                                val isBright = isColorfulButton(cleanBitmap!!, box)
                                val isLargeButton = box.height() > cleanBitmap!!.height * 0.015f || box.width() > cleanBitmap!!.width * 0.1f
                                val isPrimary = textUpper.contains("FOLD") || textUpper.contains("ФОЛД") || 
                                               textUpper.contains("PАС") || textUpper.contains("CHECK") || 
                                               textUpper.contains("CALL") || textUpper.contains("КОЛЛ") || 
                                               textUpper.contains("RAISE") || textUpper.contains("РЕЙЗ") || 
                                               textUpper.contains("BET") || textUpper.contains("ALL-IN")
                                
                                // Primary actions (Must be bright colorful buttons or large enough to clearly not be a pre-action checkbox)
                                val qualifiesAsAction = isBright || (isPrimary && isLargeButton)
                                
                                if ((textUpper.contains("FOLD") || textUpper.contains("ФОЛД") || textUpper.contains("ПАС")) && !textUpper.contains("ANY")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Fold")
                                        actionButtonsMap[textUpper] = box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if ((textUpper.contains("CHECK") || textUpper.contains("ЧЕК")) && !textUpper.contains("FOLD") && !textUpper.contains("ФОЛД")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Check")
                                        actionButtonsMap[textUpper] = box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if ((textUpper.contains("CALL") || textUpper.contains("КОЛЛ")) && !textUpper.contains("ANY") && !textUpper.contains("ЛЮБ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Call")
                                        actionButtonsMap[textUpper] = box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if (textUpper.contains("RAISE") || textUpper.contains("РЕЙЗ") || 
                                         textUpper.contains("BET") || textUpper.contains("БЕТ") || 
                                         textUpper.contains("CONFIRM") || textUpper.contains("ПОДТВЕРДИТЬ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("Raise") // Mapping Confirm to Raise internally
                                        actionButtonsMap[textUpper] = box
                                    } else {
                                        hasPreactions = true
                                    }
                                } 
                                else if (textUpper.contains("ALL-IN") || textUpper.contains("ALL IN") || 
                                         textUpper.contains("ОЛЛ-ИН") || textUpper.contains("ALL") || 
                                         textUpper.contains("ОЛЛ")) {
                                    if (qualifiesAsAction) {
                                        heroActionOptions.add("All-in")
                                        actionButtonsMap[textUpper] = box
                                    }
                                }
                                else if (textUpper.contains("STRADDLE") || textUpper.contains("СТРАДДЛ")) {
                                    if (isBright) actionButtonsMap[textUpper] = box
                                }
                                
                                // Add common bet sizing multipliers. These are usually grey, so we ignore color.
                                val noSpaceText = textUpper.replace(" ", "")
                                if (noSpaceText == "1/2" || noSpaceText == "1/3" || noSpaceText == "2/3" || noSpaceText == "3/4" || 
                                    noSpaceText == "1/2POT" || noSpaceText == "2/3POT" || noSpaceText == "3/4POT" ||
                                    textUpper.contains("POT") || textUpper.contains("ПОТ") || textUpper.contains("MAX") || textUpper.contains("МАКС") ||
                                    textUpper.contains("MIN") || textUpper.contains("МИН") || textUpper.matches(Regex(".*\\d+X.*")) || textUpper.contains("%") ||
                                    noSpaceText == "+" || noSpaceText == "-") {
                                    actionButtonsMap[textUpper] = box
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
                val parsedRanks = findCardsInText(safeText, isHoleCard = true)
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
                val sorted = cards.sortedBy { it.second.centerX() }
                val clusters = mutableListOf<MutableList<Pair<Card, android.graphics.Rect>>>()
                
                for (elem in sorted) {
                    if (clusters.isEmpty()) {
                        clusters.add(mutableListOf(elem))
                    } else {
                        val lastCluster = clusters.last()
                        val lastCx = lastCluster.map { it.second.centerX() }.average()
                        val avgWidth = lastCluster.map { it.second.width() }.average().toFloat()
                        val avgHeight = lastCluster.map { it.second.height() }.average().toFloat()
                        
                        // Use a tighter threshold to group same-card detections 
                        // while keeping strictly separate cards separated.
                        val clusterThreshold = maxOf(avgWidth * 0.4f, regionRect.width() * 0.08f, 15f)
                        
                        if (elem.second.centerX() - lastCx < clusterThreshold) {
                            lastCluster.add(elem)
                        } else {
                            clusters.add(mutableListOf(elem))
                        }
                    }
                }
                
                // Compute the total bounding box area for each cluster to distinguish real cards from tiny noise
                val clustersWithAreaAndX = clusters.map { cluster ->
                    val area = cluster.sumOf { it.second.width() * it.second.height() }
                    val minX = cluster.minOf { it.second.centerX() }
                    
                    val ranks = cluster.map { it.first.rank }
                    val finalRank = ranks.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    
                    val suits = cluster.map { it.first.suit }
                    val finalSuit = suits.groupBy { it }.maxByOrNull { it.value.size }!!.key
                    
                    Triple(Card(finalRank, finalSuit), area, minX)
                }
                
                // Deduplicate consecutive identical cards
                val deduplicated = clustersWithAreaAndX.filterIndexed { index, item -> 
                    index == 0 || item.first != clustersWithAreaAndX[index - 1].first
                }
                
                // Sort by area descending so real cards beat noise like the timer, then take maxCards
                // Then sort them back by X coordinate so they are in left-to-right order
                val topCards = deduplicated.sortedByDescending { it.second }
                    .take(maxCards)
                    .sortedBy { it.third }
                    .map { it.first }
                
                for (i in 0 until topCards.size) {
                    resultList[i] = topCards[i]
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
            
            val nlhMatch = Regex("NLH\\s*-\\s*([0-9.,KM]+)\\s*/\\s*([0-9.,KM]+)(?:\\s*\\(LEVEL\\s*(\\d+)\\))?").find(fullScanText)
            if (nlhMatch != null) {
                parsedSB = parseCleanValue(nlhMatch.groupValues[1])
                parsedBB = parseCleanValue(nlhMatch.groupValues[2])
                
                val levelValStr = nlhMatch.groupValues.getOrNull(3)
                if (levelValStr != null) {
                    val levelVal = levelValStr.toIntOrNull()
                    if (levelVal != null) {
                        parsedStage = when {
                            levelVal <= 6 -> TournamentStage.EARLY
                            levelVal <= 14 -> TournamentStage.MIDDLE
                            else -> TournamentStage.LATE
                        }
                    }
                }
            }

            // Hero is usually at the bottom-center of the screen.
            // Exclude hero from opponents and extract hero stack.
            var scannedHeroStack: Float? = null
            var scannedHeroBet: Float? = null
            var heroBoundingBox: android.graphics.Rect? = null
            val finalOpponents = finalOpponentsRaw.filter { opp ->
                val box = opp.boundingBox
                if (box != null && box.top > cleanBitmap!!.height * 0.70f && box.left > cleanBitmap.width * 0.2f && box.right < cleanBitmap.width * 0.8f) {
                    scannedHeroStack = opp.stackSize
                    scannedHeroBet = opp.betSize
                    heroBoundingBox = box
                    false // EXCLUDE from opponents
                } else {
                    true // Keep as opponent
                }
            }

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
                val prevDealerOpp = currentState.opponents.firstOrNull { it.isDealer }
                if (prevDealerOpp != null) {
                    dealerPlayer = seatedPlayers.firstOrNull { it.nickname == prevDealerOpp.nickname }
                } else if (currentState.position == TablePosition.BTN) {
                    dealerPlayer = heroState
                }
            }
            
            val screenCenterX = cleanBitmap!!.width / 2f
            val screenCenterY = cleanBitmap!!.height / 2f
            
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
            if (btnIdx == -1) btnIdx = 0
            
            val mappedPositions = assignPositions(orderedSeated, btnIdx)
            val heroPos = mappedPositions[heroNick] ?: TablePosition.BTN
            
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
            
            val hasTableElements = finalH1 != null || finalH2 != null || finalBoard.any { it != null } || finalOpponents.isNotEmpty() || scannedPotSize != null || (actionButtonsMap.isNotEmpty() && !isProfileScreen)
            
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
                scanStatus.value = "Агент-Сторож: Мы в CoinPoker, но неизвестная страница. Ждем..."
                heroActionOptions.clear()
            } else if (currentContext == AppScreenState.COINPOKER_PROFILE) {
                RobotPlayer.availableActionButtons = emptyMap()
                RobotPlayer.lobbyTransitionButtons = emptyMap()
                scanStatus.value = "Агент-Сторож: Открыт профиль игрока. Автокликер приостановлен."
                heroActionOptions.clear()
            } else {
                RobotPlayer.availableActionButtons = actionButtonsMap
                RobotPlayer.lobbyTransitionButtons = transitionButtonsMap
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
                    isBbDisplay = (scannedHeroStack != null && scannedHeroStack < 5000f && scannedHeroStack % 1.0f != 0.0f) || 
                                  Regex("([0-9.,]+)\\s*(BB|ББ)").containsMatchIn(result.text.uppercase())
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
        
        var rC = 0; var gC = 0; var bC = 0; var blkC = 0
        
        // Scan just around the rank and look down below it where the suit symbol is located.
        // Shrink slightly horizontally to avoid bleeding into adjacent cards if they are tightly clustered.
        val adjustX = (rankBox.width() * 0.1).toInt()
        val left = maxOf(0, rankBox.left + adjustX) // Changed from - to + to shrink into center
        val right = minOf(w - 1, rankBox.right - adjustX)
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
                
                // We loosen the purple/orange ignoring slightly to ensure we capture actual card colors correctly,
                // but keep it enough to avoid table background. The table is purple.
                val isPurple = (r > g + 30 && b > g + 30 && r > 40 && b > 40)
                val isOrange = (r > g + 20 && g > b + 20 && r > 80 && r - b > 50)
                
                if (saturation > 40 && max > 35 && !isPurple && !isOrange) {
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
        val dominantChroma = maxOf(rC, gC, bC)
        
        // Only classify as a colored suit (Red/Green/Blue) if its dominant color is a strong signal, 
        // significantly higher than blkC or an absolute large amount.
        if (totalChroma >= 5 && dominantChroma > blkC * 0.4f) {
            if (rC > gC && rC > bC) return Suit.HEARTS
            if (gC > rC && gC > bC) return Suit.CLUBS
            if (bC > rC && bC > gC) return Suit.DIAMONDS
        }
        
        if (totalChroma + blkC < 2) return null
        
        return Suit.SPADES
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
        for (block in textBlocks) {
            for (line in block.lines) {
                val txt = line.text.trim().uppercase()
                val lineBox = line.boundingBox ?: continue
                if ((txt == "D" || txt == "DEALER" || txt == "BTN") && 
                    android.graphics.Rect.intersects(box, lineBox)) {
                    return true
                }
            }
        }
        
        val searchLeft = maxOf(0, box.left - 45)
        val searchTop = maxOf(0, box.top - 45)
        val searchRight = minOf(bitmap.width - 1, box.right + 45)
        val searchBottom = minOf(bitmap.height - 1, box.bottom + 45)
        
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
            2 -> listOf(TablePosition.SB, TablePosition.BTN)
            3 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB)
            4 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG)
            5 -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.CO)
            else -> listOf(TablePosition.BTN, TablePosition.SB, TablePosition.BB, TablePosition.UTG, TablePosition.MP, TablePosition.CO)
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
