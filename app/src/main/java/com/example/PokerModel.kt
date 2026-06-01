package com.example

import java.util.Random
import java.util.Locale
import kotlin.random.Random as KotlinRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

enum class Rank(val value: Int, val symbol: String) {
    TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"), FIVE(5, "5"),
    SIX(6, "6"), SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"),
    TEN(10, "T"), JACK(11, "J"), QUEEN(12, "Q"), KING(13, "K"), ACE(14, "A");

    companion object {
        fun fromValue(v: Int): Rank = values().firstOrNull { it.value == v } ?: TWO
    }
}

enum class Suit(val index: Int, val symbol: String, val colorHex: String, val nameStr: String) {
    SPADES(0, "♠", "#B0BEC5", "Spades"),       // Blue Grey for dark UI
    HEARTS(1, "♥", "#E53935", "Hearts"),       // Crimson Red
    DIAMONDS(2, "♦", "#1E88E5", "Diamonds"),   // Royal Blue (4-color deck)
    CLUBS(3, "♣", "#43A047", "Clubs");         // Forest Green (4-color deck)
}

data class Card(val rank: Rank, val suit: Suit) {
    override fun toString(): String = "${rank.symbol}${suit.symbol}"
    fun toHtmlString(): String = "<font color='${suit.colorHex}'>${rank.symbol}${suit.symbol}</font>"
}

enum class HandCategory(val value: Int, val displayName: String, val displayNameRu: String) {
    HIGH_CARD(1, "High Card", "Нет пары"),
    ONE_PAIR(2, "One Pair", "Пара"),
    TWO_PAIR(3, "Two Pair", "Две пары"),
    THREE_OF_A_KIND(4, "Three of a Kind", "Сет/Трипс"),
    STRAIGHT(5, "Straight", "Стрит"),
    FLUSH(6, "Flush", "Флеш"),
    FULL_HOUSE(7, "Full House", "Фулл-Хаус"),
    FOUR_OF_A_KIND(8, "Four of a Kind", "Каре"),
    STRAIGHT_FLUSH(9, "Straight Flush", "Стрит-Флеш"),
    ROYAL_FLUSH(10, "Royal Flush", "Рояль-Флеш")
}

data class HandScore(
    val category: HandCategory,
    val ranks: List<Int>
) : Comparable<HandScore> {
    
    override fun compareTo(other: HandScore): Int {
        if (this.category != other.category) {
            return this.category.value.compareTo(other.category.value)
        }
        for (i in 0 until minOf(this.ranks.size, other.ranks.size)) {
            val cmp = this.ranks[i].compareTo(other.ranks[i])
            if (cmp != 0) return cmp
        }
        return 0
    }
}

data class ScannedBox(
    val rect: android.graphics.Rect,
    val label: String
)

data class OpponentState(
    val id: Int,
    val card1: Card? = null,
    val card2: Card? = null,
    val isRandom: Boolean = true,
    val isActive: Boolean = false,
    val nickname: String = "Player $id",
    val betSize: Int = 0,
    val stackSize: Int = 1000,
    val stats: PlayerStats? = null,
    val currentAction: String = "NONE",
    @Transient
    val boundingBox: android.graphics.Rect? = null
)

data class SimulationResult(
    val heroWinPct: Float,
    val heroTiePct: Float,
    val heroLossPct: Float,
    val heroHandFrequencies: Map<HandCategory, Float>,
    val simulationsPerformed: Int,
    val isExact: Boolean
)

object HandEvaluator {

    fun evaluate5CardHand(cards: List<Card>): HandScore {
        require(cards.size == 5)

        val rankCounts = IntArray(15)
        val suitCounts = IntArray(4)
        for (i in 0 until 5) {
            val card = cards[i]
            rankCounts[card.rank.value]++
            suitCounts[card.suit.index]++
        }

        var isFlush = false
        for (s in 0 until 4) {
            if (suitCounts[s] == 5) {
                isFlush = true
                break
            }
        }

        val uniqueRanksSorted = ArrayList<Int>(5)
        for (r in 14 downTo 2) {
            if (rankCounts[r] > 0) {
                uniqueRanksSorted.add(r)
            }
        }

        var isStraight = false
        var straightHighCard = 0
        if (uniqueRanksSorted.size == 5) {
            if (uniqueRanksSorted[0] - uniqueRanksSorted[4] == 4) {
                isStraight = true
                straightHighCard = uniqueRanksSorted[0]
            } else if (uniqueRanksSorted[0] == 14 && uniqueRanksSorted[1] == 5 && uniqueRanksSorted[2] == 4 && uniqueRanksSorted[3] == 3 && uniqueRanksSorted[4] == 2) {
                isStraight = true
                straightHighCard = 5
            }
        }

        // Royal & Straight Flush
        if (isFlush && isStraight) {
            if (straightHighCard == 14) {
                return HandScore(HandCategory.ROYAL_FLUSH, listOf(14))
            }
            return HandScore(HandCategory.STRAIGHT_FLUSH, listOf(straightHighCard))
        }

        // Four of a Kind
        var quadRank = 0
        var quadKicker = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] == 4) {
                quadRank = r
            } else if (rankCounts[r] > 0) {
                quadKicker = r
            }
        }
        if (quadRank > 0) {
            return HandScore(HandCategory.FOUR_OF_A_KIND, listOf(quadRank, quadKicker))
        }

        // Full house
        var tripRank = 0
        var pairRankCandidate = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] == 3) {
                tripRank = r
                break
            }
        }
        if (tripRank > 0) {
            for (r in 14 downTo 2) {
                if (r != tripRank && rankCounts[r] >= 2) {
                    pairRankCandidate = r
                    break
                }
            }
        }
        if (tripRank > 0 && pairRankCandidate > 0) {
            return HandScore(HandCategory.FULL_HOUSE, listOf(tripRank, pairRankCandidate))
        }

        // Flush
        if (isFlush) {
            return HandScore(HandCategory.FLUSH, uniqueRanksSorted)
        }

        // Straight
        if (isStraight) {
            return HandScore(HandCategory.STRAIGHT, listOf(straightHighCard))
        }

        // Three of a Kind
        if (tripRank > 0) {
            val kickers = ArrayList<Int>(2)
            for (r in 14 downTo 2) {
                if (r != tripRank && rankCounts[r] > 0) {
                    kickers.add(r)
                }
            }
            return HandScore(HandCategory.THREE_OF_A_KIND, listOf(tripRank) + kickers)
        }

        // Two pair
        var firstPair = 0
        var secondPair = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] == 2) {
                if (firstPair == 0) {
                    firstPair = r
                } else if (secondPair == 0) {
                    secondPair = r
                }
            }
        }
        if (firstPair > 0 && secondPair > 0) {
            var kicker = 0
            for (r in 14 downTo 2) {
                if (r != firstPair && r != secondPair && rankCounts[r] > 0) {
                    kicker = r
                    break
                }
            }
            return HandScore(HandCategory.TWO_PAIR, listOf(firstPair, secondPair, kicker))
        }

        // One pair
        var onePairRank = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] == 2) {
                onePairRank = r
                break
            }
        }
        if (onePairRank > 0) {
            val kickers = ArrayList<Int>(3)
            for (r in 14 downTo 2) {
                if (r != onePairRank && rankCounts[r] > 0) {
                    kickers.add(r)
                }
            }
            return HandScore(HandCategory.ONE_PAIR, listOf(onePairRank) + kickers)
        }

        // High card
        return HandScore(HandCategory.HIGH_CARD, uniqueRanksSorted)
    }

    fun findBestHand(allCards: List<Card>): HandScore {
        if (allCards.size < 2) return HandScore(HandCategory.HIGH_CARD, emptyList())
        
        if (allCards.size >= 5) {
            return findBest5CardHand(allCards)
        }
        
        // For partial hands (pre-flop/flop), evaluate based on fewer cards
        val rankCounts = IntArray(15)
        for (card in allCards) rankCounts[card.rank.value]++
        
        val sortedRanks = allCards.map { it.rank.value }.sortedDescending()
        
        // Check for Quads (impossible with < 5 unless multiple decks, but for safety)
        for (r in 14 downTo 2) if (rankCounts[r] == 4) return HandScore(HandCategory.FOUR_OF_A_KIND, listOf(r))
        // Trips
        for (r in 14 downTo 2) if (rankCounts[r] == 3) return HandScore(HandCategory.THREE_OF_A_KIND, listOf(r))
        // Two pairs or One pair
        var firstPair = 0
        var secondPair = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] == 2) {
                if (firstPair == 0) firstPair = r else if (secondPair == 0) secondPair = r
            }
        }
        if (firstPair > 0 && secondPair > 0) return HandScore(HandCategory.TWO_PAIR, listOf(firstPair, secondPair))
        if (firstPair > 0) return HandScore(HandCategory.ONE_PAIR, listOf(firstPair))
        
        return HandScore(HandCategory.HIGH_CARD, sortedRanks)
    }

    fun findBest5CardHand(allCards: List<Card>): HandScore {
        if (allCards.size < 5) {
            return findBestHand(allCards)
        }

        var bestScore = HandScore(HandCategory.HIGH_CARD, listOf(0))
        val n = allCards.size

        // Generate combinations size 5 out of n cards (n choose 5)
        val indices = IntArray(5)
        fun combine(count: Int, start: Int) {
            if (count == 5) {
                val combo = listOf(
                    allCards[indices[0]],
                    allCards[indices[1]],
                    allCards[indices[2]],
                    allCards[indices[3]],
                    allCards[indices[4]]
                )
                val score = evaluate5CardHand(combo)
                if (score > bestScore) {
                    bestScore = score
                }
                return
            }
            for (i in start until n) {
                indices[count] = i
                combine(count + 1, i + 1)
            }
        }

        combine(0, 0)
        return bestScore
    }
}

object SimulationEngine {
    private val fullDeck: List<Card> = Rank.values().flatMap { r -> Suit.values().map { s -> Card(r, s) } }

    private val rankedPairs: List<Pair<Rank, Rank>> by lazy {
        val pairs = mutableListOf<Triple<Rank, Rank, Boolean>>() // R1, R2, suited
        for (r1 in Rank.values()) {
            for (r2 in Rank.values()) {
                if (r1.value > r2.value) {
                    pairs.add(Triple(r1, r2, true))
                    pairs.add(Triple(r1, r2, false))
                } else if (r1 == r2) {
                    pairs.add(Triple(r1, r2, false))
                }
            }
        }
        // Sort by Sklansky Group (lower group is better)
        pairs.sortedBy { (r1, r2, suited) ->
            AdvisorEngine.getSklanskyGroup(Card(r1, Suit.SPADES), Card(r2, if (suited) Suit.SPADES else Suit.HEARTS))
        }.map { Pair(it.first, it.second) }
    }

    suspend fun runHoldemSimulation(
        heroCard1: Card?,
        heroCard2: Card?,
        opponents: List<OpponentState>,
        board: List<Card?>,
        simulations: Int = 3000
    ): SimulationResult {
        val activeOpponents = opponents.filter { it.isActive }
        if (activeOpponents.isEmpty()) {
            return SimulationResult(100f, 0f, 0f, HandCategory.values().associateWith { 0f }, 1, true)
        }

        val knownCardsSet = mutableSetOf<Card>().apply {
            heroCard1?.let { add(it) }
            heroCard2?.let { add(it) }
            board.filterNotNull().forEach { add(it) }
            activeOpponents.forEach { opp ->
                if (!opp.isRandom) {
                    opp.card1?.let { add(it) }
                    opp.card2?.let { add(it) }
                }
            }
        }

        val remainingDeckPool = fullDeck.filter { it !in knownCardsSet }.toMutableList()
        val totalToSample = board.count { it == null } + (if (heroCard1 == null) 1 else 0) + (if (heroCard2 == null) 1 else 0) +
                activeOpponents.sumOf { opp ->
                    if (opp.isRandom) 2 else (if (opp.card1 == null) 1 else 0) + (if (opp.card2 == null) 1 else 0)
                }

        if (remainingDeckPool.size < totalToSample) return SimulationResult(0f, 0f, 0f, emptyMap(), 0, false)

        var heroWins = 0
        var heroTies = 0
        var heroLosses = 0
        val handCategoryCounts = mutableMapOf<HandCategory, Int>().apply { HandCategory.values().forEach { put(it, 0) } }

        val rng = Random(System.nanoTime())
        val poolArray = remainingDeckPool.toTypedArray()
        val poolSize = poolArray.size

        for (sim in 0 until simulations) {
            if (sim % 500 == 0 && !kotlinx.coroutines.currentCoroutineContext().isActive) break

            // Efficient partial shuffle
            for (i in 0 until totalToSample) {
                val j = i + rng.nextInt(poolSize - i)
                val temp = poolArray[i]
                poolArray[i] = poolArray[j]
                poolArray[j] = temp
            }

            var sampleIdx = 0
            val simBoard = Array(5) { i -> board[i] ?: poolArray[sampleIdx++] }
            val simBoardList = simBoard.toList()
            val simHero1 = heroCard1 ?: poolArray[sampleIdx++]
            val simHero2 = heroCard2 ?: poolArray[sampleIdx++]

            val hero7 = listOf(simHero1, simHero2) + simBoardList
            val heroScore = HandEvaluator.findBest5CardHand(hero7)
            handCategoryCounts[heroScore.category] = handCategoryCounts.getValue(heroScore.category) + 1

            var bestOppScore: HandScore? = null
            for (opp in activeOpponents) {
                val o1 = if (opp.isRandom) poolArray[sampleIdx++] else opp.card1 ?: poolArray[sampleIdx++]
                val o2 = if (opp.isRandom) poolArray[sampleIdx++] else opp.card2 ?: poolArray[sampleIdx++]
                val opp7 = listOf(o1, o2) + simBoardList
                val oppScore = HandEvaluator.findBest5CardHand(opp7)
                if (bestOppScore == null || oppScore > bestOppScore) bestOppScore = oppScore
            }

            if (bestOppScore != null) {
                if (heroScore > bestOppScore) heroWins++
                else if (heroScore == bestOppScore) heroTies++
                else heroLosses++
            }
        }

        val total = simulations.toFloat()
        return SimulationResult(
            heroWins / total * 100f, heroTiePct = heroTies / total * 100f, heroLossPct = heroLosses / total * 100f,
            handCategoryCounts.mapValues { it.value / total * 100f }, simulations, true
        )
    }

    suspend fun runHoldemSimulationAdvanced(
        heroCard1: Card?,
        heroCard2: Card?,
        opponents: List<OpponentState>,
        board: List<Card?>,
        simulations: Int = 3000
    ): SimulationResult {
        return runHoldemSimulationInternal(heroCard1, heroCard2, opponents, board, simulations, true)
    }

    private suspend fun runHoldemSimulationInternal(
        heroCard1: Card?,
        heroCard2: Card?,
        opponents: List<OpponentState>,
        board: List<Card?>,
        simulations: Int,
        useRanges: Boolean
    ): SimulationResult {
        val activeOpponents = opponents.filter { it.isActive }
        
        // 1. Gather all known cards from input
        val knownCardsSet = mutableSetOf<Card>()
        heroCard1?.let { knownCardsSet.add(it) }
        heroCard2?.let { knownCardsSet.add(it) }
        board.filterNotNull().forEach { knownCardsSet.add(it) }
        activeOpponents.forEach { opp ->
            if (!opp.isRandom) {
                opp.card1?.let { knownCardsSet.add(it) }
                opp.card2?.let { knownCardsSet.add(it) }
            }
        }

        // 2. Remaining available cards in the deck
        val remainingDeckPool = fullDeck.filter { it !in knownCardsSet }.toMutableList()

        // 3. Count how many empty community and hole cards are remaining
        var nullBoardCount = board.count { it == null }
        var needsHero1 = (heroCard1 == null)
        var needsHero2 = (heroCard2 == null)

        // Total number of random cards we need to pull per simulation run (for board)
        val boardCardsToFill = board.indices.filter { board[it] == null }
        
        // For range-based simulation, we handle hole cards separately
        val totalToSampleForBoard = nullBoardCount
        val totalToSampleForHoles = (if (needsHero1) 1 else 0) + (if (needsHero2) 1 else 0) +
                activeOpponents.sumOf { opp ->
                    if (opp.isRandom) 2 else {
                        (if (opp.card1 == null) 1 else 0) + (if (opp.card2 == null) 1 else 0)
                    }
                }
        val totalToSample = totalToSampleForBoard + totalToSampleForHoles

        // Edge case: if no opponents are active, Hero has 100% equity alone
        if (activeOpponents.isEmpty()) {
            val emptyMap = HandCategory.values().associateWith { 0f }.toMutableMap()
            // Just evaluate Hero's best hand if board was completed
            val tempBoard = board.toMutableList()
            var currentPos = 0
            while (tempBoard.contains(null) && currentPos < remainingDeckPool.size) {
                val idx = tempBoard.indexOf(null)
                tempBoard[idx] = remainingDeckPool[currentPos++]
            }
            val bestHero = if (heroCard1 != null && heroCard2 != null && tempBoard.count { it != null } == 5) {
                val comb = listOf(heroCard1, heroCard2) + tempBoard.filterNotNull()
                HandEvaluator.findBest5CardHand(comb)
            } else {
                HandScore(HandCategory.HIGH_CARD, listOf(0))
            }
            emptyMap[bestHero.category] = 100f
            return SimulationResult(100f, 0f, 0f, emptyMap, 1, true)
        }

        if (remainingDeckPool.size < totalToSample) {
            return SimulationResult(0f, 0f, 0f, emptyMap(), 0, false)
        }

        var heroWins = 0
        var heroTies = 0
        var heroLosses = 0

        val handCategoryCounts = mutableMapOf<HandCategory, Int>()
        HandCategory.values().forEach { handCategoryCounts[it] = 0 }

        val rng = Random(System.nanoTime())
        val tempPoolArray = remainingDeckPool.toTypedArray()
        val poolSize = tempPoolArray.size

        // Precompute ranges for opponents if useRanges is true
        val cardsByRank = fullDeck.filter { it !in knownCardsSet }.groupBy { it.rank }

        val opponentRanges = if (useRanges) {
            activeOpponents.map { opp ->
                val vpip = opp.stats?.histVpip ?: opp.stats?.vpip ?: 100f
                val count = (rankedPairs.size * (vpip / 100f)).toInt().coerceIn(1, rankedPairs.size)
                rankedPairs.take(count)
            }
        } else null

        // Run Monte Carlo loops
        var successfulSims = 0
        for (sim in 0 until simulations) {
            if (sim % 200 == 0) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    if (successfulSims == 0) return SimulationResult(0f, 0f, 0f, emptyMap(), sim, false)
                    break
                }
            }
            
            // For range-based, we might need multiple attempts to find non-conflicting cards
            var attempts = 0
            var success = false
            
            while (attempts < 8 && !success) {
                attempts++
                // Shuffle pool
                for (i in 0 until totalToSample) {
                    val swapIdx = rng.nextInt(poolSize - i) + i
                    val temp = tempPoolArray[i]
                    tempPoolArray[i] = tempPoolArray[swapIdx]
                    tempPoolArray[swapIdx] = temp
                }

                var sampleIdx = 0
                val usedInSim = mutableSetOf<Card>()

                // 1. Fill board
                val simBoard = Array(5) { Card(Rank.TWO, Suit.SPADES) }
                for (b in 0 until 5) {
                    val existing = board[b]
                    if (existing != null) {
                        simBoard[b] = existing
                    } else {
                        val c = tempPoolArray[sampleIdx++]
                        simBoard[b] = c
                        usedInSim.add(c)
                    }
                }
                val simBoardList = simBoard.toList()

                // 2. Fill Hero hand
                val simHero1 = if (needsHero1) {
                    val c = tempPoolArray[sampleIdx++]
                    usedInSim.add(c)
                    c
                } else heroCard1!!
                
                val simHero2 = if (needsHero2) {
                    val c = tempPoolArray[sampleIdx++]
                    usedInSim.add(c)
                    c
                } else heroCard2!!

                // 3. Fill Opponents
                val simOpponentHands = mutableListOf<Pair<Card, Card>>()
                var conflict = false
                
                // Track sample index for random cards
                var currentSampleIdx = sampleIdx
                
                for (idx in activeOpponents.indices) {
                    val opp = activeOpponents[idx]
                    val range = opponentRanges?.get(idx)
                    
                    var h1: Card? = null
                    var h2: Card? = null
                    
                    if (range != null && opp.isRandom) {
                        // Pick from range
                        val pair = range[rng.nextInt(range.size)]
                        
                        val cardsR1 = cardsByRank[pair.first] ?: emptyList()
                        val availableForR1 = cardsR1.filter { it !in usedInSim }
                        
                        if (availableForR1.isNotEmpty()) {
                            h1 = availableForR1[rng.nextInt(availableForR1.size)]
                            usedInSim.add(h1)
                            
                            val cardsR2 = cardsByRank[pair.second] ?: emptyList()
                            val availableForR2 = cardsR2.filter { it !in usedInSim }
                            
                            if (availableForR2.isNotEmpty()) {
                                h2 = availableForR2[rng.nextInt(availableForR2.size)]
                                usedInSim.add(h2)
                            } else {
                                conflict = true
                                break
                            }
                        } else {
                            conflict = true
                            break
                        }
                    } else {
                        // Pure random or known cards
                        if (opp.isRandom) {
                            // Find next available in tempPoolArray that was not used by range-based hero/opps
                            while (currentSampleIdx < poolSize && tempPoolArray[currentSampleIdx] in usedInSim) {
                                currentSampleIdx++
                            }
                            if (currentSampleIdx >= poolSize) { conflict = true; break }
                            h1 = tempPoolArray[currentSampleIdx++]
                            usedInSim.add(h1)

                            while (currentSampleIdx < poolSize && tempPoolArray[currentSampleIdx] in usedInSim) {
                                currentSampleIdx++
                            }
                            if (currentSampleIdx >= poolSize) { conflict = true; break }
                            h2 = tempPoolArray[currentSampleIdx++]
                            usedInSim.add(h2)
                        } else {
                            h1 = opp.card1 ?: run {
                                while (currentSampleIdx < poolSize && tempPoolArray[currentSampleIdx] in usedInSim) {
                                    currentSampleIdx++
                                }
                                if (currentSampleIdx >= poolSize) { conflict = true; break }
                                val c = tempPoolArray[currentSampleIdx++]
                                usedInSim.add(c)
                                c
                            }
                            if (conflict) break
                            h2 = opp.card2 ?: run {
                                while (currentSampleIdx < poolSize && tempPoolArray[currentSampleIdx] in usedInSim) {
                                    currentSampleIdx++
                                }
                                if (currentSampleIdx >= poolSize) { conflict = true; break }
                                val c = tempPoolArray[currentSampleIdx++]
                                usedInSim.add(c)
                                c
                            }
                            if (conflict) break
                        }
                    }
                    simOpponentHands.add(h1!! to h2!!)
                }

                if (conflict) continue
                success = true
                successfulSims++

                // Evaluate Hero Hand
                val hero7Cards = listOf(simHero1, simHero2) + simBoardList
                val heroScore = HandEvaluator.findBest5CardHand(hero7Cards)
                handCategoryCounts[heroScore.category] = handCategoryCounts.getOrDefault(heroScore.category, 0) + 1

                // Evaluate all active opponents
                var bestOpponentScore: HandScore? = null

                for (hand in simOpponentHands) {
                    val opp7Cards = listOf(hand.first, hand.second) + simBoardList
                    val oppScore = HandEvaluator.findBest5CardHand(opp7Cards)

                    if (bestOpponentScore == null || oppScore > bestOpponentScore) {
                        bestOpponentScore = oppScore
                    }
                }

                // Compare Hero to best opponent
                if (bestOpponentScore != null) {
                    if (heroScore > bestOpponentScore) {
                        heroWins++
                    } else if (heroScore == bestOpponentScore) {
                        heroTies++
                    } else {
                        heroLosses++
                    }
                } else {
                    heroWins++
                }
            }
        }

        // Compute final statistics in percentage
        if (successfulSims == 0) return SimulationResult(0f, 0f, 0f, emptyMap(), 0, false)
        
        val totalRun = successfulSims.toFloat()
        val heroWinPct = (heroWins / totalRun) * 100f
        val heroTiePct = (heroTies / totalRun) * 100f
        val heroLossPct = (heroLosses / totalRun) * 100f

        val handFrequencies = handCategoryCounts.mapValues { (_, count) ->
            (count / totalRun) * 100f
        }

        return SimulationResult(
            heroWinPct = heroWinPct,
            heroTiePct = heroTiePct,
            heroLossPct = heroLossPct,
            heroHandFrequencies = handFrequencies,
            simulationsPerformed = simulations,
            isExact = false
        )
    }
}
