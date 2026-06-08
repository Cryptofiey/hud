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
    var autoPlayDelayMs = 1500L
    
    // Keep track of last action to avoid spamming the same action multiple times per turn
    private var lastActedBoardCards = 0
    private var lastActedAction = ""
    private var lastActionTime = 0L
    
    init {
        CoroutineScope(Dispatchers.Default).launch {
            PokerHudSharedState.uiState.collectLatest { uiState ->
                if (!isRobotModeEnabled.value) return@collectLatest
                if (AutoPlayerService.instance == null) {
                    return@collectLatest
                }
                
                // If there are no action buttons detected, it's not our turn
                if (availableActionButtons.isEmpty()) {
                    handleProfileScanning(uiState)
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
            if ((canonicalAction == "CALL" || canonicalAction == "CHECK") && 
                (upperKey.contains("CALL") || upperKey.contains("CHECK") || upperKey.contains("КОЛЛ") || upperKey.contains("ЧЕК"))) return rect
            if ((canonicalAction == "BET" || canonicalAction == "RAISE") && 
                (upperKey.contains("BET") || upperKey.contains("RAISE") || upperKey.contains("БЕТ") || upperKey.contains("РЕЙЗ"))) return rect
        }
        return null
    }

    private fun executeClickOnRect(rect: Rect) {
        val autoPlayer = AutoPlayerService.instance ?: return
        
        // Randomize point inside the button rectangle (staying mostly central)
        val insetX = rect.width() * 0.15f
        val insetY = rect.height() * 0.15f
        val startX = rect.left + insetX
        val endX = rect.right - insetX
        val startY = rect.top + insetY
        val endY = rect.bottom - insetY

        val x = startX + Random.nextFloat() * (endX - startX)
        val y = startY + Random.nextFloat() * (endY - startY)

        val clickDuration = Random.nextLong(100, 250) // longer tap which passes touchslop
        
        autoPlayer.dispatchClick(x, y, clickDuration)
    }

    private fun calculateHumanDelay(action: String): Long {
        // Base delay
        val base = autoPlayDelayMs
        // Variation
        val variation = Random.nextLong(-300, 800)
        
        // Add artificial thinking time for harder decisions like raises
        val thinkTime = if (action == "RAISE" || action == "БЕТ") Random.nextLong(1000, 2500) else 0L
        
        return maxOf(500L, base + variation + thinkTime)
    }

    private var isProfileScanningActive = false
    private val scannedPositions = mutableSetOf<String>()

    private fun handleProfileScanning(uiState: PokerUiState) {
        if (isProfileScanningActive) return
        val opponents = uiState.opponents
        if (opponents.isEmpty()) return

        // Pick an opponent whose bounding box we haven't scanned recently.
        val unscannedOpponent = opponents.firstOrNull { opp ->
            val box = opp.boundingBox
            if (box == null) false else {
                val key = "${box.left / 10}_${box.top / 10}"
                !scannedPositions.contains(key)
            }
        }

        if (unscannedOpponent != null) {
            val box = unscannedOpponent.boundingBox!!
            val key = "${box.left / 10}_${box.top / 10}"
            
            isProfileScanningActive = true
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    scannedPositions.add(key)
                    if (scannedPositions.size > 20) scannedPositions.clear() // Prevent memory bloat
                    
                    // 1. Click on opponent avatar to open profile
                    BotLogSharedState.appendLogBot("[BOT][L5] Clicking avatar to open profile of player ${unscannedOpponent.nickname}")
                    executeClickOnRect(box)
                    
                    // 2. Wait for profile dialog to animate and open (around 1000ms is safe)
                    delay(1200)
                    
                    // 3. Trigger scan bypassing HUD buttons
                    PokerHudSharedState.triggerProfileScan.value = true 
                    BotLogSharedState.appendLogBot("[BOT][L5] Triggered profile screen scan directly")
                    
                    // wait for scan to process
                    delay(1200)
                    
                    // 4. Close the profile. We can click near the top of the screen
                    val displayMetrics = AutoPlayerService.instance?.resources?.displayMetrics
                    val screenW = displayMetrics?.widthPixels ?: 1000
                    val screenH = displayMetrics?.heightPixels ?: 2000
                    
                    val closeX = screenW * 0.5f
                    val closeY = screenH * 0.1f // Top area clicks outside of bounds, closes popups
                    
                    BotLogSharedState.appendLogBot("[BOT][L5] Closing profile by clicking outside: $closeX, $closeY")
                    AutoPlayerService.instance?.dispatchClick(closeX, closeY, 150)
                    
                    delay(1000) // Cooldown before next
                } catch (e: Exception) {
                    Log.e("RobotPlayer", "Error during profile scan", e)
                } finally {
                    isProfileScanningActive = false
                }
            }
        }
    }
}
