package com.example

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

object AdvisorEngine {
    fun getSklanskyRangeForVpip(vpip: Float): Int {
        return when {
            vpip <= 5f -> 1
            vpip <= 12f -> 2
            vpip <= 20f -> 3
            vpip <= 30f -> 4
            vpip <= 45f -> 5
            vpip <= 60f -> 6
            vpip <= 80f -> 7
            else -> 8
        }
    }

    // Helper: Determine Sklansky & Malmuth Hand Group for two cards
    private fun getPositionalHazard(position: TablePosition, isPreflop: Boolean): Float {
        return when(position) {
            TablePosition.UTG -> if (isPreflop) 0.05f else 0.02f
            TablePosition.MP -> if (isPreflop) 0.03f else 0.01f
            TablePosition.CO -> if (isPreflop) 0.01f else 0.0f
            TablePosition.BTN -> 0.0f
            TablePosition.SB -> if (isPreflop) 0.02f else 0.04f
            TablePosition.BB -> if (isPreflop) 0.0f else 0.02f
        }
    }

    private fun getTargetPotOdds(basePotOdds: Float, betToCall: Float, heroStack: Float, activeOpponentsCount: Int, position: TablePosition, isPreflop: Boolean, stage: TournamentStage, sklanskyGroup: Int, bigBlind: Float = 0f): Float {
        val mRatio = if (heroStack > 0 && bigBlind > 0) heroStack / bigBlind else 20f
        
        val stackRisk = if (heroStack > 0) (betToCall / heroStack).coerceIn(0f, 1f) else 0f
        val shortStackFactor = (mRatio / 20f).coerceIn(0.1f, 1.0f)
        val stackRiskMargin = stackRisk * 0.08f * shortStackFactor
        
        // Penalize for multiple active opponents (squeeze hazard & multiway dilution)
        val multiwayHazard = if (activeOpponentsCount > 1) {
            (activeOpponentsCount - 1) * 0.005f
        } else 0f

        val positionalHazard = getPositionalHazard(position, isPreflop) * 0.4f
        
        // Stage & Bubble Hazard (Tighter ranges as game progresses, but less so if we are short stacked)
        var stageHazard = if (isPreflop) {
            when (stage) {
                TournamentStage.EARLY -> 0.0f 
                TournamentStage.MIDDLE -> {
                    if (sklanskyGroup >= 6) 0.02f
                    else if (sklanskyGroup >= 5) 0.01f
                    else 0.0f
                }
                TournamentStage.LATE -> {
                    when {
                        sklanskyGroup >= 7 -> 0.05f
                        sklanskyGroup >= 4 -> 0.03f
                        else -> 0.01f 
                    }
                }
            }
        } else {
            when (stage) {
                TournamentStage.EARLY -> 0.0f
                TournamentStage.MIDDLE -> 0.01f
                TournamentStage.LATE -> 0.03f 
            }
        }
        
        stageHazard *= shortStackFactor

        // --- CHEAP FLOP FIX ---
        if (isPreflop && betToCall > 0f && bigBlind > 0f && betToCall <= bigBlind * 2.5f) {
            stageHazard = 0.0f 
            stageHazard -= 0.02f 
        }

        return basePotOdds + stackRiskMargin + multiwayHazard + positionalHazard + stageHazard
    }

    fun getSklanskyGroup(card1: Card, card2: Card): Int {
        val r1 = maxOf(card1.rank.value, card2.rank.value)
        val r2 = minOf(card1.rank.value, card2.rank.value)
        val suited = card1.suit == card2.suit
        val pair = r1 == r2

        return when {
            pair -> when (r1) {
                14, 13, 12, 11 -> 1 // AA, KK, QQ, JJ
                10 -> 2            // TT
                9 -> 3             // 99
                8 -> 4             // 88
                7 -> 5             // 77
                6, 5 -> 6          // 66, 55
                4, 3, 2 -> 7       // 44, 33, 22
                else -> 8
            }
            suited -> when {
                r1 == 14 && r2 == 13 -> 1 // AKs
                r1 == 14 && r2 == 12 -> 2 // AQs
                r1 == 14 && r2 == 11 -> 2 // AJs
                r1 == 13 && r2 == 12 -> 2 // KQs
                r1 == 14 && r2 == 10 -> 3 // ATs
                r1 == 12 && r2 == 11 -> 3 // QJs
                r1 == 13 && r2 == 11 -> 3 // KJs
                r1 == 11 && r2 == 10 -> 3 // JTs
                r1 == 14 && r2 >= 2 -> 4   // A9s-A2s
                r1 == 13 && r2 == 10 -> 4 // KTs
                r1 == 12 && r2 == 10 -> 4 // QTs
                r1 == 10 && r2 == 9 -> 4  // T9s
                r1 == 9 && r2 == 8 -> 4   // 98s
                r1 == 8 && r2 == 7 -> 4   // 87s
                else -> 6
            }
            else -> when {
                r1 == 14 && r2 == 13 -> 2 // AKo
                r1 == 14 && r2 == 12 -> 3 // AQo
                r1 == 14 && r2 == 11 -> 4 // AJo
                r1 == 13 && r2 == 12 -> 4 // KQo
                r1 == 14 && r2 == 10 -> 5 // ATo
                r1 == 13 && r2 == 11 -> 5 // KJo
                r1 == 12 && r2 == 11 -> 5 // QJo
                r1 == 11 && r2 == 10 -> 5 // JTo
                r1 == 13 && r2 == 10 -> 6 // KTo
                r1 == 12 && r2 == 10 -> 6 // QTo
                r1 == 10 && r2 == 9 -> 7  // T9o
                else -> 8
            }
        }
    }

    // Mathematical Chen Formula Score for Pocket Cards Evaluation
    fun getChenScore(card1: Card, card2: Card): Float {
        val r1 = maxOf(card1.rank.value, card2.rank.value)
        val r2 = minOf(card1.rank.value, card2.rank.value)
        val suited = card1.suit == card2.suit
        val pair = r1 == r2

        // 1. Base points based on the highest card
        var points = when (r1) {
            14 -> 10.0f // Ace
            13 -> 8.0f  // King
            12 -> 7.0f  // Queen
            11 -> 6.0f  // Jack
            else -> r1.toFloat() / 2.0f
        }

        // 2. Adjustments for pocket pairs
        if (pair) {
            points *= 2.0f
            if (points < 5.0f) points = 5.0f // Minimum pair score threshold
        }

        // 3. Suited cards bonus
        if (suited) {
            points += 2.0f
        }

        // 4. Closeness gap penalty
        val gap = r1 - r2 - 1
        if (gap > 0) {
            val penalty = when (gap) {
                1 -> -1.0f
                2 -> -2.0f
                3 -> -4.0f
                else -> -5.0f
            }
            points += penalty
        }

        // 5. Straight connector bonus (small adjustments for no-gap/1-gap and card < Q)
        if (!pair && gap <= 1 && r1 < 12) {
            points += 1.0f
        }

        // Standard maximum Chen points is 20 (AAs). Normalize points from [-5.0f, 20.0f] range to [0.0f, 1.0f] range
        return (points / 20.0f).coerceIn(0.0f, 1.0f)
    }

    // 4. Level 1 (L1) Bare GTO Mathematical Recommendation (Extracts truth from 4 independent sources)
    fun computeRecommendation(
        heroCard1: Card?,
        heroCard2: Card?,
        board: List<Card?>,
        potSize: Float,
        heroBet: Float,
        opponents: List<OpponentState>,
        activeOpponentsCount: Int,
        simResult: SimulationResult?,
        settings: AdvisorSettings,
        position: TablePosition,
        stage: TournamentStage,
        smallBlind: Float,
        bigBlind: Float,
        heroStack: Float,
        lastActions: String = "",
        heroActionOptions: List<String> = emptyList(),
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation(
                action = "WAIT",
                confidence = 100f,
                explanation = "Wait cards."
            )
        }

        // 1. Source 1: Monte Carlo Simulation clean equity (EV_Sim)
        val s1 = (simResult?.heroWinPct ?: 0f) / 100f

        // 2. Source 2: Sklansky-Malmuth Group EV (EV_Sklansky)
        val sklanskyGroup = getSklanskyGroup(heroCard1, heroCard2)
        val s2 = when (sklanskyGroup) {
            1 -> 1.0f
            2 -> 0.9f
            3 -> 0.8f
            4 -> 0.65f
            5 -> 0.5f
            6 -> 0.35f
            7 -> 0.2f
            else -> 0.1f
        }

        // 3. Source 3: Mathematical Chen Formula Score (EV_Chen)
        val s3 = getChenScore(heroCard1, heroCard2)

        // Calculate maximum opponent bet & pots
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) {
                maxOpponentBet = pBet
            }
        }
        var betToCall = maxOf(0f, maxOpponentBet - heroBet)
        
        // --- OCR FIX FOR MISSED BETS ---
        if (betToCall == 0f && heroActionOptions.isNotEmpty()) {
            val hasCheck = heroActionOptions.any { it.equals("Check", true) }
            val hasCall = heroActionOptions.any { it.equals("Call", true) }
            val hasAllIn = heroActionOptions.any { it.equals("All-in", true) }
            
            if (!hasCheck && (hasCall || hasAllIn)) {
                if (hasAllIn && !hasCall) betToCall = heroStack
                else betToCall = bigBlind.coerceAtLeast(1f) // Assumed minimal bet
            }
        }
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = if (potSize + totalOpponentBets + heroBet < betToCall && betToCall > 0) betToCall + (bigBlind * 1.5f) else potSize + totalOpponentBets + heroBet
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        val isPreflop = board.filterNotNull().isEmpty()
        val targetPotOdds = getTargetPotOdds(potOdds, betToCall, heroStack, activeOpponentsCount, position, isPreflop, stage, sklanskyGroup, bigBlind)

        // 4. Source 4: Pot Odds & Margin Factor (EV_OddsGap)
        val s4 = if (betToCall > 0f) {
            val gap = s1 - targetPotOdds
            ((gap + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            (s1 * 1.15f).coerceIn(0f, 1f)
        }

        // Pure GTO Math Core - clean of any psychological or position heuristics
        val l1Score = if (isPreflop) {
            (s1 * 0.35f) + (s2 * 0.25f) + (s3 * 0.20f) + (s4 * 0.20f)
        } else {
            (s1 * 0.80f) + (s4 * 0.20f)
        }

        val isMultiway = activeOpponentsCount >= 2
        val fairShare = if (activeOpponentsCount > 0) 1.0f / (activeOpponentsCount + 1.0f) else 0.5f
        val betThreshold = if (isPreflop) {
            if (isMultiway) 0.50f else 0.45f
        } else {
            fairShare + if (isMultiway) 0.10f else 0.05f
        }
        val raiseThreshold = if (isPreflop) {
            if (isMultiway) 0.65f else 0.60f
        } else {
            fairShare + if (isMultiway) 0.25f else 0.15f
        }

        val action: String
        val explanation: String
        val profitableCall = s1 > targetPotOdds

        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)
        fun fmt(f: Float): String = String.format(Locale.US, "%.1f", f)

        val mRatioRaw = if (heroStack > 0 && bigBlind > 0) (heroStack.toFloat() / bigBlind) else 20.0f
        val mRatio = if (heroStack > 0 && isBbDisplay) heroStack else mRatioRaw

        if (betToCall > 0) {
            if (profitableCall) {
                if (s1 > 0.65f && l1Score > raiseThreshold) {
                    if (mRatio < 12.0f) {
                        action = "ALL-IN"
                        explanation = "ALL-IN: excellent EV ${pct(s1)}% [M=${fmt(mRatio)}]"
                    } else {
                        action = "RAISE"
                        explanation = "Raise: strong value equity ${pct(s1)}%"
                    }
                } else {
                    action = "CALL"
                    explanation = "Call: equity ${pct(s1)}% > odds ${pct(targetPotOdds)}% (with margin)"
                }
            } else {
                // Unprofitable call based on raw win percentage. Check if playability score offers GTO defense
                if (l1Score > 0.48f && s1 > (0.9f / (activeOpponentsCount + 1.0f))) {
                    if (s1 > 0.45f && l1Score > raiseThreshold) {
                        action = "RAISE"
                        explanation = "Raise (semi-bluff): equity ${pct(s1)}%"
                    } else {
                        action = "CALL"
                        explanation = "Call (defense): equity ${pct(s1)}%"
                    }
                } else {
                    action = "FOLD"
                    explanation = "Fold: weak equity ${pct(s1)}% < odds ${pct(potOdds)}%"
                }
            }
        } else {
            // No bet to call (betToCall == 0) -> Check/Bet/Raise solver
            if (s1 > 0.60f && l1Score > raiseThreshold) {
                if (mRatio < 12.0f && (s1 > 0.80f || sklanskyGroup <= 2)) {
                    action = "ALL-IN"
                    explanation = "ALL-IN: excellent push, equity ${pct(s1)}%"
                } else {
                    action = "RAISE"
                    explanation = "Raise: value aggression, equity ${pct(s1)}%"
                }
            } else if (s1 > 0.45f && l1Score > betThreshold) {
                action = "BET"
                explanation = "Bet: math advantage, equity ${pct(s1)}%"
            } else if (l1Score > 0.50f) {
                action = "BET"
                explanation = "Bet (bluff): equity ${pct(s1)}%"
            } else {
                action = "CHECK"
                explanation = "Check: pot control, equity ${pct(s1)}%"
            }
        }

        val breakdown = "Simulations (Branch 1): Base=${pct(s1)}%\n" +
                        "Sklansky (Branch 2): Grp=$sklanskyGroup, Wgt=${pct(s2)}%\n" +
                        "Chen Score (Branch 3): Wgt=${pct(s3)}%\n" +
                        "Pot Odds/Margin (Branch 4): Targets=${pct(targetPotOdds)}%, Adj=${pct(s4)}%\n" +
                        "Net Output: EV Average=${pct(l1Score)}%"
                        
        val detailExplanation = "⚙️ L1 MATH VETKI:\n$breakdown\n🎯 ACTION: $explanation"

        val confidence = when(action) {
            "RAISE", "ALL-IN", "BET" -> (((l1Score - 0.4f) / 0.6f) * 100f).coerceIn(10f, 98f)
            "FOLD" -> {
                val msg = "🚨 DECISION-LOG [FOLD L1]: EV = ${pct(l1Score)}%, Target PotOdds/Threshold = ${pct(targetPotOdds)}%. (s1=${pct(s1)}%). Reason: L1 threshold missed."
                BotLogSharedState.appendLogBot(msg)
                (((0.5f - l1Score) / 0.5f) * 100f).coerceIn(10f, 98f)
            }
            "CALL" -> ((1.0f - kotlin.math.abs(l1Score - 0.45f) / 0.55f) * 100f).coerceIn(10f, 98f)
            "CHECK" -> ((1.0f - kotlin.math.abs(l1Score - 0.35f) / 0.65f) * 100f).coerceIn(10f, 98f)
            else -> 50f
        }

        return Recommendation(action, confidence, detailExplanation, l1Score)
    }

    fun computeRecommendationL2(
        heroCard1: Card?,
        heroCard2: Card?,
        board: List<Card?>,
        potSize: Float,
        heroBet: Float,
        opponents: List<OpponentState>,
        activeOpponentsCount: Int,
        simResult: SimulationResult?,
        settings: AdvisorSettings,
        position: TablePosition,
        stage: TournamentStage,
        smallBlind: Float,
        bigBlind: Float,
        heroStack: Float,
        heroActionOptions: List<String> = emptyList(),
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("WAIT", 100f, "Wait cards.")
        }

        // 1. Core Source 1: Monte Carlo Simulation win pct (and check fallback to L1 elements)
        val s1 = (simResult?.heroWinPct ?: 0f) / 100f

        // Core Source 2: Sklansky-Malmuth Group EV
        val sklanskyGroup = getSklanskyGroup(heroCard1, heroCard2)
        val s2 = when (sklanskyGroup) {
            1 -> 1.0f
            2 -> 0.9f
            3 -> 0.8f
            4 -> 0.65f
            5 -> 0.5f
            6 -> 0.35f
            7 -> 0.2f
            else -> 0.1f
        }

        // Core Source 3: Mathematical Chen Score
        val s3 = getChenScore(heroCard1, heroCard2)

        // Calculate bets and Pot Odds elements
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) {
                maxOpponentBet = pBet
            }
        }
        var betToCall = maxOf(0f, maxOpponentBet - heroBet)
        
        // --- OCR FIX FOR MISSED BETS ---
        if (betToCall == 0f && heroActionOptions.isNotEmpty()) {
            val hasCheck = heroActionOptions.any { it.equals("Check", true) }
            val hasCall = heroActionOptions.any { it.equals("Call", true) }
            val hasAllIn = heroActionOptions.any { it.equals("All-in", true) }
            
            if (!hasCheck && (hasCall || hasAllIn)) {
                if (hasAllIn && !hasCall) betToCall = heroStack
                else betToCall = bigBlind.coerceAtLeast(1f) // Assumed minimal bet
            }
        }
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = if (potSize + totalOpponentBets + heroBet < betToCall && betToCall > 0) betToCall + (bigBlind * 1.5f) else potSize + totalOpponentBets + heroBet
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        
        val isPreflop = board.filterNotNull().isEmpty()
        val targetPotOdds = getTargetPotOdds(potOdds, betToCall, heroStack, activeOpponentsCount, position, isPreflop, stage, sklanskyGroup, bigBlind)

        // Core Source 4: Margin / Potential Odds Gap
        val s4 = if (betToCall > 0f) {
            val gap = s1 - targetPotOdds
            ((gap + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            (s1 * 1.15f).coerceIn(0f, 1f)
        }

        // Clean L1 GTO base score (the foundation we build on top of)
        val baseL1Score = if (isPreflop) {
            (s1 * 0.35f) + (s2 * 0.25f) + (s3 * 0.20f) + (s4 * 0.20f)
        } else {
            (s1 * 0.80f) + (s4 * 0.20f)
        }

        // 2. Average table stats for fallback strategy
        val opponentsWithStats = opponents.filter { it.stats != null }
        val avgTableVpip = if (opponentsWithStats.isNotEmpty()) {
            opponentsWithStats.map { it.stats?.histVpip ?: it.stats?.vpip ?: 30f }.average().toFloat()
        } else {
            28f
        }
        val avgTablePfr = if (opponentsWithStats.isNotEmpty()) {
            opponentsWithStats.map { it.stats?.histPfr ?: it.stats?.pfr ?: 18f }.average().toFloat()
        } else {
            18f
        }
        val avgTableWtsd = if (opponentsWithStats.isNotEmpty()) {
            opponentsWithStats.map { it.stats?.histWtsd ?: 30f }.average().toFloat()
        } else {
            30f
        }
        val avgTableWsd = if (opponentsWithStats.isNotEmpty()) {
            opponentsWithStats.map { it.stats?.histWsd ?: 50f }.average().toFloat()
        } else {
            50f
        }

        // 3. Loop through EACH active opponent to aggregate individual behavior adjustments
        val activeOpponents = opponents.filter { it.isActive }
        var totalDeltaAdj = 0f
        var totalFocusAdj = 0f
        var totalVpipAdj = 0f
        var countedOpponents = 0

        for (opp in activeOpponents) {
            val stats = opp.stats
            val hasProfile = stats?.histVpip != null
            val hasSession = stats != null && stats.handsPlayed > 2

            // Prioritize sessionVpip scanned from screen. Soften with histVpip if available.
            val screenVpip = opp.sessionVpip
            val profileVpip = stats?.histVpip
            val sessionVpipDb = if (hasSession) stats.vpip else null

            val vpip = if (screenVpip != null) {
                if (profileVpip != null) {
                    // Soften: 70% screen, 30% historical
                    (screenVpip * 0.7f) + (profileVpip * 0.3f)
                } else {
                    screenVpip
                }
            } else {
                profileVpip ?: sessionVpipDb ?: (25f * 0.4f + avgTableVpip * 0.6f)
            }

            val pfr = stats?.histPfr ?: sessionVpipDb?.let { stats?.pfr } ?: (15f * 0.4f + avgTablePfr * 0.6f)
            val wtsd = stats?.histWtsd ?: (30f * 0.4f + avgTableWtsd * 0.6f)
            val wsd = stats?.histWsd ?: (50f * 0.4f + avgTableWsd * 0.6f)

            val deltaSD = wsd - wtsd
            val preflopFocus = if (vpip > 0f) pfr / vpip else 0.5f

            var oppDeltaAdj = 0f
            var oppFocusAdj = 0f
            var oppVpipAdj = 0f

            // Delta SD adjustments
            if (deltaSD > 15f) {
                oppDeltaAdj = -0.05f // Tight at showdown -> play tighter
            } else if (deltaSD < -5f) {
                oppDeltaAdj = 0.05f  // Loose showdown entry -> play looser
            }

            // Preflop focus and calling station adjustments
            if (preflopFocus < 0.3f && vpip > 35f) {
                oppFocusAdj = 0.04f  // Calling station -> value bet wider
            } else if (preflopFocus > 0.8f && vpip < 20f) {
                oppFocusAdj = -0.04f // Aggressive preflop nit -> play tighter
            }

            // VPIP adjustments
            if (vpip < 15f) {
                oppVpipAdj = -0.06f // Extreme nit -> play tighter
            } else if (vpip > 45f) {
                oppVpipAdj = 0.05f  // Loose fish -> exploit with wider value
            }

            // Alternative Mechanism: completely disable personal data adjustments if they have no profile, session, or screen data
            if (!hasProfile && !hasSession && screenVpip == null) {
                oppDeltaAdj = 0f
                oppFocusAdj = 0f
                oppVpipAdj = 0f
            }

            totalDeltaAdj += oppDeltaAdj
            totalFocusAdj += oppFocusAdj
            totalVpipAdj += oppVpipAdj
            countedOpponents++
        }

        // Apply average or bounded cumulative adjustments to prevent uncontrolled deviation (never goes crazy)
        val rawNetAdj = if (countedOpponents > 0) (totalDeltaAdj + totalFocusAdj + totalVpipAdj) / countedOpponents else 0f
        val netOpponentAdjustment = rawNetAdj.coerceIn(-0.15f, 0.15f)

        // 4. Second-order environmental overlays: Table Position
        val positionalAdj = when (position) {
            TablePosition.SB, TablePosition.BB -> -0.03f // Out of Position (OOP) penalty
            TablePosition.UTG, TablePosition.MP -> -0.01f // Early/Middle position
            TablePosition.CO, TablePosition.BTN -> 0.03f  // In Position (IP) advantage
        }

        // 5. Second-order environmental overlays: Tournament Stage / ICM (Bubble Proximity)
        val stageAdj = if (isPreflop) {
            when (stage) {
                TournamentStage.EARLY -> 0.01f
                TournamentStage.MIDDLE -> if (sklanskyGroup >= 5) -0.04f else -0.01f
                TournamentStage.LATE -> {
                    when {
                        sklanskyGroup >= 6 -> -0.12f
                        sklanskyGroup >= 4 -> -0.06f
                        sklanskyGroup <= 2 -> 0.02f
                        else -> -0.03f
                    }
                }
            }
        } else {
            when (stage) {
                TournamentStage.EARLY -> 0.0f
                TournamentStage.MIDDLE -> -0.01f
                TournamentStage.LATE -> -0.04f
            }
        }

        // 6. Stack levels (Table stack limits / M-Ratio)
        val mRatioRaw = if (heroStack > 0 && bigBlind > 0) heroStack / bigBlind else 20.0f
        val mRatio = if (heroStack > 0 && isBbDisplay) heroStack else mRatioRaw
        val stackAdjustment = if (mRatio < 10.0f) {
            if (sklanskyGroup <= 3) 0.06f else -0.06f
        } else if (mRatio > 40.0f && stage == TournamentStage.LATE) {
            0.03f 
        } else {
            0.0f
        }

        // Compute final calibrated L2 score
        val l2Score = (baseL1Score + netOpponentAdjustment + positionalAdj + stageAdj + stackAdjustment).coerceIn(0.0f, 1.0f)

        // Calculate average metrics for Russo-English detailed UI stats logs
        val avgDeltaSD = if (activeOpponents.any { it.stats != null }) {
            activeOpponents.filter { it.stats != null }.map { (it.stats?.histWsd ?: 50f) - (it.stats?.histWtsd ?: 30f) }.average().toFloat()
        } else {
            avgTableWsd - avgTableWtsd
        }
        val avgFocus = if (activeOpponents.any { it.stats != null }) {
            activeOpponents.filter { it.stats != null }.map {
                val v = it.stats?.histVpip ?: it.stats?.vpip ?: 30f
                val p = it.stats?.histPfr ?: it.stats?.pfr ?: 18f
                if (v > 0) p / v else 0.5f
            }.average().toFloat()
        } else {
            if (avgTableVpip > 0) avgTablePfr / avgTableVpip else 0.5f
        }

        // The total overlays generated by L2 modifiers (environmental and opponent profiling)
        val l2Overlays = netOpponentAdjustment + positionalAdj + stageAdj + stackAdjustment
        
        // Apply overlays to the pure Monte Carlo win rate to get a calibrated true equity estimate
        val adjustedS1 = (s1 + l2Overlays).coerceIn(0.0f, 1.0f)

        val profitableCall = adjustedS1 > targetPotOdds

        val action: String
        val explanation: String
        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)

        val activeOpponentsCount = opponents.count { it.isActive }
        val isMultiway = activeOpponentsCount >= 2
        val fairShare = if (activeOpponentsCount > 0) 1.0f / (activeOpponentsCount + 1.0f) else 0.5f

        val betThreshold = if (isPreflop) {
            if (isMultiway) 0.45f else 0.40f
        } else {
            fairShare + if (isMultiway) 0.10f else 0.05f
        }
        val raiseThreshold = if (isPreflop) {
            if (isMultiway) 0.60f else 0.55f
        } else {
            fairShare + if (isMultiway) 0.25f else 0.15f
        }

        if (betToCall > 0) {
            if (profitableCall) {
                // Positively expected call (L2 score > pot odds)
                if (l2Score > raiseThreshold || (isPreflop && sklanskyGroup <= 2 && l2Score > 0.45f)) {
                    if (mRatio < 12.0f) {
                        action = "ALL-IN"
                        explanation = "L2 ALL-IN: short stack def [M=${String.format(Locale.US, "%.1f", mRatio)}]"
                    } else {
                        action = "RAISE"
                        explanation = "L2 Raise (value): exploit field, power ${pct(l2Score)}%"
                    }
                } else {
                    action = "CALL"
                    explanation = "L2 Call: power ${pct(l2Score)}% > odds ${pct(targetPotOdds)}% (with margin)"
                }
            } else {
                // Negative expected call (L2 score <= pot odds)
                if (l2Score > targetPotOdds - 0.05f && (isPreflop && sklanskyGroup <= 4)) {
                    action = "CALL"
                    explanation = "L2 Call (defense): strong pos draw"
                } else {
                    action = "FOLD"
                    explanation = "L2 Fold: weak power ${pct(l2Score)}% < odds ${pct(targetPotOdds)}%"
                }
            }
        } else {
            // No bet to call -> Check / Bet / Raise solver
            if (l2Score > raiseThreshold) {
                action = "RAISE"
                explanation = "L2 Raise (attack): initiative, power ${pct(l2Score)}%, ΔSD ${avgDeltaSD.toInt()}%"
            } else if (l2Score > betThreshold || (isPreflop && sklanskyGroup <= 3)) {
                action = "BET"
                explanation = "L2 Bet: value line, power ${pct(l2Score)}%, Focus ${String.format(Locale.US, "%.2f", avgFocus)}"
            } else if (l2Score > fairShare - 0.05f) {
                action = "CHECK"
                explanation = "L2 Check: pot control, power ${pct(l2Score)}%"
            } else {
                action = "CHECK"
                explanation = "L2 Check: passive line, power ${pct(l2Score)}%"
            }
        }

        // Compose high-fidelity overlay explanation reflecting GTO calibrated metrics
        val l2Vetki = "L1 MATH BASE (Branch 1-4): Average=${pct(baseL1Score)}%\n" +
                      "L2 OVERLAYS:\n" +
                      "- VPIP/Stats Adj (Branch 5): ${if (netOpponentAdjustment >= 0) "+" else ""}${pct(netOpponentAdjustment)}%\n" +
                      "- Positional Adj (Branch 6): ${position.name} -> ${if (positionalAdj >= 0) "+" else ""}${pct(positionalAdj)}%\n" +
                      "- Stage/ICM Adj (Branch 7): ${stage.name} -> ${if (stageAdj >= 0) "+" else ""}${pct(stageAdj)}%\n" +
                      "- Stack Limits (Branch 8): M-Ratio<10 -> ${if (stackAdjustment >= 0) "+" else ""}${pct(stackAdjustment)}%\n" +
                      "Net Output: L2 Calibrated EV=${pct(l2Score)}%"

        val detailedL2Explanation = "⚙️ L2 PERSONAL STATS VETKI:\n$l2Vetki\n🎯 ACTION: $explanation"

        val confidenceValue = when(action) {
            "RAISE", "ALL-IN", "BET" -> (((l2Score - 0.4f) / 0.6f) * 100f).coerceIn(10f, 95f)
            "FOLD" -> {
                val msg = "🚨 DECISION-LOG [FOLD L2]: EV = ${pct(l2Score)}%, Target PotOdds/Threshold = ${pct(targetPotOdds)}%. (s1=${pct(s1)}%, baseL1=${pct(baseL1Score)}%). Reason: L2 threshold missed."
                BotLogSharedState.appendLogBot(msg)
                (((0.5f - l2Score) / 0.5f) * 100f).coerceIn(10f, 95f)
            }
            "CALL" -> ((1.0f - kotlin.math.abs(l2Score - 0.45f) / 0.55f) * 100f).coerceIn(10f, 95f)
            "CHECK" -> ((1.0f - kotlin.math.abs(l2Score - 0.35f) / 0.65f) * 100f).coerceIn(10f, 95f)
            else -> 50f
        }

        return Recommendation(action, confidenceValue, detailedL2Explanation, l2Score)
    }

    fun computeRecommendationAdvanced(
        heroCard1: Card?,
        heroCard2: Card?,
        board: List<Card?>,
        potSize: Float,
        heroBet: Float,
        opponents: List<OpponentState>,
        activeOpponentsCount: Int,
        simResult: SimulationResult?,
        settings: AdvisorSettings,
        position: TablePosition,
        stage: TournamentStage,
        smallBlind: Float,
        bigBlind: Float,
        heroStack: Float,
        lastActions: String = "",
        heroActionOptions: List<String> = emptyList(),
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("WAIT", 100f, "Wait cards.")
        }

        // 1. Core Source 1: Monte Carlo Simulation win pct
        val s1 = (simResult?.heroWinPct ?: 0f) / 100f

        // Core Source 2: Sklansky-Malmuth Group EV
        val sklanskyGroup = getSklanskyGroup(heroCard1, heroCard2)
        val s2 = when (sklanskyGroup) {
            1 -> 1.0f
            2 -> 0.9f
            3 -> 0.8f
            4 -> 0.65f
            5 -> 0.5f
            6 -> 0.35f
            7 -> 0.2f
            else -> 0.1f
        }

        // Core Source 3: Mathematical Chen Score
        val s3 = getChenScore(heroCard1, heroCard2)

        // Calculate bets and Pot Odds elements
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) {
                maxOpponentBet = pBet
            }
        }
        var betToCall = maxOf(0f, maxOpponentBet - heroBet)
        
        // --- OCR FIX FOR MISSED BETS ---
        if (betToCall == 0f && heroActionOptions.isNotEmpty()) {
            val hasCheck = heroActionOptions.any { it.equals("Check", true) }
            val hasCall = heroActionOptions.any { it.equals("Call", true) }
            val hasAllIn = heroActionOptions.any { it.equals("All-in", true) }
            
            if (!hasCheck && (hasCall || hasAllIn)) {
                if (hasAllIn && !hasCall) betToCall = heroStack
                else betToCall = bigBlind.coerceAtLeast(1f) // Assumed minimal bet
            }
        }
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = if (potSize + totalOpponentBets + heroBet < betToCall && betToCall > 0) betToCall + (bigBlind * 1.5f) else potSize + totalOpponentBets + heroBet
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        
        val isPreflop = board.filterNotNull().isEmpty()
        val isPostflop = !isPreflop
        val activeOpponentsCount = opponents.count { it.isActive }
        val targetPotOdds = getTargetPotOdds(potOdds, betToCall, heroStack, activeOpponentsCount, position, isPreflop, stage, sklanskyGroup, bigBlind)

        // Core Source 4: Margin / Potential Odds Gap
        val s4 = if (betToCall > 0f) {
            val gap = s1 - targetPotOdds
            ((gap + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            (s1 * 1.15f).coerceIn(0f, 1f)
        }

        val baseL1Score = if (isPreflop) {
            (s1 * 0.35f) + (s2 * 0.25f) + (s3 * 0.20f) + (s4 * 0.20f)
        } else {
            (s1 * 0.80f) + (s4 * 0.20f)
        }

        val mRatioRaw = if (heroStack > 0 && bigBlind > 0) heroStack / bigBlind else 20.0f
        val mRatio = if (heroStack > 0 && isBbDisplay) heroStack else mRatioRaw

        // 2. Identify active opponents, build robust fallback profiles for first zone if none exists
        val activeOpponents = opponents.filter { it.isActive }
        val countedOpponents = activeOpponents.size
        val divider = maxOf(1, countedOpponents)

        var totalEvL2_5 = 0f
        var totalEvL3_0 = 0f
        var totalEvL3_5 = 0f
        
        var tableArchetype = "Unknown Pool"
        var maxExploitReason = ""

        if (activeOpponents.isEmpty()) {
            totalEvL2_5 = baseL1Score
            totalEvL3_0 = baseL1Score + 0.02f
            totalEvL3_5 = baseL1Score - 0.05f
        } else {
            activeOpponents.forEach { opponent ->
                val profile = opponent.stats
                val profileStats = profile ?: PlayerStats(
                    nickname = opponent.nickname,
                    handsPlayed = 15,
                    vpipCount = 4,
                    pfrCount = 2,
                    histVpip = 28f,
                    histPfr = 18f,
                    hist3Bet = 8f,
                    histFoldTo3Bet = 45f,
                    histCBet = 55f,
                    histFoldToCBet = 45f,
                    histSteal = 32f,
                    histCheckRaise = 10f,
                    histWtsd = 30f,
                    histWsd = 50f
                )

                // 3. Multiparameter recompilation (Синтезированные перемноженные параметры)
                val gap = profileStats.vpipPfrGap
                val showdownResilience = ((profileStats.histWtsd ?: 30f) / 100f) * ((profileStats.histWsd ?: 50f) / 100f)
                val postflopDanger = profileStats.postflopDangerIndex
                val foldVulnerability = ((profileStats.histFoldToCBet ?: 45f) / 100f) * ((profileStats.histFoldTo3Bet ?: 45f) / 100f)

                // 4. Persona Matching (Сопоставление типажей игрового поведения)
                val vpipVal = profileStats.histVpip ?: profileStats.vpip
                val pfrVal = profileStats.histPfr ?: profileStats.pfr
                val wtsdVal = profileStats.histWtsd ?: 30f
                val wsdVal = profileStats.histWsd ?: 50f

                val archetype = when {
                    vpipVal > 40f && pfrVal < 12f -> "Whale🐋"
                    vpipVal > 35f && pfrVal > 25f && postflopDanger > 40f -> "Maniac🐆"
                    vpipVal < 16f && pfrVal < 12f && showdownResilience < 0.12f -> "Nit🦎"
                    wtsdVal > 35f && wsdVal < 45f -> "Station🐒"
                    else -> "Reg🦈"
                }
                
                tableArchetype = archetype

                // 5. Pathway Adjustment for THIS specific opponent
                var oppEvL2_5 = baseL1Score
                if (archetype == "Maniac🐆") oppEvL2_5 += 0.14f
                else if (archetype == "Whale🐋" || archetype == "Station🐒") oppEvL2_5 -= 0.10f

                var oppEvL3_0 = baseL1Score + 0.02f
                if (archetype == "Whale🐋" || archetype == "Station🐒") oppEvL3_0 += 0.15f
                else if (archetype == "Nit🦎") oppEvL3_0 -= 0.06f

                var oppEvL3_5 = baseL1Score - 0.05f
                if (foldVulnerability > 0.22f || archetype == "Nit🦎") oppEvL3_5 += 0.18f
                else if (archetype == "Whale🐋" || archetype == "Station🐒") oppEvL3_5 -= 0.18f

                totalEvL2_5 += oppEvL2_5
                totalEvL3_0 += oppEvL3_0
                totalEvL3_5 += oppEvL3_5

                // Дополнительные точечные эксплойты в зависимости от ситуативности
                val steal = profileStats.histSteal ?: 35f
                val fold3 = profileStats.histFoldTo3Bet ?: profileStats.foldTo3bet
                val foldCbet = profileStats.histFoldToCBet ?: 45f
                val cr = profileStats.histCheckRaise ?: 10f
                val cbet = profileStats.histCBet ?: 55f

                if (isPreflop && (position == TablePosition.SB || position == TablePosition.BB) && steal > 40f) {
                    maxExploitReason = "Steal >40% auto blind def"
                } else if (isPreflop && fold3 > 60f) {
                    maxExploitReason = "Overfold to 3-Bet (>60%)"
                } else if (isPostflop && betToCall == 0f && foldCbet > 55f) {
                    maxExploitReason = "Auto-bluff (Fold to CB >55%)"
                } else if (wtsdVal > 32f || (gap > 20f && vpipVal > 35f)) {
                    maxExploitReason = "Opp is station (respect)"
                } else if (wtsdVal < 25f && wsdVal > 55f && betToCall > bigBlind) {
                    maxExploitReason = "Respect rock agro!"
                } else if (isPostflop && betToCall > 0 && cbet > 70f) {
                    maxExploitReason = "Float vs wide. CB"
                } else if (isPostflop && cr > 15f && betToCall > 0) {
                    maxExploitReason = "Agro Check-Raise!"
                }
            }
        }

        // Average pathways across opponents
        var evL2_5 = totalEvL2_5 / divider
        var evL3_0 = totalEvL3_0 / divider
        var evL3_5 = totalEvL3_5 / divider

        // Global environmental bounds
        if (mRatio < 8f) {
            evL2_5 -= 0.05f // Короткий стек не имеет пространства для пассивного разыгрывания
        }
        if (position == TablePosition.UTG || position == TablePosition.MP) {
            evL3_5 -= 0.05f // Без позиции блефы менее прибыльны
        }

        // Выбираем оптимальный путь с максимальным математическим ожиданием
        val bestBranch: String
        val l3Score: Float
        val action: String
        val branchSummary: String

        val isMultiway = activeOpponentsCount >= 2
        val fairShare = if (activeOpponentsCount > 0) 1.0f / (activeOpponentsCount + 1.0f) else 0.5f

        val betThreshold = if (isPreflop) {
            if (isMultiway) 0.45f else 0.40f
        } else {
            fairShare + if (isMultiway) 0.10f else 0.05f
        }
        val raiseThreshold = if (isPreflop) {
            if (isMultiway) 0.60f else 0.55f
        } else {
            fairShare + if (isMultiway) 0.25f else 0.15f
        }

        // Determine branch and adjust pure equity (s1) with heuristic overlays
        if (evL3_5 > evL3_0 && evL3_5 > evL2_5) {
            bestBranch = "L3.5 (Bluff)"
            l3Score = evL3_5
            val adjustedS1 = (s1 + (l3Score - baseL1Score)).coerceIn(0f, 1f)
            action = if (betToCall > 0) {
                if (l3Score > raiseThreshold) "RAISE" else if (adjustedS1 > targetPotOdds || (isPreflop && sklanskyGroup <= 4)) "CALL" else "FOLD"
            } else "BET"
            branchSummary = "Max fold equity bluffing"
        } else if (evL3_0 > evL2_5) {
            bestBranch = "L3.0 (Value)"
            l3Score = evL3_0
            val adjustedS1 = (s1 + (l3Score - baseL1Score)).coerceIn(0f, 1f)
            if (betToCall > 0) {
                if (adjustedS1 > targetPotOdds) {
                    action = if (l3Score > raiseThreshold || (isPreflop && sklanskyGroup <= 2)) "RAISE" else "CALL"
                } else {
                    action = if (isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds - 0.05f) "CALL" else "FOLD"
                }
            } else {
                action = if (l3Score > betThreshold) "BET" else "CHECK"
            }
            branchSummary = if (action == "CHECK") "Pot control (check strong range)" else "Value extract"
        } else {
            bestBranch = "L2.5 (Passive)"
            l3Score = evL2_5
            val adjustedS1 = (s1 + (l3Score - baseL1Score)).coerceIn(0f, 1f)
            if (betToCall > 0) {
                if (adjustedS1 > targetPotOdds) {
                    action = "CALL"
                } else {
                    action = if (isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds - 0.05f) "CALL" else "FOLD"
                }
            } else {
                action = "CHECK"
            }
            branchSummary = if (action == "CHECK") "Fake weak / Bal. check" else "Catch bluffs"
        }

        // Ограничиваем score в пределах [0, 1]
        val finalScore = l3Score.coerceIn(0.0f, 1.0f)

        // Превращаем оптимальный путь в действие во вскрытии
        var finalAction = action
        var explanation = branchSummary

        // Специфический пуш-фолд эксплойт на коротких стеках
        if (mRatio < 8f && isPreflop) {
            if (sklanskyGroup <= 4 || (finalScore > 0.60f && sklanskyGroup <= 5)) {
                finalAction = "ALL-IN"
                explanation = "ALL-IN short stack preflop [M=${String.format(Locale.US, "%.1f", mRatio)}]"
            } else if (finalAction == "CALL" && betToCall > (bigBlind * 2.5f).coerceAtLeast(0f)) {
                finalAction = "FOLD"
                explanation = "Fold short stack preflop"
                BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD]: Short stack preflop error (M=${mRatio})")
            }
        }
        
        // Push very high equity on late streets to extract maximum value or end hand
        if (isPostflop && finalScore >= 0.85f && board.filterNotNull().size >= 4) {
             finalAction = "ALL-IN"
             explanation = "Max EV pressure. ALL-IN strong combo!"
        } else if (isPostflop && finalScore >= 0.75f && board.filterNotNull().size >= 4 && (finalAction == "BET" || finalAction == "RAISE")) {
             finalAction = "BET MAX"
             explanation = "Strong value! Max bet."
        }
        
        // Remove exploit reason if action contradicts it
        if (maxExploitReason == "Overfold to 3-Bet (>60%)" && finalAction != "RAISE" && finalAction != "ALL-IN") maxExploitReason = ""
        if (maxExploitReason == "Auto-bluff (Fold to CB >55%)" && finalAction != "BET" && finalAction != "ALL-IN" && finalAction != "RAISE") maxExploitReason = ""
        if (maxExploitReason == "Float vs wide. CB" && finalAction != "CALL" && finalAction != "RAISE") maxExploitReason = ""

        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)
        
        val archetypeLabel = if (countedOpponents > 1) "Mix [$tableArchetype+]" else "[$tableArchetype]"
        
        val fullExpl: String = if (maxExploitReason.isNotEmpty()) {
            "L3 $bestBranch: Check=${pct(evL2_5)}%|Act=${pct(evL3_0)}%|Agr=${pct(evL3_5)}% $archetypeLabel -> $maxExploitReason | $explanation"
        } else {
            "L3 $bestBranch: Check=${pct(evL2_5)}%|Act=${pct(evL3_0)}%|Agr=${pct(evL3_5)}% $archetypeLabel -> $explanation"
        }

        val confidence = when(finalAction) {
            "RAISE", "ALL-IN", "BET", "BET MAX" -> (((finalScore - 0.4f) / 0.6f) * 100f).coerceIn(10f, 98f)
                        "FOLD" -> {
                            val msg = "🚨 DECISION-LOG [FOLD L3]: EV = ${pct(finalScore)}%, Target PotOdds/Threshold = ${pct(targetPotOdds)}%. (s1=${pct(s1)}%, baseL1=${pct(baseL1Score)}%). Reason: L3score ${pct(l3Score)}% <= Target."
                            BotLogSharedState.appendLogBot(msg)
                            (((0.5f - finalScore) / 0.5f) * 100f).coerceIn(10f, 98f)
                        }
            "CALL" -> ((1.0f - kotlin.math.abs(finalScore - 0.45f) / 0.55f) * 100f).coerceIn(10f, 98f)
            "CHECK" -> ((1.0f - kotlin.math.abs(finalScore - 0.35f) / 0.65f) * 100f).coerceIn(10f, 98f)
            else -> 50f
        }

        return Recommendation(finalAction, confidence, fullExpl, finalScore)
    }

    fun computeRecommendationL4(
        heroCard1: Card?,
        heroCard2: Card?,
        board: List<Card?>,
        potSize: Float,
        heroBet: Float,
        opponents: List<OpponentState>,
        activeOpponentsCount: Int,
        simResult: SimulationResult?,
        settings: AdvisorSettings,
        position: TablePosition,
        stage: TournamentStage,
        smallBlind: Float,
        bigBlind: Float,
        heroStack: Float,
        lastActions: String = "",
        heroActionOptions: List<String> = emptyList(),
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("WAIT", 100f, "Wait cards.")
        }

        // L4 is disabled by USER request (Level 4 behavior-adaptive logic bypassed)
        return Recommendation("", 0f, "Disabled by USER")
    }
}
