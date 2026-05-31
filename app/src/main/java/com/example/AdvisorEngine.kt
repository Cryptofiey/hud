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
    val histWsd: Float? = null
) {
    val vpip: Float get() = if (handsPlayed > 0) (vpipCount.toFloat() / handsPlayed * 100f) else 0f
    val pfr: Float get() = if (handsPlayed > 0) (pfrCount.toFloat() / handsPlayed * 100f) else 0f
    val foldTo3bet: Float get() = if (handsPlayed > 0) (foldTo3betCount.toFloat() / handsPlayed * 100f) else 0f
    val showdownWinPct: Float get() = if (showdownTotal > 0) (showdownWins.toFloat() / showdownTotal * 100f) else 0f
    val aggressionFactor: Float get() = if (aggressionCalls > 0) (aggressionCount.toFloat() / aggressionCalls.toFloat()) else aggressionCount.toFloat()
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

    // Variables mapping from Java implementation
    private var weightEquity = 0.30f
    private var weightSklansky = 0.20f
    private var weightStats = 0.20f
    private var weightPosition = 0.15f
    private var weightStage = 0.10f
    private var weightDynamic = 0.05f

    // 4. Advanced Advisor Recommendation Engine (Intellectual solver port)
    fun computeRecommendation(
        heroCard1: Card?,
        heroCard2: Card?,
        board: List<Card?>,
        potSize: Int,
        heroBet: Int,
        opponents: List<OpponentState>,
        activeOpponentsCount: Int,
        simResult: SimulationResult?,
        settings: AdvisorSettings,
        position: TablePosition,
        stage: TournamentStage,
        smallBlind: Int,
        bigBlind: Int,
        heroStack: Int,
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

        // 1. Extract equity
        val equity = (simResult?.heroWinPct ?: 0f) / 100f

        // 2. Equity factor
        val equityFactor: Float = when {
            equity > 0.65f -> minOf(1.0f, (equity - 0.65f) / 0.35f)
            equity > 0.50f -> 0.5f
            equity > 0.35f -> 0.25f
            else -> 0.0f
        }

        // Calculate bets
        var maxOpponentBet = 0
        opponents.filter { it.isActive }.forEach { opp ->
            val pBet = if (settings.usePip) opp.betSize else 0
            if (pBet > maxOpponentBet) {
                maxOpponentBet = pBet
            }
        }
        val betToCall = maxOf(0, maxOpponentBet - heroBet).toFloat()
        val activePot = potSize.toFloat() + betToCall

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
                val hasHistorical = profile.histVpip != null
                val hasEnoughLocalHands = profile.handsPlayed >= 20
                
                if (hasHistorical || hasEnoughLocalHands) {
                    val vpip = (profile.histVpip ?: profile.vpip) / 100f
                    val pfr = (profile.histPfr ?: profile.pfr) / 100f
                    
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
                    val wtsd = profile.histWtsd?.div(100f) ?: 0.30f
                    val wsd = profile.histWsd?.div(100f) ?: 0.50f
                    
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

        when {
            betToCall > 0 && !profitableCall && rawScore < 0.35f -> {
                action = "FOLD"
                explanation = "Fold: equity ${pct(equity)}% < pot odds ${pct(potOdds)}%"
            }
            equity > 0.65f && rawScore > 0.6f -> {
                if (mRatio < 10.0f || (equity > 0.80f && positionFactor > 0.5f)) {
                    action = "ALL-IN"
                    explanation = "ALL-IN: premium ${pct(equity)}%, M=${fmt(mRatio)}"
                } else {
                    action = "RAISE"
                    explanation = "Raise: equity ${pct(equity)}%"
                }
            }
            equity > 0.50f && rawScore > 0.4f -> {
                if (betToCall > 0 && profitableCall && rawScore > 0.5f) {
                    action = "RAISE"
                    explanation = "Raise: profitable, equity ${pct(equity)}%"
                } else if (betToCall > 0) {
                    action = "CALL"
                    explanation = "Call: marginal ${pct(equity)}%"
                } else {
                    action = "CHECK"
                    explanation = "Check: moderate ${pct(equity)}%"
                }
            }
            betToCall > 0 && rawScore < 0.3f -> {
                action = "FOLD"
                explanation = "Fold: weak ${pct(equity)}%"
            }
            betToCall > 0 -> {
                action = "CALL"
                explanation = "Call: marginal, equity ${pct(equity)}%"
            }
            else -> {
                action = "CHECK"
                explanation = "Check: ${pct(equity)}% equity"
            }
        }

        return Recommendation(action, rawScore * 100f, explanation)
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
            histWsd = if (prefs.contains("${prefix}histWsd")) prefs.getFloat("${prefix}histWsd", -1f) else null
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
