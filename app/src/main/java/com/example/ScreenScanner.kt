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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null
    
    var requestProfileScan = stopAfterProfileScan
    val isScanning = MutableStateFlow(false)
    val scanStatus = MutableStateFlow("Scanner idle.")

    private val bitmapLock = Any()
    private var cachedCleanBitmap: Bitmap? = null
    private var emptyOpponentsFrames = 0

    fun getLatestBitmapCopy(): Bitmap? {
        synchronized(bitmapLock) {
            val bmp = cachedCleanBitmap ?: return null
            return bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    @SuppressLint("WrongConstant")
    fun start() {
        if (isScanning.value) return
        try {
            isScanning.value = true
            scanStatus.value = "Starting NVIDIA NIM API scanner..."

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
                        delay(200) // Adaptive polling rate with NIM API to avoid rate limiting
                    } else {
                        delay(50)
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Error starting scanner", e)
            scanStatus.value = "Scanner start failed: ${e.message}"
            isScanning.value = false
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
            buffer.clear()
            image.close()
            image = null

            // Perform screen analysis using NVIDIA NIM API
            val result = NvidiaNimService.analyzeScreen(context, cleanBitmap)
            if (result is NimResult.Success) {
                val jsonStr = result.jsonResponse
                val gson = com.google.gson.Gson()
                val jsonObj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
                
                val pageType = jsonObj.get("pageType")?.asString ?: "UNKNOWN_SCREEN"
                
                if (pageType == "ACTIVE_TABLE") {
                    PokerHudSharedState.appScreenContext.value = AppScreenState.COINPOKER_TABLE
                    emptyOpponentsFrames = 0

                    // Parse Hero Cards
                    var finalH1: Card? = null
                    var finalH2: Card? = null
                    val jsonHeroCards = jsonObj.getAsJsonArray("heroCards")
                    if (jsonHeroCards != null && jsonHeroCards.size() >= 2) {
                        finalH1 = parseCardString(jsonHeroCards[0]?.asString)
                        finalH2 = parseCardString(jsonHeroCards[1]?.asString)
                    }
                    
                    // Parse Board
                    val finalBoard = mutableListOf<Card?>()
                    val jsonBoard = jsonObj.getAsJsonArray("board")
                    if (jsonBoard != null) {
                        for (i in 0 until jsonBoard.size()) {
                            finalBoard.add(parseCardString(jsonBoard[i]?.asString))
                        }
                    }
                    while (finalBoard.size < 5) {
                        finalBoard.add(null)
                    }
                    
                    // Parse values
                    val potSize = jsonObj.get("potSize")?.asFloat
                    val dealerPosStr = jsonObj.get("dealerPosition")?.asString ?: "BTN"
                    val heroTurn = jsonObj.get("heroTurn")?.asBoolean ?: false
                    val heroStack = jsonObj.get("heroStack")?.asFloat
                    val heroBet = jsonObj.get("heroBet")?.asFloat
                    val sbVal = jsonObj.get("smallBlind")?.asFloat
                    val bbVal = jsonObj.get("bigBlind")?.asFloat
                    val stageStr = jsonObj.get("tournamentStage")?.asString
                    val isBbDisplay = jsonObj.get("isBbDisplay")?.asBoolean ?: false
                    
                    val availableActionsList = mutableListOf<String>()
                    val jsonActions = jsonObj.getAsJsonArray("availableActions")
                    if (jsonActions != null) {
                        for (i in 0 until jsonActions.size()) {
                            availableActionsList.add(jsonActions[i].asString)
                        }
                    }
                    
                    // Standard CoinPoker mapping for Clicker
                    val actionButtonsMap = mutableMapOf<String, android.graphics.Rect>()
                    val sizingButtonsMap = mutableMapOf<String, android.graphics.Rect>()
                    val transitionButtonsMap = mutableMapOf<String, android.graphics.Rect>()
                    
                    // Proportional sizing based on the screen dimension to withstand overlay clicks
                    if (availableActionsList.contains("Fold")) {
                        actionButtonsMap["Fold"] = android.graphics.Rect(
                            (width * 0.05).toInt(), (height * 0.86).toInt(),
                            (width * 0.35).toInt(), (height * 0.94).toInt()
                        )
                    }
                    if (availableActionsList.contains("Check")) {
                        actionButtonsMap["Check"] = android.graphics.Rect(
                            (width * 0.38).toInt(), (height * 0.86).toInt(),
                            (width * 0.62).toInt(), (height * 0.94).toInt()
                        )
                    }
                    if (availableActionsList.contains("Call")) {
                        actionButtonsMap["Call"] = android.graphics.Rect(
                            (width * 0.38).toInt(), (height * 0.86).toInt(),
                            (width * 0.62).toInt(), (height * 0.94).toInt()
                        )
                    }
                    if (availableActionsList.contains("Raise") || availableActionsList.contains("Bet")) {
                        actionButtonsMap["Raise"] = android.graphics.Rect(
                            (width * 0.65).toInt(), (height * 0.86).toInt(),
                            (width * 0.95).toInt(), (height * 0.94).toInt()
                        )
                    }
                    if (availableActionsList.contains("All-in")) {
                        actionButtonsMap["All-In"] = android.graphics.Rect(
                            (width * 0.65).toInt(), (height * 0.78).toInt(),
                            (width * 0.95).toInt(), (height * 0.84).toInt()
                        )
                    }

                    sizingButtonsMap["1/2"] = android.graphics.Rect(
                        (width * 0.15).toInt(), (height * 0.78).toInt(),
                        (width * 0.30).toInt(), (height * 0.84).toInt()
                    )
                    sizingButtonsMap["2/3"] = android.graphics.Rect(
                        (width * 0.32).toInt(), (height * 0.78).toInt(),
                        (width * 0.47).toInt(), (height * 0.84).toInt()
                    )
                    sizingButtonsMap["3/4"] = android.graphics.Rect(
                        (width * 0.49).toInt(), (height * 0.78).toInt(),
                        (width * 0.64).toInt(), (height * 0.84).toInt()
                    )
                    sizingButtonsMap["POT"] = android.graphics.Rect(
                        (width * 0.66).toInt(), (height * 0.78).toInt(),
                        (width * 0.81).toInt(), (height * 0.84).toInt()
                    )
                    sizingButtonsMap["+"] = android.graphics.Rect(
                        (width * 0.83).toInt(), (height * 0.78).toInt(),
                        (width * 0.95).toInt(), (height * 0.84).toInt()
                    )
                    
                    RobotPlayer.availableActionButtons = actionButtonsMap
                    RobotPlayer.sizingButtonsMap = sizingButtonsMap
                    RobotPlayer.lobbyTransitionButtons = transitionButtonsMap

                    // Parse Opponents
                    val finalOpponentsWithPositions = mutableListOf<OpponentState>()
                    val jsonOpponents = jsonObj.getAsJsonArray("opponents")
                    if (jsonOpponents != null) {
                        for (i in 0 until jsonOpponents.size()) {
                            val oppObj = jsonOpponents[i].asJsonObject
                            val nick = oppObj.get("nickname")?.asString ?: "Player $i"
                            val stack = oppObj.get("stackSize")?.asFloat ?: 1000f
                            val bet = oppObj.get("betSize")?.asFloat ?: 0f
                            val isActive = oppObj.get("isActive")?.asBoolean ?: true
                            val isDealer = oppObj.get("isDealer")?.asBoolean ?: false
                            val posStr = oppObj.get("positionName")?.asString ?: "NONE"
                            
                            val prefsManager = PreferencesManager(context)
                            val stats = prefsManager.loadPlayerStats(nick)
                            
                            finalOpponentsWithPositions.add(OpponentState(
                                id = i,
                                nickname = nick,
                                stackSize = stack,
                                betSize = bet,
                                isActive = isActive,
                                isDealer = isDealer,
                                positionName = posStr,
                                stats = stats
                            ))
                        }
                    }

                    val heroPos = try { TablePosition.valueOf(dealerPosStr) } catch(e: Exception) { TablePosition.BTN }
                    val parsedStage = try { TournamentStage.valueOf(stageStr ?: "EARLY") } catch(e: Exception) { TournamentStage.EARLY }

                    PokerHudSharedState.externalActions.tryEmit(
                        ExternalAction.UpdateCards(
                            hero1 = finalH1,
                            hero2 = finalH2,
                            board = finalBoard,
                            opponents = finalOpponentsWithPositions,
                            profileBoxes = null,
                            updateProfileBoxes = false,
                            rawScannerBoxes = null,
                            potSize = potSize,
                            heroActionOptions = availableActionsList,
                            heroTurn = heroTurn,
                            heroStack = heroStack,
                            heroBet = heroBet,
                            tablePosition = heroPos,
                            smallBlind = sbVal,
                            bigBlind = bbVal,
                            tournamentStage = parsedStage,
                            isBbDisplay = isBbDisplay
                        )
                    )

                    scanStatus.value = "H:${if (finalH1 != null) 1 else 0}+${if (finalH2 != null) 1 else 0} C:${finalBoard.filterNotNull().size} Ops:${finalOpponentsWithPositions.size}<br>" +
                                       "Board: ${finalBoard.take(5).joinToString("") { it?.toHtmlString() ?: "[?]" }}"
                } 
                else if (pageType == "PLAYER_PROFILE") {
                    PokerHudSharedState.appScreenContext.value = AppScreenState.COINPOKER_PROFILE
                    RobotPlayer.availableActionButtons = emptyMap()
                    RobotPlayer.lobbyTransitionButtons = emptyMap()
                    RobotPlayer.sizingButtonsMap = emptyMap()
                    
                    val nick = jsonObj.get("nickname")?.asString ?: "Unknown"
                    val jsonStats = jsonObj.getAsJsonObject("stats")
                    if (nick != "Unknown" && jsonStats != null) {
                        val prefsManager = PreferencesManager(context)
                        val existing = prefsManager.loadPlayerStats(nick)
                        val updated = existing.copy(
                            histVpip = jsonStats.get("histVpip")?.asFloat ?: existing.histVpip,
                            histPfr = jsonStats.get("histPfr")?.asFloat ?: existing.histPfr,
                            hist3Bet = jsonStats.get("hist3Bet")?.asFloat ?: existing.hist3Bet,
                            histFoldTo3Bet = jsonStats.get("histFoldTo3Bet")?.asFloat ?: existing.histFoldTo3Bet,
                            histCBet = jsonStats.get("histCBet")?.asFloat ?: existing.histCBet,
                            histFoldToCBet = jsonStats.get("histFoldToCBet")?.asFloat ?: existing.histFoldToCBet,
                            histSteal = jsonStats.get("histSteal")?.asFloat ?: existing.histSteal,
                            histCheckRaise = jsonStats.get("histCheckRaise")?.asFloat ?: existing.histCheckRaise,
                            histWtsd = jsonStats.get("histWtsd")?.asFloat ?: existing.histWtsd,
                            histWsd = jsonStats.get("histWsd")?.asFloat ?: existing.histWsd,
                            lastUpdated = System.currentTimeMillis()
                        )
                        prefsManager.savePlayerStats(updated)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Profile parsed: $nick", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    scanStatus.value = "Агент-Сторож: Открыт профиль игрока $nick."
                }
                else {
                    // Unknown or Lobby screen handling
                    PokerHudSharedState.appScreenContext.value = AppScreenState.COINPOKER_LOBBY
                    val description = jsonObj.get("description")?.asString ?: "Unknown Screen"
                    
                    val transitionButtonsMap = mutableMapOf<String, android.graphics.Rect>()
                    transitionButtonsMap["JOIN_BACK"] = android.graphics.Rect(
                        (width * 0.35).toInt(), (height * 0.65).toInt(),
                        (width * 0.65).toInt(), (height * 0.73).toInt()
                    )
                    transitionButtonsMap["TAKE_SEAT"] = android.graphics.Rect(
                        (width * 0.40).toInt(), (height * 0.45).toInt(),
                        (width * 0.60).toInt(), (height * 0.53).toInt()
                    )
                    transitionButtonsMap["OK"] = android.graphics.Rect(
                        (width * 0.40).toInt(), (height * 0.55).toInt(),
                        (width * 0.60).toInt(), (height * 0.63).toInt()
                    )
                    transitionButtonsMap["PLAY"] = android.graphics.Rect(
                        (width * 0.35).toInt(), (height * 0.75).toInt(),
                        (width * 0.65).toInt(), (height * 0.83).toInt()
                    )

                    RobotPlayer.availableActionButtons = emptyMap()
                    RobotPlayer.sizingButtonsMap = emptyMap()
                    RobotPlayer.lobbyTransitionButtons = transitionButtonsMap

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
                    scanStatus.value = "Агент-Сторож: $description"
                }
            } else if (result is NimResult.Error) {
                scanStatus.value = "NVIDIA NIM API Error: ${result.message}"
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenScanner", "Process Error", e)
            scanStatus.value = "Scan Error: ${e.message}"
        } finally {
            try { image?.close() } catch(ignored: Exception) {}
        }
        return true
    }

    private fun parseCardString(cardStr: String?): Card? {
        if (cardStr == null || cardStr.length < 2) return null
        val rankChar = cardStr[0].uppercaseChar()
        val suitChar = cardStr[1].lowercaseChar()
        val rank = when (rankChar) {
            '2' -> Rank.TWO
            '3' -> Rank.THREE
            '4' -> Rank.FOUR
            '5' -> Rank.FIVE
            '6' -> Rank.SIX
            '7' -> Rank.SEVEN
            '8' -> Rank.EIGHT
            '9' -> Rank.NINE
            'T' -> Rank.TEN
            'J' -> Rank.JACK
            'Q' -> Rank.QUEEN
            'K' -> Rank.KING
            'A' -> Rank.ACE
            else -> return null
        }
        val suit = when (suitChar) {
            's', '♠' -> Suit.SPADES
            'h', '♥' -> Suit.HEARTS
            'd', '♦' -> Suit.DIAMONDS
            'c', '♣' -> Suit.CLUBS
            else -> return null
        }
        return Card(rank, suit)
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
