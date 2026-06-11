package com.example

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

// 1. Settings Preferences Data Structure
data class AdvisorSettings(
    val usePip: Boolean = true,
    val useAdvancedStats: Boolean = true,
    val showRecommendation: Boolean = true,
    val fontScale: Float = 1.0f,
    val windowWidthPct: Int = 100,
    val windowHeightPct: Int = 100
)

// 2. Opponent Statistics Data Structure
data class PlayerStats(
    val nickname: String,
    val handsPlayed: Int = 0,
    val vpipCount: Int = 0,
    val pfrCount: Int = 0,
    val foldTo3betCount: Int = 0,
    val showdownWins: Int = 0,
    val showdownTotal: Int = 0,
    val aggressionCount: Int = 0, // bets/raises
    val aggressionCalls: Int = 0,  // calls
    // Historical profile data extracted from user profile windows
    val histVpip: Float? = null,
    val histPfr: Float? = null,
    val hist3Bet: Float? = null,
    val histFoldTo3Bet: Float? = null,
    val histCBet: Float? = null,
    val histFoldToCBet: Float? = null,
    val histSteal: Float? = null,
    val histCheckRaise: Float? = null,
    val histWtsd: Float? = null,
    val histWsd: Float? = null,
    val lastUpdated: Long = 0L,
    @Transient var profileBoundingBoxes: List<ScannedBox>? = null
) {
    val vpip: Float get() = if (handsPlayed > 0) (vpipCount.toFloat() / handsPlayed * 100f) else 0f
    val pfr: Float get() = if (handsPlayed > 0) (pfrCount.toFloat() / handsPlayed * 100f) else 0f
    val foldTo3bet: Float get() = if (handsPlayed > 0) (foldTo3betCount.toFloat() / handsPlayed * 100f) else 0f
    val showdownWinPct: Float get() = if (showdownTotal > 0) (showdownWins.toFloat() / showdownTotal * 100f) else 0f
    val aggressionFactor: Float get() = if (aggressionCalls > 0) (aggressionCount.toFloat() / aggressionCalls.toFloat()) else aggressionCount.toFloat()

    // --- SYNTHETIC METRICS (Синтетические метрики) ---

    // 1. Пассивный коридор (Calling Station Index)
    // Разрыв между VPIP и PFR. Если разрыв >15%, значит оппонент много коллирует префлоп, играя пассивно.
    val vpipPfrGap: Float get() {
        val currVpip = histVpip ?: vpip
        val currPfr = histPfr ?: pfr
        return currVpip - currPfr
    }

    // 2. Индекс честности на шоудауне (Postflop Honesty Index)
    // Разница между выигрышами (WSD) и доходами до вскрытия (WTSD).
    // Высокий WTSD (>30%) и низкий WSD (<48%) (Индекс < 15-18) = Телефон/Автоответчик (коллирует с мусором).
    // Низкий WTSD (<25%) и высокий WSD (>55%) (Индекс > 30) = Скала/Нит (показывает только сильные комбинации).
    val honestyIndex: Float get() {
        val wtsd = histWtsd ?: 30f
        val wsd = histWsd ?: 50f
        return wsd - wtsd
    }

    // 3. Частота префлоп-блефа / Опенрайза с мусором (Preflop Bluffing Tendency)
    // Произведение частоты PFR на частоту фолда на 3-бет.
    // Если оппонент делает много рейзов (высокий PFR, например 25%), но затем сдается на агрессию (>60%),
    // значит он открывается очень широко с блефами. Коэффициент > 15 = лузово-агрессивный префлоп.
    val preflopBluffingTendency: Float get() {
        val currPfr = histPfr ?: pfr
        val fold3 = histFoldTo3Bet ?: 45f
        return currPfr * (fold3 / 100f)
    }

    // 4. Агрессия на нескольких улицах (Multi-Street Danger Index)
    // Индекс опасности действий оппонента после флопа. Конт-бет (базовая агрессия) + 3x Check-Raise (экстремальная агрессия).
    // Позволяет найти маньяков или регуляров, склонных к жесткому эксплойту на постфлопе.
    val postflopDangerIndex: Float get() {
        val cbet = histCBet ?: 55f
        val cr = histCheckRaise ?: 10f
        return (cbet * 0.7f) + (cr * 3f)
    }
}

// 3. Recommendation Response Object
data class Recommendation(
    val action: String, // CHECK, FOLD, CALL, RAISE, ALL-IN
    val confidence: Float, // 0 - 100
    val explanation: String,
    val originalScore: Float = 0f
)

enum class TablePosition(val displayName: String) {
    UTG("UTG (Early)"),
    MP("MP (Middle)"),
    CO("CO (Cutoff)"),
    BTN("BTN (Button)"),
    SB("SB (Small Blind)"),
    BB("BB (Big Blind)")
}

enum class TournamentStage(val displayName: String) {
    EARLY("Early (Deep Stack)"),
    MIDDLE("Middle Stage"),
    LATE("Late (Short Stack)")
}

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
            TablePosition.UTG -> if (isPreflop) 0.08f else 0.04f
            TablePosition.MP -> if (isPreflop) 0.05f else 0.03f
            TablePosition.CO -> if (isPreflop) 0.02f else 0.01f
            TablePosition.BTN -> 0.0f
            TablePosition.SB -> if (isPreflop) 0.03f else 0.08f
            TablePosition.BB -> if (isPreflop) 0.0f else 0.05f
        }
    }

    private fun getTargetPotOdds(basePotOdds: Float, betToCall: Float, heroStack: Float, activeOpponentsCount: Int, position: TablePosition, isPreflop: Boolean, stage: TournamentStage, sklanskyGroup: Int, bigBlind: Float = 0f): Float {
        val mRatio = if (heroStack > 0 && bigBlind > 0) heroStack / bigBlind else 20f
        
        val stackRisk = if (heroStack > 0) (betToCall / heroStack).coerceIn(0f, 1f) else 0f
        // Increase safety margin if we risk a large portion of our DEEP stack, but vanish this penalty if we are short-stacked (M < 10)
        val shortStackFactor = (mRatio / 15f).coerceIn(0.1f, 1.0f)
        val stackRiskMargin = stackRisk * 0.15f * shortStackFactor
        
        // Penalize for multiple active opponents (squeeze hazard & multiway dilution)
        val multiwayHazard = if (activeOpponentsCount > 1) {
            (activeOpponentsCount - 1) * 0.015f
        } else 0f

        val positionalHazard = getPositionalHazard(position, isPreflop) * 0.5f
        
        // Stage & Bubble Hazard (Tighter ranges as game progresses, but less so if we are short stacked)
        var stageHazard = if (isPreflop) {
            when (stage) {
                TournamentStage.EARLY -> {
                    // Early game: we want to play speculative hands cheaply to felt fish.
                    0.0f 
                }
                TournamentStage.MIDDLE -> {
                    // Middle game: fish are gone, ranges are tighter. Marginal hands are penalized.
                    if (sklanskyGroup >= 6) 0.06f
                    else if (sklanskyGroup >= 5) 0.03f
                    else 0.0f
                }
                TournamentStage.LATE -> {
                    // Late/Bubble: survival mode. Very tight calling ranges. 
                    // High penalty for marginal hands.
                    when {
                        sklanskyGroup >= 6 -> 0.14f
                        sklanskyGroup >= 4 -> 0.07f
                        else -> 0.03f // Even good hands require slightly more equity due to ICM
                    }
                }
            }
        } else {
            // Postflop stage hazard evaluates raw survival pressure
            when (stage) {
                TournamentStage.EARLY -> 0.0f
                TournamentStage.MIDDLE -> 0.02f
                TournamentStage.LATE -> 0.06f 
            }
        }
        
        stageHazard *= shortStackFactor

        // --- CHEAP FLOP FIX ---
        // If it's preflop and we can see the flop for 1-2 big blinds (like a limp or min-raise), 
        // we shouldn't heavily penalize medium hands.
        if (isPreflop && betToCall > 0f && bigBlind > 0f && betToCall <= bigBlind * 2.1f) {
            stageHazard = 0.0f // Remove late stage penalty for super cheap calls
            stageHazard -= 0.03f // Small bonus to encourage seeing cheap flops with marginal hands
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
        isBbDisplay: Boolean = false
    ): Recommendation {
        // Zero cards check
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation(
                action = "FOLD",
                confidence = 100f,
                explanation = "Enter Hero hole cards to trigger active advice solver."
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
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = potSize + totalOpponentBets + heroBet
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
                        explanation = "ОЛЛ-ИН: превосходное EV ${pct(s1)}% [M=${fmt(mRatio)}]"
                    } else {
                        action = "RAISE"
                        explanation = "Рейз: сильное велью-эквити ${pct(s1)}%"
                    }
                } else {
                    action = "CALL"
                    explanation = "Колл: эквити ${pct(s1)}% > шансы ${pct(targetPotOdds)}% (с учетом маржи)"
                }
            } else {
                // Unprofitable call based on raw win percentage. Check if playability score offers GTO defense
                if (l1Score > 0.48f && s1 > (0.9f / (activeOpponentsCount + 1.0f))) {
                    if (s1 > 0.45f && l1Score > raiseThreshold) {
                        action = "RAISE"
                        explanation = "Рейз (полублеф): сильная математическая структура, эквити ${pct(s1)}%"
                    } else {
                        action = "CALL"
                        explanation = "Колл (оборона): высокая играбельность карт, эквити ${pct(s1)}%"
                    }
                } else {
                    action = "FOLD"
                    explanation = "Фолд: математически невыгодно, эквити ${pct(s1)}% < шансы ${pct(potOdds)}%"
                }
            }
        } else {
            // No bet to call (betToCall == 0) -> Check/Bet/Raise solver
            if (s1 > 0.60f && l1Score > raiseThreshold) {
                if (mRatio < 12.0f && (s1 > 0.80f || sklanskyGroup <= 2)) {
                    action = "ALL-IN"
                    explanation = "ОЛЛ-ИН: математический превосходный пуш, эквити ${pct(s1)}%"
                } else {
                    action = "RAISE"
                    explanation = "Рейз: велью-агрессия, эквити ${pct(s1)}%"
                }
            } else if (s1 > 0.45f && l1Score > betThreshold) {
                action = "BET"
                explanation = "Ставка: получено математическое преимущество, эквити ${pct(s1)}%"
            } else if (l1Score > 0.50f) {
                action = "BET"
                explanation = "Ставка (блеф): защита структуры, эквити ${pct(s1)}%"
            } else {
                action = "CHECK"
                explanation = "Чек: ведение банка по шансам, эквити ${pct(s1)}%"
            }
        }

        // Formulate highly detailed explanation with mathematical weights tracking
        val detailExplanation = "GTO L1: EV[Sim=${pct(s1)}%|Skl=${pct(s2)}%|Chen=${pct(s3)}%|Mrg=${pct(s4)}%] (СрВыч=${pct(l1Score)}%) | $explanation"

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
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("FOLD", 100f, "Enter cards.")
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
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = potSize + totalOpponentBets + heroBet
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

            // Fallback strategy: try Profile (hist), then try duplicates (session), then blend population and table average
            val vpip = stats?.histVpip ?: stats?.vpip ?: (25f * 0.4f + avgTableVpip * 0.6f)
            val pfr = stats?.histPfr ?: stats?.pfr ?: (15f * 0.4f + avgTablePfr * 0.6f)
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

            // Alternative Mechanism: completely disable personal data adjustments if they have no profile or session data
            if (!hasProfile && !hasSession) {
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
                TournamentStage.EARLY -> 0.01f  // Deep stack comfort, slight boost for speculative plays
                TournamentStage.MIDDLE -> if (sklanskyGroup >= 5) -0.04f else -0.01f
                TournamentStage.LATE -> {
                    // Bubble / survival: severely penalize weak hands, reward premium
                    when {
                        sklanskyGroup >= 6 -> -0.12f
                        sklanskyGroup >= 4 -> -0.06f
                        sklanskyGroup <= 2 -> 0.02f // Premium hands get a tiny boost for stealing
                        else -> -0.03f
                    }
                }
            }
        } else {
            when (stage) {
                TournamentStage.EARLY -> 0.0f
                TournamentStage.MIDDLE -> -0.01f
                TournamentStage.LATE -> -0.04f  // Bubble / survival adjustment (play tighter in uncertain calls)
            }
        }

        // 6. Stack levels (Table stack limits / M-Ratio)
        val mRatioRaw = if (heroStack > 0 && bigBlind > 0) heroStack / bigBlind else 20.0f
        val mRatio = if (heroStack > 0 && isBbDisplay) heroStack else mRatioRaw
        val stackAdjustment = if (mRatio < 10.0f) {
            // Under short-stack pressure, reduce speculative play and tilt decisions to push/fold
            if (sklanskyGroup <= 3) 0.06f else -0.06f
        } else if (mRatio > 40.0f && stage == TournamentStage.LATE) {
            // Big stack bully on the bubble
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
                        explanation = "L2 ОЛЛ-ИН: защита укороченного стека [M=${String.format(Locale.US, "%.1f", mRatio)}]"
                    } else {
                        action = "RAISE"
                        explanation = "L2 Рейз (велью-напор): эксплойт полей, сила ${pct(l2Score)}%"
                    }
                } else {
                    action = "CALL"
                    explanation = "L2 Колл: сила ${pct(l2Score)}% > шансы ${pct(targetPotOdds)}% (с учетом маржи)"
                }
            } else {
                // Negative expected call (L2 score <= pot odds)
                if (l2Score > targetPotOdds - 0.05f && (isPreflop && sklanskyGroup <= 4)) {
                    action = "CALL"
                    explanation = "L2 Колл (оборона): сильная позиционная дожидаемость"
                } else {
                    action = "FOLD"
                    explanation = "L2 Фолд: невыгодно, сила ${pct(l2Score)}% < шансы ${pct(targetPotOdds)}%"
                }
            }
        } else {
            // No bet to call -> Check / Bet / Raise solver
            if (l2Score > raiseThreshold) {
                action = "RAISE"
                explanation = "L2 Рейз (атака): инициатива, сила ${pct(l2Score)}%, ΔSD ${avgDeltaSD.toInt()}%"
            } else if (l2Score > betThreshold || (isPreflop && sklanskyGroup <= 3)) {
                action = "BET"
                explanation = "L2 Ставка: велью-линия, сила ${pct(l2Score)}%, КПФ ${String.format(Locale.US, "%.2f", avgFocus)}"
            } else if (l2Score > fairShare - 0.05f) {
                action = "CHECK"
                explanation = "L2 Чек (контроль): ведение пота, сила ${pct(l2Score)}%"
            } else {
                action = "CHECK"
                explanation = "L2 Чек: пас линии, сила ${pct(l2Score)}%"
            }
        }

        // Compose high-fidelity overlay explanation reflecting GTO calibrated metrics
        val detailedL2Explanation = "L2: EV[L1=${pct(baseL1Score)}%|Opp=${String.format(Locale.US, "%+.1f", netOpponentAdjustment*100)}%|Env=${String.format(Locale.US, "%+.1f", (positionalAdj+stageAdj+stackAdjustment)*100)}%] (Калиб=${pct(l2Score)}%) | $explanation"

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
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("FOLD", 100f, "Enter cards.")
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
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)
        val totalOpponentBets = opponents.filter { it.isActive }.sumOf { (if (settings.usePip) it.betSize else 0f).toDouble() }.toFloat()
        val activePot = potSize + totalOpponentBets + heroBet
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
        
        var tableArchetype = "Неизвестный Пул"
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
                    vpipVal > 40f && pfrVal < 12f -> "Гиппопотам"
                    vpipVal > 35f && pfrVal > 25f && postflopDanger > 40f -> "Гепард"
                    vpipVal < 16f && pfrVal < 12f && showdownResilience < 0.12f -> "Хамелеон"
                    wtsdVal > 35f && wsdVal < 45f -> "Обезьяна"
                    else -> "Акула"
                }
                
                tableArchetype = archetype

                // 5. Pathway Adjustment for THIS specific opponent
                var oppEvL2_5 = baseL1Score
                if (archetype == "Гепард") oppEvL2_5 += 0.14f
                else if (archetype == "Гиппопотам" || archetype == "Обезьяна") oppEvL2_5 -= 0.10f

                var oppEvL3_0 = baseL1Score + 0.02f
                if (archetype == "Гиппопотам" || archetype == "Обезьяна") oppEvL3_0 += 0.15f
                else if (archetype == "Хамелеон") oppEvL3_0 -= 0.06f

                var oppEvL3_5 = baseL1Score - 0.05f
                if (foldVulnerability > 0.22f || archetype == "Хамелеон") oppEvL3_5 += 0.18f
                else if (archetype == "Гиппопотам" || archetype == "Обезьяна") oppEvL3_5 -= 0.18f

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
                    maxExploitReason = "Steal >40% авто-защита блайндов"
                } else if (isPreflop && fold3 > 60f) {
                    maxExploitReason = "Оверфолд на 3-Bet (>60%)"
                } else if (isPostflop && betToCall == 0f && foldCbet > 55f) {
                    maxExploitReason = "Авто-блеф (Fold to CB >55%)"
                } else if (wtsdVal > 32f || (gap > 20f && vpipVal > 35f)) {
                    maxExploitReason = "Опп - телефон (респект)"
                } else if (wtsdVal < 25f && wsdVal > 55f && betToCall > bigBlind) {
                    maxExploitReason = "Респект агрессии Скале!"
                } else if (isPostflop && betToCall > 0 && cbet > 70f) {
                    maxExploitReason = "Флоат против шир. CB"
                } else if (isPostflop && cr > 15f && betToCall > 0) {
                    maxExploitReason = "Агрессивный Чек-Рейз!"
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
            bestBranch = "L3.5 (Блеф)"
            l3Score = evL3_5
            val adjustedS1 = (s1 + (l3Score - baseL1Score)).coerceIn(0f, 1f)
            action = if (betToCall > 0) {
                if (l3Score > raiseThreshold) "RAISE" else if (adjustedS1 > targetPotOdds || (isPreflop && sklanskyGroup <= 4)) "CALL" else "FOLD"
            } else "BET"
            branchSummary = "Максимизация фолд-эквити блефом"
        } else if (evL3_0 > evL2_5) {
            bestBranch = "L3.0 (Велью)"
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
            branchSummary = if (action == "CHECK") "Аккуратный контроль банка (чек сильного спектра)" else "Извлечение велью из сильной спектральной структуры"
        } else {
            bestBranch = "L2.5 (Пассив)"
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
            branchSummary = if (action == "CHECK") "Имитация слабости / Сбалансированный чек" else "Сбор блефов прокаткой"
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
                explanation = "ОЛЛ-ИН по короткому стеку префлоп [M=${String.format(Locale.US, "%.1f", mRatio)}]"
            } else if (finalAction == "CALL" && betToCall > (bigBlind * 2.5f).coerceAtLeast(0f)) {
                finalAction = "FOLD"
                explanation = "Пас укороченного стека префлоп"
                BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD]: Ошибка короткого стека префлоп (M=${mRatio})")
            }
        }
        
        // Push very high equity on late streets to extract maximum value or end hand
        if (isPostflop && finalScore >= 0.85f && board.filterNotNull().size >= 4) {
             finalAction = "ALL-IN"
             explanation = "Давление EV максимизировано. Дожим (ОЛЛ-ИН) с сильной комбинацией!"
        } else if (isPostflop && finalScore >= 0.75f && board.filterNotNull().size >= 4 && (finalAction == "BET" || finalAction == "RAISE")) {
             finalAction = "BET MAX"
             explanation = "Мощный велью! Увеличиваем банк максимизированной ставкой."
        }
        
        // Remove exploit reason if action contradicts it
        if (maxExploitReason == "Оверфолд на 3-Bet (>60%)" && finalAction != "RAISE" && finalAction != "ALL-IN") maxExploitReason = ""
        if (maxExploitReason == "Авто-блеф (Fold to CB >55%)" && finalAction != "BET" && finalAction != "ALL-IN" && finalAction != "RAISE") maxExploitReason = ""
        if (maxExploitReason == "Флоат против шир. CB" && finalAction != "CALL" && finalAction != "RAISE") maxExploitReason = ""

        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)
        
        val archetypeLabel = if (countedOpponents > 1) "Смесь [$tableArchetype+]" else "[$tableArchetype]"
        
        val fullExpl: String = if (maxExploitReason.isNotEmpty()) {
            "L3 $bestBranch: Чек=${pct(evL2_5)}%|Акт=${pct(evL3_0)}%|Агр=${pct(evL3_5)}% $archetypeLabel -> $maxExploitReason | $explanation"
        } else {
            "L3 $bestBranch: Чек=${pct(evL2_5)}%|Акт=${pct(evL3_0)}%|Агр=${pct(evL3_5)}% $archetypeLabel -> $explanation"
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
        isBbDisplay: Boolean = false
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("FOLD", 100f, "Enter cards.")
        }

        // Get L3 advanced recommendation to base our adaptive adjustments upon
        val baseL3 = computeRecommendationAdvanced(
            heroCard1, heroCard2, board, potSize, heroBet,
            opponents, activeOpponentsCount, simResult, settings, position, stage, smallBlind, bigBlind, heroStack, lastActions
        )

        // Find the main active opponent
        val opponent = opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
        val stats = opponent?.stats

        var action = baseL3.action
        var confidence = baseL3.confidence
        var customExplanation = "DNA: Адаптивное решение"

        if (stats != null) {
            val vpip = stats.histVpip ?: stats.vpip
            val pfr = stats.histPfr ?: stats.pfr
            val wtsd = stats.histWtsd ?: 30f
            val wsd = stats.histWsd ?: 50f

            // Define "Creature's DNA" profile subtypes of active opponent:
            val dnaProfile = when {
                vpip > 50f && pfr < 10f -> "Гиппопотам" // Super loose-passive whale
                vpip > 35f && pfr > 25f -> "Гепард" // Aggressive maniac
                vpip < 16f && pfr < 12f -> "Хамелеон" // Passive Nit
                wtsd > 35f && wsd < 45f -> "Обезьяна" // Showdown-station
                else -> "Акула" // Decent regular
            }

            // High pressure or short stack DNA response
            val bigBlinds = if (heroStack > 0 && isBbDisplay) heroStack else heroStack / (bigBlind.coerceAtLeast(1f))
            if (bigBlinds < 15f && board.filterNotNull().isEmpty()) {
                // Short stack preflop Push/Fold DNA adjustment
                val sklansky = getSklanskyGroup(heroCard1, heroCard2)
                if (sklansky <= 4) {
                    action = "RAISE" // Recommend raise/push instead of call
                    confidence = 90f
                    customExplanation = "DNA ($dnaProfile): Пуш/Фолд при <15бб"
                } else if (action == "CALL") {
                    action = "FOLD"
                    confidence = 85f
                    customExplanation = "DNA ($dnaProfile): Сброс маргинала при <15бб"
                    BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD L4]: $customExplanation")
                } else if (action == "FOLD") {
                    customExplanation = "DNA ($dnaProfile): Сброс рук при <15бб"
                }
            } else {
                // Preflop / Postflop adaptive meta game behavior matching DNA profiles:
                when (dnaProfile) {
                    "Гиппопотам" -> {
                        // Against passive whale, NEVER bluff, only thin value raise/bet
                        if (action == "RAISE" || action == "BET") {
                            confidence = (confidence + 15f).coerceAtMost(98f)
                            customExplanation = "DNA: Велью-напор против Гиппопотама"
                        } else if (action == "CALL" && baseL3.confidence < 60f) {
                            action = "FOLD"
                            confidence = 75f
                            customExplanation = "DNA: Сброс маргинальной руки против Гиппопотама"
                            BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD L4]: $customExplanation")
                        }
                    }
                    "Гепард" -> {
                        // cheetah is super aggressive. Let's trap / check-call or let him bluff
                        if (action == "BET" && board.filterNotNull().isNotEmpty()) {
                            action = "CHECK"
                            confidence = 80f
                            customExplanation = "DNA: Ловушка (чекаем вперед Гепарда)"
                        } else if (action == "CALL") {
                            confidence = (confidence + 10f).coerceAtMost(95f)
                            customExplanation = "DNA: Взвешенный прием ставки Гепарда"
                        } else if (action == "FOLD") {
                            BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD L4]: Гепард")
                        }
                    }
                    "Хамелеон" -> {
                        // Chameleon is tight-passive. Overfold to his bets, steal his blinds.
                        if (action == "RAISE" && baseL3.confidence < 65f) {
                            action = "FOLD"
                            confidence = 90f
                            customExplanation = "DNA: Падаем под силу Хамелеона"
                            BotLogSharedState.appendLogBot("🚨 DECISION-LOG [FOLD L4]: $customExplanation")
                        } else if (action == "CHECK" && position == TablePosition.BTN) {
                            action = "BET"
                            confidence = 70f
                            customExplanation = "DNA: Кража пота у Хамелеона"
                        }
                    }
                    "Обезьяна" -> {
                        // Showdown station, loves calling. Don't pull big multi-street bluffs.
                        if (action == "BET" && baseL3.confidence < 60f) {
                            action = "CHECK"
                            confidence = 85f
                            customExplanation = "DNA: Обезьяну не напугать, чек"
                        } else {
                            customExplanation = "DNA: Чистый велью-пуш в Обезьяну"
                        }
                    }
                    else -> {
                        customExplanation = "DNA (Акула): Адаптивный сбалансированный эксплойт"
                    }
                }
            }
        } else {
            customExplanation = "DNA: Ожидание профиля (Баланс)"
        }

        // Global safeguard: never fold if checking is free/possible
        val betToCall = maxOf(0f, heroBet.let { hb -> 
            opponents.filter { it.isActive }.maxOfOrNull { opp -> if (settings.usePip) opp.betSize else 0f }?.minus(hb) ?: 0f 
        })
        if (betToCall <= 0f && action == "FOLD") {
            action = "CHECK"
            customExplanation = "Чек: бесплатно (исправление случайного фолда)"
            confidence = 100f
        }

        return Recommendation(action, confidence, customExplanation, baseL3.originalScore)
    }
}

// 5. High-performance clean player stats and preferences database helper (SharedPreferences)
class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("poker_hud_prefs", Context.MODE_PRIVATE)

    // Saves general simulation & advisor configurations
    fun saveAdvisorSettings(settings: AdvisorSettings) {
        prefs.edit().apply {
            putBoolean("advisor_use_pip", settings.usePip)
            putBoolean("advisor_use_advanced_stats", settings.useAdvancedStats)
            putBoolean("advisor_show_recommendation", settings.showRecommendation)
            putFloat("advisor_font_scale", settings.fontScale)
            putInt("advisor_window_width_pct", settings.windowWidthPct)
            putInt("advisor_window_height_pct", settings.windowHeightPct)
            apply()
        }
    }

    // Loads general simulation & advisor configurations
    fun loadAdvisorSettings(): AdvisorSettings {
        return AdvisorSettings(
            usePip = prefs.getBoolean("advisor_use_pip", true),
            useAdvancedStats = prefs.getBoolean("advisor_use_advanced_stats", true),
            showRecommendation = prefs.getBoolean("advisor_show_recommendation", true),
            fontScale = prefs.getFloat("advisor_font_scale", 1.0f),
            windowWidthPct = prefs.getInt("advisor_window_width_pct", 100),
            windowHeightPct = prefs.getInt("advisor_window_height_pct", 100)
        )
    }

    // Load particular opponent profile statistics based on name/nickname as requested
    fun loadPlayerStats(nickname: String): PlayerStats {
        val sanitized = nickname.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
        val prefix = "player_${sanitized}_"
        if (!prefs.contains("${prefix}hands_played")) {
            return PlayerStats(nickname = nickname) // Return default empty profile
        }
        return PlayerStats(
            nickname = nickname,
            handsPlayed = prefs.getInt("${prefix}hands_played", 0),
            vpipCount = prefs.getInt("${prefix}vpip_count", 0),
            pfrCount = prefs.getInt("${prefix}pfr_count", 0),
            foldTo3betCount = prefs.getInt("${prefix}fold_to_3bet_count", 0),
            showdownWins = prefs.getInt("${prefix}showdown_wins", 0),
            showdownTotal = prefs.getInt("${prefix}showdown_total", 0),
            aggressionCount = prefs.getInt("${prefix}aggression_count", 0),
            aggressionCalls = prefs.getInt("${prefix}aggression_calls", 0),
            histVpip = if (prefs.contains("${prefix}histVpip")) prefs.getFloat("${prefix}histVpip", -1f) else null,
            histPfr = if (prefs.contains("${prefix}histPfr")) prefs.getFloat("${prefix}histPfr", -1f) else null,
            hist3Bet = if (prefs.contains("${prefix}hist3Bet")) prefs.getFloat("${prefix}hist3Bet", -1f) else null,
            histFoldTo3Bet = if (prefs.contains("${prefix}histFoldTo3Bet")) prefs.getFloat("${prefix}histFoldTo3Bet", -1f) else null,
            histCBet = if (prefs.contains("${prefix}histCBet")) prefs.getFloat("${prefix}histCBet", -1f) else null,
            histFoldToCBet = if (prefs.contains("${prefix}histFoldToCBet")) prefs.getFloat("${prefix}histFoldToCBet", -1f) else null,
            histSteal = if (prefs.contains("${prefix}histSteal")) prefs.getFloat("${prefix}histSteal", -1f) else null,
            histCheckRaise = if (prefs.contains("${prefix}histCheckRaise")) prefs.getFloat("${prefix}histCheckRaise", -1f) else null,
            histWtsd = if (prefs.contains("${prefix}histWtsd")) prefs.getFloat("${prefix}histWtsd", -1f) else null,
            histWsd = if (prefs.contains("${prefix}histWsd")) prefs.getFloat("${prefix}histWsd", -1f) else null,
            lastUpdated = prefs.getLong("${prefix}lastUpdated", 0L)
        )
    }

    // Saves customized opponent profile statistics based on name/nickname
    fun savePlayerStats(stats: PlayerStats) {
        val sanitized = stats.nickname.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
        val prefix = "player_${sanitized}_"
        prefs.edit().apply {
            putInt("${prefix}hands_played", stats.handsPlayed)
            putInt("${prefix}vpip_count", stats.vpipCount)
            putInt("${prefix}pfr_count", stats.pfrCount)
            putInt("${prefix}fold_to_3bet_count", stats.foldTo3betCount)
            putInt("${prefix}showdown_wins", stats.showdownWins)
            putInt("${prefix}showdown_total", stats.showdownTotal)
            putInt("${prefix}aggression_count", stats.aggressionCount)
            putInt("${prefix}aggression_calls", stats.aggressionCalls)
            
            stats.histVpip?.let { putFloat("${prefix}histVpip", it) }
            stats.histPfr?.let { putFloat("${prefix}histPfr", it) }
            stats.hist3Bet?.let { putFloat("${prefix}hist3Bet", it) }
            stats.histFoldTo3Bet?.let { putFloat("${prefix}histFoldTo3Bet", it) }
            stats.histCBet?.let { putFloat("${prefix}histCBet", it) }
            stats.histFoldToCBet?.let { putFloat("${prefix}histFoldToCBet", it) }
            stats.histSteal?.let { putFloat("${prefix}histSteal", it) }
            stats.histCheckRaise?.let { putFloat("${prefix}histCheckRaise", it) }
            stats.histWtsd?.let { putFloat("${prefix}histWtsd", it) }
            stats.histWsd?.let { putFloat("${prefix}histWsd", it) }
            putLong("${prefix}lastUpdated", stats.lastUpdated)
            apply()
        }
    }

    // Returns a list of some pre-populated standard player nicknames to make the interface extremely helpful
    fun getPlayerProfilesList(): List<String> {
        val set = mutableSetOf<String>()
        // Load whatever other nicknames have been saved in prefs
        prefs.all.keys.forEach { key ->
            if (key.startsWith("player_") && key.endsWith("_hands_played")) {
                val extracted = key.removePrefix("player_").removeSuffix("_hands_played")
                if (extracted.isNotEmpty()) {
                    set.add(extracted.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                }
            }
        }
        return set.toList().sorted()
    }
}
