package com.example

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random

object RobotPlayer {
    
    val isRobotModeEnabled = MutableStateFlow(false)
    
    // Map of Action Name (e.g. "FOLD", "CALL", "CHECK", "BET", "RAISE") to its screen Rect
    var availableActionButtons = emptyMap<String, Rect>()
    var lobbyTransitionButtons = emptyMap<String, Rect>()
    var autoPlayDelayMs = 1500L
    
    // Keep track of last action to avoid spamming the same action multiple times per turn
    private var lastActedBoardCards = 0
    private var lastActedAction = ""
    private var lastActionTime = 0L
    
    init {
        // Background loop for Lobby / Table Buy-in Transition clicks
        CoroutineScope(Dispatchers.Default).launch {
            val lastClickedTimeMap = mutableMapOf<String, Long>()
            while (isActive) {
                delay(800) // check every 800ms
                if (!isRobotModeEnabled.value) continue
                if (AutoPlayerService.instance == null) continue
                
                // If we have active game table action buttons, we shouldn't click random lobby buttons!
                if (availableActionButtons.isNotEmpty()) continue
                
                val currentLobbyButtons = lobbyTransitionButtons
                if (currentLobbyButtons.isEmpty()) continue
                
                // Priority scan of transition keys
                val priorityKeys = listOf("OK", "CONFIRM", "BUY_IN", "TAKE_SEAT", "PLAY", "JOIN", "REGISTER")
                val now = System.currentTimeMillis()
                
                for (key in priorityKeys) {
                    val rect = currentLobbyButtons[key] ?: continue
                    
                    // Throttling: avoid clicking the exact same transition state faster than once per 5 seconds
                    val lastClicked = lastClickedTimeMap[key] ?: 0L
                    if (now - lastClicked < 5000L) {
                        continue // wait for screen transition or server processing
                    }
                    
                    Log.d("RobotPlayer", "[LOBBY] Found transition button: $key inside $rect. Executing transition click.")
                    BotLogSharedState.appendLogBot("[BOT][L5] Transition/Lobby action: click on '$key' to navigate to game table")
                    
                    // Human delay before transition action
                    delay(Random.nextLong(200, 700))
                    executeClickOnRect(rect)
                    
                    lastClickedTimeMap[key] = System.currentTimeMillis()
                    break // perform only 1 action per tick to avoid fast multi-click errors
                }
            }
        }

        CoroutineScope(Dispatchers.Default).launch {
            PokerHudSharedState.uiState.collectLatest { uiState ->
                if (!isRobotModeEnabled.value) return@collectLatest
                if (AutoPlayerService.instance == null) {
                    return@collectLatest
                }
                
                // If there are no action buttons detected, it's not our turn
                if (availableActionButtons.isEmpty()) {
                    if (PokerHudSharedState.isAutoProfileScanningEnabled.value) {
                        handleProfileScanning(uiState)
                    }
                    return@collectLatest
                }

                val advRec = uiState.advancedRecommendation
                val rec = uiState.recommendation
                val advisorText = ((advRec ?: rec)?.action ?: "").uppercase()
                
                if (advisorText.isEmpty() || advisorText.contains("N/A", ignoreCase=true)) return@collectLatest
                
                // Parse the advice.
                val targetActionRaw = advisorText
                
                // Attempt to map Advisor terms to canonical
                val canonicalAction = when {
                    targetActionRaw.contains("ФОЛД") || targetActionRaw.contains("FOLD") -> "FOLD"
                    targetActionRaw.contains("ЧЕК") || targetActionRaw.contains("CHECK") -> "CHECK"
                    targetActionRaw.contains("КОЛЛ") || targetActionRaw.contains("CALL") -> "CALL"
                    targetActionRaw.contains("БЕТ") || targetActionRaw.contains("BET") -> "BET"
                    targetActionRaw.contains("РЕЙЗ") || targetActionRaw.contains("RAISE") -> "RAISE"
                    targetActionRaw.contains("ОЛЛ-ИН") || targetActionRaw.contains("ALL-IN") -> "ALL-IN"
                    else -> ""
                }

                if (canonicalAction.isEmpty()) return@collectLatest
                
                // If the board hasn't changed and we already did this action within the last 8 seconds, don't spam.
                // It accounts for multi-action streets where action might return to us after > 8 seconds.
                val commCardsSize = uiState.board.filterNotNull().size
                val heroStr = (uiState.heroCard1?.toString() ?: "") + (uiState.heroCard2?.toString() ?: "")
                val exactOptions = availableActionButtons.keys.sorted().joinToString(",")
                val signature = "${heroStr}_${commCardsSize}_${canonicalAction}_${exactOptions}"
                val now = System.currentTimeMillis()
                
                if (lastActedAction == signature && (now - lastActionTime < 8000L)) {
                    // Already acted for this phase recently
                    return@collectLatest
                }

                // Match with available rectangles
                // E.g., if canonicalAction is "CALL", look for "CALL" or "CHECK" in map.
                val targetRect = findButtonRect(canonicalAction) ?: return@collectLatest
                
                // Execute after humanizer delay
                lastActedAction = signature
                lastActedBoardCards = commCardsSize
                lastActionTime = now
                
                Log.d("RobotPlayer", "Executing AI Action: $canonicalAction on rectangle $targetRect (sig: $signature)")
                
                CoroutineScope(Dispatchers.Default).launch {
                    delay(calculateHumanDelay(canonicalAction))
                    executeClickOnRect(targetRect)
                }
            }
        }
    }
    
    fun start() {
        // Kept for backward compatibility if called elsewhere, but no longer needed since it starts automatically in init.
        isRobotModeEnabled.value = true
    }
    
    fun stop() {
        // Kept for backward compatibility if called elsewhere
        isRobotModeEnabled.value = false
    }

    private fun findButtonRect(canonicalAction: String): Rect? {
        // Buttons could have names like "Check/Call" depending on how OCR sees them.
        for ((key, rect) in availableActionButtons) {
            val upperKey = key.uppercase()
            
            // Check direct matches in both English and Russian
            val match = when (canonicalAction) {
                "FOLD" -> upperKey.contains("FOLD") || upperKey.contains("ФОЛД")
                "CHECK" -> upperKey.contains("CHECK") || upperKey.contains("ЧЕК")
                "CALL" -> upperKey.contains("CALL") || upperKey.contains("КОЛЛ")
                "BET", "RAISE" -> upperKey.contains("BET") || upperKey.contains("БЕТ") || 
                                  upperKey.contains("RAISE") || upperKey.contains("РЕЙЗ") || 
                                  upperKey.contains("CONFIRM") || upperKey.contains("ПОДТВЕРДИТЬ")
                "ALL-IN" -> upperKey.contains("ALL-IN") || upperKey.contains("ОЛЛ-ИН") || upperKey.contains("ALL") || upperKey.contains("ОЛЛ")
                else -> false
            }
            if (match) return rect

            // Synonyms
            if (canonicalAction == "FOLD" && (upperKey.contains("CHECK") || upperKey.contains("ЧЕК"))) return rect
            if ((canonicalAction == "CALL" || canonicalAction == "CHECK") && 
                (upperKey.contains("CALL") || upperKey.contains("CHECK") || upperKey.contains("КОЛЛ") || upperKey.contains("ЧЕК"))) return rect
            if ((canonicalAction == "BET" || canonicalAction == "RAISE") && 
                (upperKey.contains("BET") || upperKey.contains("RAISE") || upperKey.contains("БЕТ") || upperKey.contains("РЕЙЗ"))) return rect
        }
        return null
    }

    private val gaussianGenerator = java.util.Random()

    private fun executeClickOnRect(rect: Rect) {
        val autoPlayer = AutoPlayerService.instance ?: return
        
        // Calculate the central coordinates
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        
        // Standard deviation scaled to 10% of width and height to form a dense central cluster
        val stdDevX = rect.width() * 0.10f
        val stdDevY = rect.height() * 0.10f
        
        // Generate coordinates using Gaussian (Normal) distribution around the center
        val rawX = centerX + (gaussianGenerator.nextGaussian() * stdDevX).toFloat()
        val rawY = centerY + (gaussianGenerator.nextGaussian() * stdDevY).toFloat()
        
        // Strictly clamp within safe coordinates inside the button boundaries (minimum 4px margin)
        val x = rawX.coerceIn(rect.left.toFloat() + 4f, rect.right.toFloat() - 4f)
        val y = rawY.coerceIn(rect.top.toFloat() + 4f, rect.bottom.toFloat() - 4f)

        // Longer tap (120ms to 240ms) with slight randomness to pass touchslop and emulate human pressure time
        val clickDuration = Random.nextLong(120, 240) 
        
        autoPlayer.dispatchClick(x, y, clickDuration)
    }

    private fun calculateHumanDelay(action: String): Long {
        val base = autoPlayDelayMs
        
        // Model distinct action delay profiles mimicking biological human reaction times and pondering
        return when (action.uppercase(java.util.Locale.US)) {
            "FOLD" -> {
                // Folds are generally fast decisions or quick reactions
                val variation = Random.nextLong(400, 1500)
                maxOf(400L, base - 500L + variation)
            }
            "CHECK" -> {
                // Checks can be fast or involve brief pause
                val variation = Random.nextLong(600, 1800)
                maxOf(500L, base - 300L + variation)
            }
            "CALL" -> {
                // Calls require more assessment of pot odds and board texture
                val variation = Random.nextLong(1000, 2800)
                maxOf(1000L, base + variation)
            }
            "BET", "RAISE", "ALL-IN" -> {
                // Big/active bets involve sizing calculation and psychological stress
                val variation = Random.nextLong(2000, 4800)
                maxOf(1800L, base + 800L + variation)
            }
            else -> {
                val variation = Random.nextLong(800, 2000)
                maxOf(600L, base + variation)
            }
        }
    }

    private var isProfileScanningActive = false
    private val scannedPositions = mutableSetOf<String>()
    private val scannedNicknames = mutableSetOf<String>()
    private var lastProfileScanTime = 0L
    private val PROFILE_SCAN_COOLDOWN_MS = 25000L // 25 seconds cooldown between scans

    private fun handleProfileScanning(uiState: PokerUiState) {
        if (isProfileScanningActive) return
        
        val now = System.currentTimeMillis()
        if (now - lastProfileScanTime < PROFILE_SCAN_COOLDOWN_MS) return

        val opponents = uiState.opponents
        if (opponents.isEmpty()) return

        // Pick an opponent whose bounding box or nickname we haven't scanned recently.
        val unscannedOpponent = opponents.firstOrNull { opp ->
            val box = opp.boundingBox
            val name = opp.nickname
            if (box == null) false else {
                val spatialKey = "${box.left / 50}_${box.top / 50}"
                val hasScannedName = name.isNotEmpty() && name != "Unknown" && scannedNicknames.contains(name)
                val hasScannedPosition = scannedPositions.contains(spatialKey)
                !hasScannedName && !hasScannedPosition
            }
        }

        if (unscannedOpponent != null) {
            val box = unscannedOpponent.boundingBox!!
            val name = unscannedOpponent.nickname
            val spatialKey = "${box.left / 50}_${box.top / 50}"
            
            isProfileScanningActive = true
            lastProfileScanTime = now
            
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    if (name.isNotEmpty() && name != "Unknown") {
                        scannedNicknames.add(name)
                    }
                    scannedPositions.add(spatialKey)
                    
                    if (scannedNicknames.size > 40) scannedNicknames.clear() // Prevent memory leaks
                    if (scannedPositions.size > 40) scannedPositions.clear()
                    
                    // 1. Click on opponent avatar to open profile
                    BotLogSharedState.appendLogBot("[BOT][L5] Opening profile of player: ${unscannedOpponent.nickname}")
                    executeClickOnRect(box)
                    
                    // 2. Wait for profile dialog to animate and open (around 1200ms is safe)
                    delay(1200)
                    
                    // 3. Trigger scan bypassing HUD buttons
                    PokerHudSharedState.triggerProfileScan.value = true 
                    BotLogSharedState.appendLogBot("[BOT][L5] Scanning profile stats...")
                    
                    // wait for scan to process
                    delay(1300)
                    
                    // 4. Close the profile. First try the robust global BACK action
                    BotLogSharedState.appendLogBot("[BOT][L5] Closing profile dialog via SYSTEM BACK action")
                    val serviceInstance = AutoPlayerService.instance
                    val backSuccess = serviceInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                    
                    delay(600) // wait for animation
                    
                    // Fallback to coordinates click if back button wasn't handled or service was disconnected
                    if (!backSuccess && serviceInstance != null) {
                        val displayMetrics = serviceInstance.resources?.displayMetrics
                        val screenW = displayMetrics?.widthPixels ?: 1000
                        val screenH = displayMetrics?.heightPixels ?: 2000
                        
                        // Click in a peripheral safe margin outside standard modal bounds (e.g., top-left corner)
                        val closeX = screenW * 0.05f
                        val closeY = screenH * 0.05f
                        
                        BotLogSharedState.appendLogBot("[BOT][L5] Fallback: Closing profile by clicking margin: $closeX, $closeY")
                        serviceInstance.dispatchClick(closeX, closeY, 150)
                    }
                    
                    delay(1000) // Cooldown before next loop invocation
                } catch (e: Exception) {
                    Log.e("RobotPlayer", "Error during profile scan", e)
                } finally {
                    isProfileScanningActive = false
                }
            }
        }
    }
}
