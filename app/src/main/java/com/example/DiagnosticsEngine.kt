package com.example

import android.util.Log

object DiagnosticsEngine {
    fun runDiagnostics(state: PokerUiState, 
                       recL1: Recommendation?, 
                       recL2: Recommendation?, 
                       recL3: Recommendation?, 
                       recL4: Recommendation?) {
        
        val sb = StringBuilder()
        sb.appendLine("================ DIAGNOSTICS REPORT ================")
        
        // 1. Raw Data Inputs
        sb.appendLine("RAW INPUTS:")
        sb.appendLine("PotSize: ${state.potSize}, HeroBet: ${state.heroBet}")
        val activeOpponents = state.opponents.filter { it.isActive }
        val maxOpponentBet = activeOpponents.maxOfOrNull { if (state.settings.usePip) it.betSize else 0f } ?: 0f
        val betToCall = maxOf(0f, maxOpponentBet - state.heroBet)
        val activePot = state.potSize + activeOpponents.sumOf { if (state.settings.usePip) it.betSize.toDouble() else 0.0 }.toFloat() + state.heroBet
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        sb.appendLine("BetToCall: $betToCall | ActivePot: $activePot | PotOdds(Raw): ${potOdds * 100}%")
        
        // 2. Simulation Results
        val res = state.simulationResult
        val advRes = state.advancedSimulationResult
        sb.appendLine("SIMULATION (L1/L2): Win%=${res?.heroWinPct ?: 0f}, Tie%=${res?.heroTiePct ?: 0f}")
        sb.appendLine("SIMULATION (L3/L4): Win%=${advRes?.heroWinPct ?: 0f}, Tie%=${advRes?.heroTiePct ?: 0f}")
        
        if (res != null && advRes != null) {
            val diff = Math.abs(res.heroWinPct - advRes.heroWinPct)
            if (diff > 5.0f) {
                sb.appendLine("⚠️ WARNING: Massive deviation betwen base Sim and Advanced Sim ($diff%)")
            }
        }

        // 3. Compare with UI outputs 
        sb.appendLine("L1 HUD: ${recL1?.explanation}")
        sb.appendLine("L2 HUD: ${recL2?.explanation}")
        sb.appendLine("L3 HUD: ${recL3?.explanation}")
        sb.appendLine("L4 HUD: ${recL4?.explanation}")

        // 4. Thresholds mapping
        sb.appendLine("====================================================")
        BotLogSharedState.appendLogBot(sb.toString())
        Log.d("DiagnosticsEngine", sb.toString())
    }
}
