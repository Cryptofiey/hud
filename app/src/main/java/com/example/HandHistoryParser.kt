package com.example

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

object HandHistoryParser {
    private const val TAG = "HandHistoryParser"

    fun parseHandHistory(inputStream: InputStream, prefsManager: PreferencesManager): Int {
        var handsParsed = 0
        val hands = mutableListOf<List<String>>()
        var currentHandLines = mutableListOf<String>()

        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("CoinPoker Hand #") || trimmed.startsWith("Hand #") || trimmed.startsWith("Game #") || trimmed.startsWith("Stage #")) {
                    if (currentHandLines.isNotEmpty()) {
                        hands.add(currentHandLines)
                        currentHandLines = mutableListOf()
                    }
                }
                if (trimmed.isNotEmpty()) {
                    currentHandLines.add(trimmed)
                }
                line = reader.readLine()
            }
            if (currentHandLines.isNotEmpty()) {
                hands.add(currentHandLines)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading hand history stream", e)
            return 0
        }

        Log.d(TAG, "Found ${hands.size} potential hands to parse.")

        // Database structures to aggregate player statistics over all parsed hands in this file
        val handsPlayedMap = mutableMapOf<String, Int>()
        val vpipCountMap = mutableMapOf<String, Int>()
        val pfrCountMap = mutableMapOf<String, Int>()
        val foldTo3BetCountMap = mutableMapOf<String, Int>()
        val showdownTotalMap = mutableMapOf<String, Int>()
        val showdownWinsMap = mutableMapOf<String, Int>()
        val aggressionCountMap = mutableMapOf<String, Int>()
        val aggressionCallsMap = mutableMapOf<String, Int>()

        val seatRegex = Regex("""^Seat\s+\d+:\s+([^(\n]+?)\s*\(""", RegexOption.IGNORE_CASE)
        val actionRegex = Regex("""^([^:\n]+):\s*(folds|calls|raises|bets|checks)""", RegexOption.IGNORE_CASE)

        for (handLines in hands) {
            if (handLines.isEmpty()) continue

            val seatPlayers = mutableSetOf<String>()
            val vpipPlayers = mutableSetOf<String>()
            val pfrPlayers = mutableSetOf<String>()
            var firstRaiserPreflop: String? = null
            var has3bet = false
            val foldedTo3bet = mutableSetOf<String>()
            val showdownTotalPlayers = mutableSetOf<String>()
            val showdownWinsPlayers = mutableSetOf<String>()
            val postFlopAggressionBetsAndRaises = mutableMapOf<String, Int>()
            val postFlopAggressionCalls = mutableMapOf<String, Int>()

            var isPreflop = true
            var isPostflop = false

            for (line in handLines) {
                // 1. Parse Seat Players (who was dealt cards)
                val seatMatch = seatRegex.find(line)
                if (seatMatch != null) {
                    val name = seatMatch.groupValues[1].trim()
                    if (name.isNotEmpty() && !name.contains("posts", ignoreCase = true) && !name.contains("small blind", ignoreCase = true) && !name.contains("big blind", ignoreCase = true)) {
                        seatPlayers.add(name)
                    }
                    continue
                }

                // 2. Action street transitions
                if (line.contains("*** HOLE CARDS ***", ignoreCase = true)) {
                    isPreflop = true
                    isPostflop = false
                    continue
                }
                if (line.contains("*** FLOP ***", ignoreCase = true) || line.contains("*** TURN ***", ignoreCase = true) || line.contains("*** RIVER ***", ignoreCase = true)) {
                    isPreflop = false
                    isPostflop = true
                    continue
                }
                if (line.contains("*** SHOW DOWN ***", ignoreCase = true) || line.contains("*** SHOWDOWN ***", ignoreCase = true) || line.contains("*** SUMMARY ***", ignoreCase = true)) {
                    isPreflop = false
                    isPostflop = false
                    
                    // Check for showdown metrics
                    for (player in seatPlayers) {
                        if (line.contains(player, ignoreCase = true)) {
                            if (line.contains("showed", ignoreCase = true) || line.contains("shows", ignoreCase = true) || line.contains("showed:", ignoreCase = true)) {
                                showdownTotalPlayers.add(player)
                            }
                            if (line.contains("won", ignoreCase = true) || line.contains("collected", ignoreCase = true)) {
                                showdownTotalPlayers.add(player)
                                showdownWinsPlayers.add(player)
                            }
                        }
                    }
                    continue
                }

                // Extra check in summary/showdown lines for players who win/show
                for (player in seatPlayers) {
                    if (line.contains(player, ignoreCase = true)) {
                        if (line.contains("showed", ignoreCase = true) || line.contains("shows", ignoreCase = true) || line.contains("showed:", ignoreCase = true)) {
                            showdownTotalPlayers.add(player)
                        }
                        if (line.contains("won", ignoreCase = true) || line.contains("collected", ignoreCase = true)) {
                            showdownTotalPlayers.add(player)
                            showdownWinsPlayers.add(player)
                        }
                    }
                }

                // 3. Parse Actions
                val actionMatch = actionRegex.find(line)
                if (actionMatch != null) {
                    val player = actionMatch.groupValues[1].trim()
                    val action = actionMatch.groupValues[2].trim().lowercase(Locale.ROOT)

                    // Ensure it is a valid active seat player
                    if (seatPlayers.contains(player)) {
                        if (isPreflop) {
                            if (action == "calls" || action == "raises") {
                                vpipPlayers.add(player)
                            }
                            if (action == "raises") {
                                pfrPlayers.add(player)
                                if (firstRaiserPreflop == null) {
                                    firstRaiserPreflop = player
                                } else {
                                    has3bet = true
                                }
                            }
                            if (action == "folds") {
                                if (has3bet && player == firstRaiserPreflop) {
                                    foldedTo3bet.add(player)
                                }
                            }
                        } else if (isPostflop) {
                            if (action == "bets" || action == "raises") {
                                postFlopAggressionBetsAndRaises[player] = (postFlopAggressionBetsAndRaises[player] ?: 0) + 1
                            } else if (action == "calls") {
                                postFlopAggressionCalls[player] = (postFlopAggressionCalls[player] ?: 0) + 1
                            }
                        }
                    }
                }
            }

            // Aggregate metrics from this hand into maps
            if (seatPlayers.isNotEmpty()) {
                handsParsed++
                for (player in seatPlayers) {
                    handsPlayedMap[player] = (handsPlayedMap[player] ?: 0) + 1
                    
                    if (vpipPlayers.contains(player)) {
                        vpipCountMap[player] = (vpipCountMap[player] ?: 0) + 1
                    }
                    if (pfrPlayers.contains(player)) {
                        pfrCountMap[player] = (pfrCountMap[player] ?: 0) + 1
                    }
                    if (foldedTo3bet.contains(player)) {
                        foldTo3BetCountMap[player] = (foldTo3BetCountMap[player] ?: 0) + 1
                    }
                    if (showdownTotalPlayers.contains(player)) {
                        showdownTotalMap[player] = (showdownTotalMap[player] ?: 0) + 1
                    }
                    if (showdownWinsPlayers.contains(player)) {
                        showdownWinsMap[player] = (showdownWinsMap[player] ?: 0) + 1
                    }
                    
                    val bAndR = postFlopAggressionBetsAndRaises[player] ?: 0
                    if (bAndR > 0) {
                        aggressionCountMap[player] = (aggressionCountMap[player] ?: 0) + bAndR
                    }
                    
                    val calls = postFlopAggressionCalls[player] ?: 0
                    if (calls > 0) {
                        aggressionCallsMap[player] = (aggressionCallsMap[player] ?: 0) + calls
                    }
                }
            }
        }

        // 4. Update Database for every player detected across all hands
        for (player in handsPlayedMap.keys) {
            val stats = prefsManager.loadPlayerStats(player)
            val addedHands = handsPlayedMap[player] ?: 0
            val addedVpip = vpipCountMap[player] ?: 0
            val addedPfr = pfrCountMap[player] ?: 0
            val addedFold3B = foldTo3BetCountMap[player] ?: 0
            val addedShowdownTot = showdownTotalMap[player] ?: 0
            val addedShowdownWin = showdownWinsMap[player] ?: 0
            val addedAggCount = aggressionCountMap[player] ?: 0
            val addedAggCalls = aggressionCallsMap[player] ?: 0

            val updatedStats = stats.copy(
                handsPlayed = stats.handsPlayed + addedHands,
                vpipCount = stats.vpipCount + addedVpip,
                pfrCount = stats.pfrCount + addedPfr,
                foldTo3betCount = stats.foldTo3betCount + addedFold3B,
                showdownTotal = stats.showdownTotal + addedShowdownTot,
                showdownWins = stats.showdownWins + addedShowdownWin,
                aggressionCount = stats.aggressionCount + addedAggCount,
                aggressionCalls = stats.aggressionCalls + addedAggCalls,
                lastUpdated = System.currentTimeMillis()
            )
            prefsManager.savePlayerStats(updatedStats)
            Log.d(TAG, "Processed player '$player': +$addedHands hands. Total hands now: ${updatedStats.handsPlayed}.")
        }

        return handsParsed
    }
}
