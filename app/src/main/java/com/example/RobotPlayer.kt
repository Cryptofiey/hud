package com.example

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random

object RobotPlayer {
    
    val isRobotModeEnabled = MutableStateFlow(false)
    
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // Map of Action Name (e.g. "FOLD", "CALL", "CHECK", "BET", "RAISE") to its screen Rect
    var availableActionButtons = emptyMap<String, Rect>()
    var autoPlayDelayMs = 1500L
    
    // Keep track of last action to avoid spamming the same action multiple times per turn
    private var lastActedBoardCards = 0
    private var lastActedAction = ""
    
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            PokerHudSharedState.uiState.collectLatest { uiState ->
                if (!isRobotModeEnabled.value) return@collectLatest
                if (AutoPlayerService.instance == null) {
                    Log.d("RobotPlayer", "AutoPlayerService not running!")
                    return@collectLatest
                }
                
                // If there are no action buttons detected, it's not our turn
                if (availableActionButtons.isEmpty()) {
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
                
                // If the board hasn't changed and we already did this action, don't spam.
                // Wait, if we act, the action buttons will disappear in the next frame.
                // But just in case, we add a check.
                val commCardsSize = uiState.board.filterNotNull().size
                val checkSignature = "${commCardsSize}_${canonicalAction}"
                if (lastActedBoardCards == commCardsSize && lastActedAction == canonicalAction) {
                    // Already acted for this phase
                    return@collectLatest
                }

                // Match with available rectangles
                // E.g., if canonicalAction is "CALL", look for "CALL" or "CHECK" in map.
                val targetRect = findButtonRect(canonicalAction) ?: return@collectLatest
                
                // Execute after humanizer delay
                lastActedBoardCards = commCardsSize
                lastActedAction = canonicalAction
                
                Log.d("RobotPlayer", "Executing AI Action: $canonicalAction on rectangle $targetRect")
                
                delay(calculateHumanDelay(canonicalAction))
                
                executeClickOnRect(targetRect)
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun findButtonRect(canonicalAction: String): Rect? {
        // Buttons could have names like "Check/Call" depending on how OCR sees them.
        for ((key, rect) in availableActionButtons) {
            val upperKey = key.uppercase()
            if (upperKey.contains(canonicalAction)) return rect
            // Synonyms
            if ((canonicalAction == "CALL" || canonicalAction == "CHECK") && 
                (upperKey.contains("CALL") || upperKey.contains("CHECK"))) return rect
            if ((canonicalAction == "BET" || canonicalAction == "RAISE") && 
                (upperKey.contains("BET") || upperKey.contains("RAISE"))) return rect
             if (canonicalAction == "ALL-IN" && upperKey.contains("ALL")) return rect
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

        val clickDuration = Random.nextLong(60, 150) // human-like tap
        
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
}
