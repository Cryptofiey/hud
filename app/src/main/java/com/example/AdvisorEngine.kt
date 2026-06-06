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

    // 4. Advanced Advisor Recommendation Engine (Intellectual solver port)
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

        // 0. Local weights definition to prevent singleton state leakage across calls
        var weightEquity = 0.30f
        var weightSklansky = 0.20f
        var weightStats = 0.20f
        val weightPosition = 0.15f
        val weightStage = 0.10f
        val weightDynamic = 0.05f

        // 1. Extract equity
        val equity = (simResult?.heroWinPct ?: 0f) / 100f

        // 2. Equity factor (Fixed inverse/inverted step gap bug where 0.66 equity performed worse than 0.64)
        val equityFactor: Float = when {
            equity > 0.65f -> 0.5f + ((equity - 0.65f) / 0.35f) * 0.5f
            equity > 0.50f -> 0.5f
            equity > 0.35f -> 0.25f
            else -> 0.0f
        }

        // Calculate bets
        var maxOpponentBet = 0f
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0f
            if (pBet > maxOpponentBet) {
                maxOpponentBet = pBet
            }
        }
        val betToCall = maxOf(0f, maxOpponentBet - heroBet)
        val activePot = potSize + betToCall

        // 3. Pot odds
        val potOdds = if (betToCall > 0) betToCall / (activePot + betToCall) else 0.0f
        val profitableCall = equity > potOdds

        // 4. Sklansky factor
        val sklanskyGroup = getSklanskyGroup(heroCard1, heroCard2)
        val sklanskyFactor = when (sklanskyGroup) {
            1 -> 1.0f
            2 -> 0.9f
            3 -> 0.8f
            4 -> 0.6f
            5 -> 0.5f
            6 -> 0.4f
            7 -> 0.2f
            else -> 0.1f
        }

        // 5. Stats factor (Historical Data Filter)
        var statsFactor = 0.5f
        if (settings.useAdvancedStats && opponents.isNotEmpty()) {
            val opponent = opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
            val profile = opponent?.stats

            if (profile != null) {
                val useVpip = PokerHudSharedState.useVpipPfrFilter.value
                val useWsd = PokerHudSharedState.useWsdWtsdFilter.value

                val hasHistorical = profile.histVpip != null && useVpip
                val hasEnoughLocalHands = profile.handsPlayed >= 20
                
                if (hasHistorical || hasEnoughLocalHands) {
                    val vpip = if (useVpip && profile.histVpip != null) profile.histVpip!! / 100f else profile.vpip / 100f
                    val pfr = if (useVpip && profile.histPfr != null) profile.histPfr!! / 100f else profile.pfr / 100f
                    
                    // Specific logic for VPIP and Sklansky relationship:
                    // If VPIP is very low (tight player), they only play premium hands. We need premium hands to engage.
                    if (vpip < 0.20f) {
                        statsFactor = 0.3f
                    } 
                    // If VPIP is high (loose player), they play garbage. We can have higher confidence.
                    else if (vpip > 0.40f) {
                        statsFactor = 0.8f
                    }
                    
                    // If player folds to C-Bet very rarely (calling station), decrease confidence on bluffs
                    val foldToCBet = profile.histFoldToCBet?.div(100f) ?: 0.5f
                    if (foldToCBet < 0.30f && betToCall == 0f) {
                        statsFactor -= 0.1f // Don't bluff a calling station
                    } else if (foldToCBet > 0.60f && betToCall == 0f) {
                        statsFactor += 0.1f // Good target for C-bet squeeze
                    }
                    
                    // WTSD (Went to Showdown) and WSD (Won at Showdown)
                    val wtsd = if (useWsd && profile.histWtsd != null) profile.histWtsd!! / 100f else 0.30f
                    val wsd = if (useWsd && profile.histWsd != null) profile.histWsd!! / 100f else 0.50f
                    
                    if (wtsd > 0.35f && wsd < 0.45f) {
                        // Calling station that loses often. Value bet them heavily!
                        statsFactor += 0.15f 
                    } else if (wtsd < 0.25f && wsd > 0.55f) {
                        // Tight player that only bluffs occasionally or only shows nuts
                        statsFactor -= 0.1f
                    }

                    // Constrain the statsFactor between 0 and 1
                    statsFactor = maxOf(0.1f, minOf(0.9f, statsFactor))

                    val handsWeight = if (hasHistorical) 1.0f else minOf(1.0f, profile.handsPlayed / 200.0f)
                    weightStats = 0.20f + (0.15f * handsWeight)
                    weightSklansky = 0.20f - (0.05f * handsWeight)
                    weightEquity = 1.0f - weightStats - weightSklansky - weightPosition - weightStage - weightDynamic
                }
            }
        }

        // 6. Position factor
        val posIndex = when (position) {
            TablePosition.SB -> 0
            TablePosition.BB -> 1
            TablePosition.UTG -> 2
            TablePosition.MP -> 3
            TablePosition.CO -> 4
            TablePosition.BTN -> 5
        }
        val lateThreshold = maxOf(3, activeOpponentsCount - 2)
        val positionFactor = when {
            posIndex >= lateThreshold -> 1.0f
            posIndex >= 3 -> 0.6f
            posIndex >= 1 -> 0.3f
            else -> 0.1f
        }

        // 7. Stage factor
        val mRatio = if (heroStack > 0 && bigBlind > 0) (heroStack.toFloat() / bigBlind) / maxOf(1, activeOpponentsCount * 2) else 20.0f
        val blindLevel = when (stage) {
            TournamentStage.LATE -> 2
            TournamentStage.MIDDLE -> 1
            TournamentStage.EARLY -> 0
        }
        val stageFactor = when {
            blindLevel >= 2 || mRatio < 10.0f -> 0.3f
            blindLevel >= 1 || mRatio < 20.0f -> 0.5f
            else -> 0.8f
        }

        // 8. Dynamic factor
        var dynamicFactor = 0.5f
        if (lastActions.length >= 3) {
            var raiseCount = 0
            for (c in lastActions) {
                if (c == 'R' || c == 'A') raiseCount++
            }
            if (raiseCount >= 3) {
                dynamicFactor = 0.3f
            } else if (raiseCount == 0) {
                dynamicFactor = 0.7f
            }
        }

        // 9. Weighted score
        val rawScore = (equityFactor * weightEquity) +
                (sklanskyFactor * weightSklansky) +
                (statsFactor * weightStats) +
                (positionFactor * weightPosition) +
                (stageFactor * weightStage) +
                (dynamicFactor * weightDynamic)

        // 10. Action selection
        val action: String
        val explanation: String

        fun pct(f: Float): String = String.format(Locale.US, "%.0f", f * 100)
        fun fmt(f: Float): String = String.format(Locale.US, "%.1f", f)

        if (betToCall > 0) {
            if (profitableCall) {
                // Positively expected call (equity > potOdds)
                if (equity > 0.65f && rawScore > 0.6f) {
                    if (mRatio < 10.0f || (equity > 0.80f && positionFactor > 0.5f)) {
                        action = "ALL-IN"
                        explanation = "ОЛЛ-ИН: премиум ${pct(equity)}%, M=${fmt(mRatio)}"
                    } else {
                        action = "RAISE"
                        explanation = "Рейз: премиум эквити ${pct(equity)}%"
                    }
                } else if (equity > 0.50f && rawScore > 0.4f && rawScore > 0.5f) {
                    action = "RAISE"
                    explanation = "Рейз: выгодно, эквити ${pct(equity)}% > шансы ${pct(potOdds)}%"
                } else {
                    action = "CALL"
                    explanation = "Колл: выгодно по шансам, эквити ${pct(equity)}% > шансы ${pct(potOdds)}%"
                }
            } else {
                // Unprofitable call based on raw equity (equity <= potOdds)
                // Fold unless rawScore/hand playability is high (rawScore > 0.5f)
                if (rawScore > 0.5f && equity > 0.25f) {
                    if (equity > 0.50f && rawScore > 0.6f) {
                        action = "RAISE"
                        explanation = "Рейз (полублеф): сильный скор ${pct(rawScore)}, эквити ${pct(equity)}%"
                    } else {
                        action = "CALL"
                        explanation = "Колл (полублеф): сильная позиция/рука, эквити ${pct(equity)}%"
                    }
                } else {
                    action = "FOLD"
                    explanation = "Фолд: эквити ${pct(equity)}% < шансы банка ${pct(potOdds)}%"
                }
            }
        } else {
            // No bet to call (betToCall == 0) — We check or bet/raise.
            if (equity > 0.65f && rawScore > 0.6f) {
                if (mRatio < 10.0f || (equity > 0.80f && positionFactor > 0.5f)) {
                    action = "ALL-IN"
                    explanation = "ОЛЛ-ИН: премиум ${pct(equity)}%, M=${fmt(mRatio)}"
                } else {
                    action = "RAISE"
                    explanation = "Рейз: превосходное эквити ${pct(equity)}%"
                }
            } else if (equity > 0.50f && rawScore > 0.4f) {
                action = "RAISE"
                explanation = "Рейз: хорошее эквити ${pct(equity)}%"
            } else if (rawScore > 0.5f) {
                action = "RAISE"
                explanation = "Рейз (блеф/защита): эквити ${pct(equity)}%, позиция"
            } else {
                action = "CHECK"
                explanation = "Чек: умеренно, эквити ${pct(equity)}%"
            }
        }

        val confidence = when(action) {
            "RAISE", "ALL-IN" -> (((rawScore - 0.4f) / 0.6f) * 100f).coerceIn(0f, 100f)
            "FOLD" -> (((0.5f - rawScore) / 0.5f) * 100f).coerceIn(0f, 100f)
            "CALL" -> ((1.0f - kotlin.math.abs(rawScore - 0.45f) / 0.55f) * 100f).coerceIn(0f, 100f)
            "CHECK" -> ((1.0f - kotlin.math.abs(rawScore - 0.35f) / 0.65f) * 100f).coerceIn(0f, 100f)
            else -> 50f
        }

        return Recommendation(action, confidence, explanation)
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

        // Base equity is from advanced simulation (which filters opponent ranges using historical vpip/pfr data)
        val equity = (simResult?.heroWinPct ?: 0f) / 100f

        // Get main active opponent stats
        val opponent = opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
        val profile = opponent?.stats

        // Synthetic parameter 1: Delta SD = WSD - WTSD (Showdown win vs Showdown entry)
        val wtsd = profile?.histWtsd ?: 30f
        val wsd = profile?.histWsd ?: 50f
        val deltaSD = wsd - wtsd

        // Synthetic parameter 2: Preflop Focus Factor = PFR / VPIP
        val vpip = profile?.histVpip ?: profile?.vpip ?: 30f
        val pfr = profile?.histPfr ?: profile?.pfr ?: 20f
        val preflopFocus = if (vpip > 0) (pfr / vpip) else 0f

        // Adjust raw mathematics score using these synthetic calculations
        var adjustedScore = equity

        // If high deltaSD (e.g. > 15%), they rarely get to showdown without strong hands. Play tighter.
        if (deltaSD > 15f) {
            adjustedScore -= 0.05f
        } else if (deltaSD < -5f) {
            // Low deltaSD (< -5%), they are regular folders or showdown losers, we can bet/raise more freely.
            adjustedScore += 0.05f
        }

        // Focus factor: if close to 1.0, they play extremely focused/solid preflop.
        // If very low (< 0.3), they are hyper passive fish who check/call preflop but don't raise.
        if (preflopFocus < 0.3f && vpip > 35f) {
            // Passive calling station. Don't bluff him, but value bet wider.
            adjustedScore += 0.03f
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

        // Actions choice
        val action: String
        val explanation: String
        val isPreflop = board.filterNotNull().isEmpty()

        if (betToCall > 0) {
            val callMargin = adjustedScore - potOdds
            when {
                callMargin > 0.15f || (isPreflop && sklanskyGroup <= 2 && adjustedScore > 0.45f) -> {
                    action = "RAISE"
                    explanation = "Рейз на велью, ΔSD ${deltaSD.toInt()}%"
                }
                callMargin >= 0f -> {
                    action = "CALL"
                    explanation = "Математический колл"
                }
                else -> {
                    action = "FOLD"
                    explanation = "Фолд по оддсам"
                }
            }
        } else {
            when {
                adjustedScore > 0.52f || (isPreflop && sklanskyGroup <= 3) -> {
                    action = "BET"
                    explanation = "Математический бет, КПФ ${String.format(Locale.US, "%.1f", preflopFocus)}"
                }
                else -> {
                    action = "CHECK"
                    explanation = "Математический чек"
                }
            }
        }

        val confidenceValue = (adjustedScore * 100f).coerceIn(10f, 95f)

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
