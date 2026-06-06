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
    val explanation: String
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
        lastActions: String = ""
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
        val activePot = potSize + betToCall
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f

        // 4. Source 4: Pot Odds & Margin Factor (EV_OddsGap)
        val s4 = if (betToCall > 0f) {
            val gap = s1 - potOdds
            ((gap + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            (s1 * 1.15f).coerceIn(0f, 1f)
        }

        // Pure GTO Math Core - clean of any psychological or position heuristics
        val l1Score = (s1 * 0.35f) + (s2 * 0.25f) + (s3 * 0.20f) + (s4 * 0.20f)

        // Perfect GTO Tighter Standards for Multi-Way fields to avoid equity dilution
        val isMultiway = activeOpponentsCount >= 2
        val raiseThreshold = if (isMultiway) 0.68f else 0.60f
        val betThreshold = if (isMultiway) 0.55f else 0.45f

        val action: String
        val explanation: String
        val profitableCall = s1 > potOdds

        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)
        fun fmt(f: Float): String = String.format(Locale.US, "%.1f", f)

        val mRatio = if (heroStack > 0 && bigBlind > 0) (heroStack.toFloat() / bigBlind) else 20.0f

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
                    explanation = "Колл: выгодно по шансам банка, эквити ${pct(s1)}% > шансы ${pct(potOdds)}%"
                }
            } else {
                // Unprofitable call based on raw win percentage. Check if playability score offers GTO defense
                if (l1Score > 0.52f && s1 > 0.28f) {
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
            "RAISE", "ALL-IN" -> (((l1Score - 0.4f) / 0.6f) * 100f).coerceIn(10f, 98f)
            "FOLD" -> (((0.5f - l1Score) / 0.5f) * 100f).coerceIn(10f, 98f)
            "CALL" -> ((1.0f - kotlin.math.abs(l1Score - 0.45f) / 0.55f) * 100f).coerceIn(10f, 98f)
            "CHECK", "BET" -> ((1.0f - kotlin.math.abs(l1Score - 0.35f) / 0.65f) * 100f).coerceIn(10f, 98f)
            else -> 50f
        }

        return Recommendation(action, confidence, detailExplanation)
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
        heroStack: Float
    ): Recommendation {
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("FOLD", 100f, "Enter cards.")
        }

        // Base equity is from advanced simulation
        val equity = (simResult?.heroWinPct ?: 0f) / 100f

        // 1. Calculate weighted table averages across all players with stats for robust fallback
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

        // 2. Loop through EACH active opponent to aggregate individual math adjustments
        val activeOpponents = opponents.filter { it.isActive }
        var totalDeltaAdj = 0f
        var totalFocusAdj = 0f
        var totalVpipAdj = 0f
        var countedOpponents = 0

        for (opp in activeOpponents) {
            val stats = opp.stats
            val hasProfile = stats?.histVpip != null
            val hasSession = stats != null && stats.handsPlayed > 2

            // Fallback strategy: try Profile (hist), then try duplicate (session), then blend population average and table average
            val vpip = stats?.histVpip ?: stats?.vpip ?: (25f * 0.4f + avgTableVpip * 0.6f)
            val pfr = stats?.histPfr ?: stats?.pfr ?: (15f * 0.4f + avgTablePfr * 0.6f)
            val wtsd = stats?.histWtsd ?: (30f * 0.4f + avgTableWtsd * 0.6f)
            val wsd = stats?.histWsd ?: (50f * 0.4f + avgTableWsd * 0.6f)

            val deltaSD = wsd - wtsd
            val preflopFocus = if (vpip > 0f) pfr / vpip else 0f

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
                oppFocusAdj = 0.03f  // Calling station -> value bet wider
            } else if (preflopFocus > 0.8f && vpip < 20f) {
                oppFocusAdj = -0.04f // Aggressive preflop nit -> play tighter
            }

            // VPIP adjustments
            if (vpip < 15f) {
                oppVpipAdj = -0.06f // Extreme nit -> play tighter
            } else if (vpip > 45f) {
                oppVpipAdj = 0.04f  // Loose fish -> exploit with wider value
            }

            // Alternative Mechanism: completely disable personal data adjustments for blank players
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
        val rawNetAdj = totalDeltaAdj + totalFocusAdj + totalVpipAdj
        val netAdjustment = rawNetAdj.coerceIn(-0.15f, 0.15f)
        val adjustedScore = (equity + netAdjustment).coerceIn(0f, 1f)

        // Synthesize average metrics for the UI/explanations
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

        // Sklansky factor
        val sklanskyGroup = getSklanskyGroup(heroCard1, heroCard2)

        // Max opponent bet
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) maxOpponentBet = pBet
        }
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)
        val activePot = potSize + betToCall
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        val profitableCall = adjustedScore > potOdds
        val isPreflop = board.filterNotNull().isEmpty()

        val action: String
        val explanation: String
        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)

        if (betToCall > 0) {
            if (profitableCall) {
                if (adjustedScore > 0.65f || (isPreflop && sklanskyGroup <= 2 && adjustedScore > 0.45f)) {
                    val mRatio = heroStack / (bigBlind + 0.01f)
                    if (mRatio < 10.0f || adjustedScore > 0.80f) {
                        action = "ALL-IN"
                        explanation = "L2 ОЛЛ-ИН: сила ${pct(adjustedScore)}%, M=${String.format(Locale.US, "%.1f", mRatio)}"
                    } else {
                        action = "RAISE"
                        explanation = "L2 Рейз (велью): выгоден, сила ${pct(adjustedScore)}%, ΔSD ${avgDeltaSD.toInt()}%"
                    }
                } else {
                    action = "CALL"
                    explanation = "L2 Колл (по оддсам): сила ${pct(adjustedScore)}% > шансы ${pct(potOdds)}%"
                }
            } else {
                if (adjustedScore > 0.48f && (isPreflop && sklanskyGroup <= 4)) {
                    action = "CALL"
                    explanation = "L2 Колл (полублеф): сильная позиция, сила ${pct(adjustedScore)}%"
                } else {
                    action = "FOLD"
                    explanation = "L2 Фолд: сила ${pct(adjustedScore)}% < шансы ${pct(potOdds)}%"
                }
            }
        } else {
            if (adjustedScore > 0.60f) {
                action = "RAISE"
                explanation = "L2 Рейз: превосходная сила ${pct(adjustedScore)}%, КПФ ${String.format(Locale.US, "%.1f", avgFocus)}"
            } else if (adjustedScore > 0.45f || (isPreflop && sklanskyGroup <= 3)) {
                action = "BET"
                explanation = "L2 Ставка (велью): сила ${pct(adjustedScore)}%, КПФ ${String.format(Locale.US, "%.1f", avgFocus)}"
            } else if (adjustedScore > 0.35f) {
                action = "CHECK"
                explanation = "L2 Чек: умеренно, сила ${pct(adjustedScore)}%"
            } else {
                action = "CHECK"
                explanation = "L2 Чек: слабость, сила ${pct(adjustedScore)}%"
            }
        }

        val confidenceValue = when(action) {
            "RAISE", "ALL-IN" -> (((adjustedScore - 0.4f) / 0.6f) * 100f).coerceIn(10f, 95f)
            "FOLD" -> (((0.5f - adjustedScore) / 0.5f) * 100f).coerceIn(10f, 95f)
            "CALL" -> ((1.0f - kotlin.math.abs(adjustedScore - 0.45f) / 0.55f) * 100f).coerceIn(10f, 95f)
            "CHECK", "BET" -> ((1.0f - kotlin.math.abs(adjustedScore - 0.35f) / 0.65f) * 100f).coerceIn(10f, 95f)
            else -> 50f
        }

        return Recommendation(action, confidenceValue, explanation)
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
        lastActions: String = ""
    ): Recommendation {
        // Advanced logic uses the 10 parameters more deeply
        if (heroCard1 == null || heroCard2 == null) {
            return Recommendation("FOLD", 100f, "Enter cards.")
        }

        val equity = (simResult?.heroWinPct ?: 0f) / 100f
        
        // Find the main opponent (most hands or currently active with highest aggression)
        val opponent = opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
        val profile = opponent?.stats
        
        var adjustedScore = equity

        // Pot odds and profit check
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) maxOpponentBet = pBet
        }
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)

        if (profile != null) {
            // Integrate ALL 10 parameters into the calculation
            val vpip = profile.histVpip ?: profile.vpip
            val pfr = profile.histPfr ?: profile.pfr
            val bet3 = profile.hist3Bet ?: profile.foldTo3bet // fallback
            val fold3 = profile.histFoldTo3Bet ?: 45f
            val cbet = profile.histCBet ?: 55f
            val foldCbet = profile.histFoldToCBet ?: 45f
            val steal = profile.histSteal ?: 35f
            val cr = profile.histCheckRaise ?: 10f
            val wtsd = profile.histWtsd ?: 30f
            val wsd = profile.histWsd ?: 50f

            val isPreflop = board.filterNotNull().isEmpty()
            val isPostflop = !isPreflop
            var exploitReason = ""
            
            // Эксплуатационная машина на основе 10 параметров:
            
            // 1. Поздняя позиция и защита блайндов (Steal)
            if (isPreflop && (position == TablePosition.SB || position == TablePosition.BB) && steal > 40f) {
                adjustedScore += 0.15f
                if (exploitReason.isEmpty()) exploitReason = "Steal >40% (Авто-Защита)"
            }
            
            // 2. Эксплойт Фолд эквити (Fold to 3-Bet, Fold to C-Bet)
            if (isPreflop && fold3 > 60f) {
                if (adjustedScore < 0.6f && betToCall > 0) {
                    adjustedScore += 0.20f // бустим для возможного блеф-рейза
                    if (exploitReason.isEmpty()) exploitReason = "Оверфолд на 3-Bet (>60%)"
                }
            } else if (isPostflop && betToCall == 0f && foldCbet > 55f) {
                if (adjustedScore < 0.5f) {
                    adjustedScore += 0.20f
                    if (exploitReason.isEmpty()) exploitReason = "Авто-блеф (Fold to CB >55%)"
                }
            }
            
            // 3. Вычисляем тип оппонента (Archetypes) и подстраиваем общую ширину
            val gap = profile.vpipPfrGap
            val honesty = profile.honestyIndex
            
            if (wtsd > 32f || (gap > 20f && vpip > 35f) || honesty < 18f) {
                // Calling Station (Телефон) - Не блефуем, только велью
                if (adjustedScore < 0.5f) {
                    adjustedScore -= 0.15f // Понижаем силу слабых рук (нет фолд эквити)
                } else {
                    adjustedScore += 0.15f // Улучшаем силу велью-бета (шире велью)
                }
                if (exploitReason.isEmpty()) exploitReason = "Опп - телефон (Нет блефам)"
            } else if ((wtsd < 25f && wsd > 55f) || honesty > 30f) {
                // Nit (Скала)
                if (betToCall > bigBlind) {
                    adjustedScore -= 0.20f // Скала ставит - у него натс, сильно занижаем наше эквити
                    if (exploitReason.isEmpty()) exploitReason = "Респект агрессии (Скала)"
                } else if (betToCall == 0f && adjustedScore < 0.7f) {
                    adjustedScore += 0.10f // Можно пытаться подблефовывать мелкие банки
                }
            }
            
            // 4. Реагируем на C-Bet оппонента
            if (isPostflop && betToCall > 0 && cbet > 70f) {
                // Он ставит контбет слишком часто - можно флотить.
                if (adjustedScore in 0.35f..0.6f) {
                    adjustedScore += 0.15f // Не падаем с маргинальным эквити
                    if (exploitReason.isEmpty()) exploitReason = "Флоат против шир. CB"
                }
            }

            // 5. Эксплойт агрессивных префлоперов, падающих на отпор
            if (isPreflop && profile.preflopBluffingTendency > 15f && betToCall > 0) {
                 if (adjustedScore in 0.45f..0.7f) {
                     adjustedScore += 0.25f // Огромный буст под блеф 3-бет или пуш, так как он открывается широко и часто фолдит
                     if (exploitReason.isEmpty()) exploitReason = "Опп открывает мусор (Блеф 3-бет/Пуш)"
                 }
            }
            
            // 5. Уважение чек-рейза
            if (isPostflop && cr > 15f && betToCall > 0) {
                adjustedScore -= 0.15f // Осторожно
                if (exploitReason.isEmpty()) exploitReason = "Агрессивный Чек-Рейз!"
            }
            
            // 6. Учет диапазонов Sklansky
            val sRange = getSklanskyRangeForVpip(vpip)
            val mySGroup = getSklanskyGroup(heroCard1, heroCard2)
            if (mySGroup <= sRange && adjustedScore > 0.4f) {
                 adjustedScore += 0.05f 
            }
            
            // Pass the exploitReason back through the system implicitly... wait, we need it in explanation.
            // Let's store it so we can append it later.
            // But we don't have a way to pass it down easily outside the if(profile != null) block.
        }

        val activePot = potSize + betToCall
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        
        val isProfitable = adjustedScore > potOdds
        
        val action: String
        val confidence: Float
        var explanation: String
        
        when {
            adjustedScore > 0.7f -> {
                action = "RAISE"
                confidence = (adjustedScore * 100f).coerceAtLeast(0f)
                explanation = "Adv: мощное EV ${String.format("%.0f", adjustedScore*100)}%"
            }
            adjustedScore > 0.5f -> {
                action = if (betToCall > 0) "CALL" else "CHECK"
                confidence = 70f
                explanation = "Adv: прибыльный колл/чек"
            }
            isProfitable -> {
                action = if (betToCall > 0) "CALL" else "CHECK"
                confidence = 55f
                explanation = "Adv: маргинально, EV>0"
            }
            else -> {
                action = if (betToCall > 0) "FOLD" else "CHECK"
                confidence = 80f
                explanation = "Adv: отрицательное EV"
            }
        }
        
        // Retrieve exploit reason if available
        var expReason = ""
        if (profile != null) {
            val isPreflop = board.filterNotNull().isEmpty()
            val isPostflop = !isPreflop
            val steal = profile.histSteal ?: 35f
            val fold3 = profile.histFoldTo3Bet ?: 45f
            val foldCbet = profile.histFoldToCBet ?: 45f
            val gap = (profile.histVpip ?: profile.vpip) - (profile.histPfr ?: profile.pfr)
            val wtsd = profile.histWtsd ?: 30f
            val wsd = profile.histWsd ?: 50f
            val cr = profile.histCheckRaise ?: 10f
            val cbet = profile.histCBet ?: 55f
            val vpip = profile.histVpip ?: profile.vpip

            if (isPreflop && (position == TablePosition.SB || position == TablePosition.BB) && steal > 40f) {
                expReason = "Steal >40% авто-защита"
            } else if (isPreflop && fold3 > 60f && adjustedScore >= 0.5f && action == "RAISE") {
                expReason = "Оверфолд на 3-Bet"
            } else if (isPostflop && betToCall == 0f && foldCbet > 55f && action != "FOLD") {
                expReason = "Авто-блеф CB"
            } else if (wtsd > 32f || (gap > 20f && vpip > 35f)) {
                expReason = "Опп - телефон (респект)"
            } else if (wtsd < 25f && wsd > 55f && betToCall > bigBlind) {
                expReason = "Респект агрессии Скале!"
            } else if (isPostflop && betToCall > 0 && cbet > 70f && action == "CALL") {
                expReason = "Флоат против шир. CB"
            } else if (isPostflop && cr > 15f && betToCall > 0) {
                expReason = "Агрессивный Чек-Рейз!"
            }
        }
        
        if (expReason.isNotEmpty()) {
            explanation = "$expReason | $explanation"
        }

        return Recommendation(action, confidence, explanation)
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
        lastActions: String = ""
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
            val bigBlinds = heroStack / (bigBlind.coerceAtLeast(1f))
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
                } else {
                    customExplanation = "DNA ($dnaProfile): Сброс рук"
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
                        }
                    }
                    "Хамелеон" -> {
                        // Chameleon is tight-passive. Overfold to his bets, steal his blinds.
                        if (action == "RAISE" && baseL3.confidence < 65f) {
                            action = "FOLD"
                            confidence = 90f
                            customExplanation = "DNA: Падаем под силу Хамелеона"
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

        return Recommendation(action, confidence, customExplanation)
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
