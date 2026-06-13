package com.example

import java.util.Random
import java.util.Locale
import kotlin.random.Random as KotlinRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

enum class Rank(val value: Int, val symbol: String) {
    TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"), FIVE(5, "5"),
    SIX(6, "6"), SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"),
    TEN(10, "10"), JACK(11, "J"), QUEEN(12, "Q"), KING(13, "K"), ACE(14, "A");

    companion object {
        fun fromValue(v: Int): Rank = values().firstOrNull { it.value == v } ?: TWO
    }
}

enum class Suit(val index: Int, val symbol: String, val colorHex: String, val nameStr: String) {
    SPADES(0, "♠", "#ECEFF1", "Spades"),       // White/Light Grey for Spades (Black replacement)
    HEARTS(1, "♥", "#EF5350", "Hearts"),       // Bright Red
    DIAMONDS(2, "♦", "#4FC3F7", "Diamonds"),   // Bright Blue
    CLUBS(3, "♣", "#66BB6A", "Clubs");         // Bright Green
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
    val betSize: Float = 0f,
    val stackSize: Float = 1000f,
    val sessionVpip: Float? = null,
    val sessionVpipBox: android.graphics.Rect? = null,
    val stats: PlayerStats? = null,
    val currentAction: String = "NONE",
    val isDealer: Boolean = false,
    val positionName: String = "NONE",
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
        val rankCounts = IntArray(15) { 0 }
        var isFlush = true
        val firstSuit = cards[0].suit.index
        for (i in 0 until 5) {
            val card = cards[i]
            rankCounts[card.rank.value]++
            if (card.suit.index != firstSuit) isFlush = false
        }

        val uniqueRanks = IntArray(5)
        var uniqueCount = 0
        for (r in 14 downTo 2) {
            if (rankCounts[r] > 0) uniqueRanks[uniqueCount++] = r
        }

        var isStraight = false
        var straightHighCard = 0
        if (uniqueCount == 5) {
            if (uniqueRanks[0] - uniqueRanks[4] == 4) {
                isStraight = true
                straightHighCard = uniqueRanks[0]
            } else if (uniqueRanks[0] == 14 && uniqueRanks[1] == 5 && uniqueRanks[2] == 4 && uniqueRanks[3] == 3 && uniqueRanks[4] == 2) {
                isStraight = true
                straightHighCard = 5
            }
        }

        if (isFlush && isStraight) {
            return HandScore(if (straightHighCard == 14) HandCategory.ROYAL_FLUSH else HandCategory.STRAIGHT_FLUSH, listOf(straightHighCard))
        }

        var quadRank = 0
        var tripRank = 0
        var pair1 = 0
        var pair2 = 0
        var pairCount = 0

        for (r in 14 downTo 2) {
            val count = rankCounts[r]
            when (count) {
                4 -> quadRank = r
                3 -> tripRank = r
                2 -> {
                    if (pairCount == 0) pair1 = r else if (pairCount == 1) pair2 = r
                    pairCount++
                }
            }
        }

        if (quadRank > 0) {
            var kicker = 0
            for (r in 14 downTo 2) if (rankCounts[r] > 0 && r != quadRank) { kicker = r; break }
            return HandScore(HandCategory.FOUR_OF_A_KIND, listOf(quadRank, kicker))
        }
        if (tripRank > 0 && pair1 > 0) return HandScore(HandCategory.FULL_HOUSE, listOf(tripRank, pair1))
        if (isFlush) {
            return HandScore(HandCategory.FLUSH, uniqueRanks.take(uniqueCount))
        }
        if (isStraight) return HandScore(HandCategory.STRAIGHT, listOf(straightHighCard))
        if (tripRank > 0) {
            val kickers = mutableListOf<Int>()
            for (i in 0 until uniqueCount) if (uniqueRanks[i] != tripRank) kickers.add(uniqueRanks[i])
            return HandScore(HandCategory.THREE_OF_A_KIND, listOf(tripRank) + kickers)
        }
        if (pairCount >= 2) {
            var kicker = 0
            for (i in 0 until uniqueCount) if (uniqueRanks[i] != pair1 && uniqueRanks[i] != pair2) { kicker = uniqueRanks[i]; break }
            return HandScore(HandCategory.TWO_PAIR, listOf(pair1, pair2, kicker))
        }
        if (pairCount == 1) {
            val kickers = mutableListOf<Int>()
            for (i in 0 until uniqueCount) if (uniqueRanks[i] != pair1) kickers.add(uniqueRanks[i])
            return HandScore(HandCategory.ONE_PAIR, listOf(pair1) + kickers)
        }
        
        return HandScore(HandCategory.HIGH_CARD, uniqueRanks.take(uniqueCount))
    }

    fun findBestHand(allCards: List<Card>): HandScore {
        if (allCards.size < 5) {
            val sorted = allCards.map { it.rank.value }.sortedDescending()
            return HandScore(HandCategory.HIGH_CARD, sorted)
        }
        return findBest5CardHand(allCards)
    }

    fun findBest5CardHand(allCards: List<Card>): HandScore {
        val n = allCards.size
        if (n == 5) return evaluate5CardHand(allCards)
        
        var bestScore: HandScore? = null
        val combo = ArrayList<Card>(5)
        for (i in 0..4) combo.add(allCards[0]) // Initial dummy

        // Optimized combination generation
        for (i in 0 until n - 4) {
            val c1 = allCards[i]
            for (j in i + 1 until n - 3) {
                val c2 = allCards[j]
                for (k in j + 1 until n - 2) {
                    val c3 = allCards[k]
                    for (l in k + 1 until n - 1) {
                        val c4 = allCards[l]
                        for (m in l + 1 until n) {
                            val c5 = allCards[m]
                            val currentCombo = listOf(c1, c2, c3, c4, c5)
                            val score = evaluate5CardHand(currentCombo)
                            if (bestScore == null || score > bestScore!!) bestScore = score
                        }
                    }
                }
            }
        }
        return bestScore ?: HandScore(HandCategory.HIGH_CARD, listOf(0))
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

    private val cardToIndex: Map<Card, Int> by lazy { fullDeck.withIndex().associate { it.value to it.index } }

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
        val knownCardsIndices = mutableListOf<Int>()
        heroCard1?.let { cardToIndex[it]?.let { idx -> knownCardsIndices.add(idx) } }
        heroCard2?.let { cardToIndex[it]?.let { idx -> knownCardsIndices.add(idx) } }
        board.filterNotNull().forEach { cardToIndex[it]?.let { idx -> knownCardsIndices.add(idx) } }
        activeOpponents.forEach { opp ->
            if (!opp.isRandom) {
                opp.card1?.let { cardToIndex[it]?.let { idx -> knownCardsIndices.add(idx) } }
                opp.card2?.let { cardToIndex[it]?.let { idx -> knownCardsIndices.add(idx) } }
            }
        }

        val knownCardsMap = BooleanArray(52)
        knownCardsIndices.forEach { knownCardsMap[it] = true }

        // 2. Remaining available cards in the deck
        val remainingDeckPool = fullDeck.filter { it !in knownCardsMap.indices.filter { knownCardsMap[it] }.map { fullDeck[it] } }.toMutableList()
        // Wait, simpler way to get remaining cards
        val pool = mutableListOf<Card>()
        for (c in fullDeck) { if (!knownCardsMap[cardToIndex[c]!!]) pool.add(c) }

        // 3. Count how many empty community and hole cards are remaining
        val nullBoardIndices = board.indices.filter { board[it] == null }
        val needsHero1 = (heroCard1 == null)
        val needsHero2 = (heroCard2 == null)
        
        val totalToSampleForBoard = nullBoardIndices.size
        val totalToSampleForHoles = (if (needsHero1) 1 else 0) + (if (needsHero2) 1 else 0) +
                activeOpponents.sumOf { opp ->
                    if (opp.isRandom) 2 else {
                        (if (opp.card1 == null) 1 else 0) + (if (opp.card2 == null) 1 else 0)
                    }
                }
        val totalToSample = totalToSampleForBoard + totalToSampleForHoles

        if (activeOpponents.isEmpty()) {
            val emptyMap = HandCategory.values().associateWith { 0f }.toMutableMap()
            val tempBoard = board.toMutableList()
            var currentPos = 0
            while (tempBoard.contains(null) && currentPos < pool.size) {
                val idx = tempBoard.indexOf(null)
                tempBoard[idx] = pool[currentPos++]
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

        if (pool.size < totalToSample) return SimulationResult(0f, 0f, 0f, emptyMap(), 0, false)

        var heroWins = 0
        var heroTies = 0
        var heroLosses = 0
        val handCategoryCounts = IntArray(HandCategory.values().size + 1)

        val rng = Random(System.nanoTime())
        val tempPoolArray = pool.toTypedArray()
        val poolSize = tempPoolArray.size

        // Precompute ranges for opponents
        val cardsByRank = fullDeck.filter { card -> !knownCardsMap[cardToIndex[card]!!] }.groupBy { it.rank }

        val opponentRanges = if (useRanges) {
            activeOpponents.map { opp ->
                val vpip = opp.stats?.histVpip ?: opp.stats?.vpip ?: 100f
                val count = (rankedPairs.size * (vpip / 100f)).toInt().coerceIn(1, rankedPairs.size)
                rankedPairs.take(count)
            }
        } else null

        val usedInSimIndices = BooleanArray(52)
        var successfulSims = 0
        
        for (sim in 0 until simulations) {
            if (sim % 250 == 0 && !kotlinx.coroutines.currentCoroutineContext().isActive) break
            
            var attempts = 0
            var success = false
            
            while (attempts < 5 && !success) {
                attempts++
                // Fast partial shuffle
                for (i in 0 until totalToSample) {
                    val swapIdx = i + rng.nextInt(poolSize - i)
                    val t = tempPoolArray[i]
                    tempPoolArray[i] = tempPoolArray[swapIdx]
                    tempPoolArray[swapIdx] = t
                }

                usedInSimIndices.fill(false)
                var sampleIdx = 0

                val simBoard = Array(5) { i ->
                    board[i] ?: tempPoolArray[sampleIdx++].also { usedInSimIndices[cardToIndex[it]!!] = true }
                }

                val h1 = if (needsHero1) tempPoolArray[sampleIdx++].also { usedInSimIndices[cardToIndex[it]!!] = true } else heroCard1!!
                val h2 = if (needsHero2) tempPoolArray[sampleIdx++].also { usedInSimIndices[cardToIndex[it]!!] = true } else heroCard2!!

                val simOppHand1 = Array<Card?>(activeOpponents.size) { null }
                val simOppHand2 = Array<Card?>(activeOpponents.size) { null }
                var conflict = false
                var currentSampleIdx = sampleIdx

                for (idx in activeOpponents.indices) {
                    val opp = activeOpponents[idx]
                    val range = opponentRanges?.get(idx)
                    
                    if (range != null && opp.isRandom) {
                        val pair = range[rng.nextInt(range.size)]
                        
                        val cardsR1 = cardsByRank[pair.first] ?: emptyList()
                        var picked1: Card? = null
                        for (c in cardsR1) {
                            if (!usedInSimIndices[cardToIndex[c]!!]) {
                                picked1 = c
                                break
                            }
                        }
                        
                        if (picked1 != null) {
                            usedInSimIndices[cardToIndex[picked1]!!] = true
                            val cardsR2 = cardsByRank[pair.second] ?: emptyList()
                            var picked2: Card? = null
                            for (c in cardsR2) {
                                if (!usedInSimIndices[cardToIndex[c]!!]) {
                                    picked2 = c
                                    break
                                }
                            }
                            
                            if (picked2 != null) {
                                usedInSimIndices[cardToIndex[picked2]!!] = true
                                simOppHand1[idx] = picked1
                                simOppHand2[idx] = picked2
                            } else {
                                conflict = true; break
                            }
                        } else {
                            conflict = true; break
                        }
                    } else {
                        if (opp.isRandom) {
                            while (currentSampleIdx < poolSize && usedInSimIndices[cardToIndex[tempPoolArray[currentSampleIdx]]!!]) { currentSampleIdx++ }
                            if (currentSampleIdx >= poolSize) { conflict = true; break }
                            val c1 = tempPoolArray[currentSampleIdx++]
                            usedInSimIndices[cardToIndex[c1]!!] = true
                            
                            while (currentSampleIdx < poolSize && usedInSimIndices[cardToIndex[tempPoolArray[currentSampleIdx]]!!]) { currentSampleIdx++ }
                            if (currentSampleIdx >= poolSize) { conflict = true; break }
                            val c2 = tempPoolArray[currentSampleIdx++]
                            usedInSimIndices[cardToIndex[c2]!!] = true
                            
                            simOppHand1[idx] = c1
                            simOppHand2[idx] = c2
                        } else {
                            val c1 = opp.card1 ?: run {
                                while (currentSampleIdx < poolSize && usedInSimIndices[cardToIndex[tempPoolArray[currentSampleIdx]]!!]) { currentSampleIdx++ }
                                if (currentSampleIdx >= poolSize) { conflict = true; null } else {
                                    val c = tempPoolArray[currentSampleIdx++]
                                    usedInSimIndices[cardToIndex[c]!!] = true
                                    c
                                }
                            }
                            if (c1 == null) { conflict = true; break }
                            val c2 = opp.card2 ?: run {
                                while (currentSampleIdx < poolSize && usedInSimIndices[cardToIndex[tempPoolArray[currentSampleIdx]]!!]) { currentSampleIdx++ }
                                if (currentSampleIdx >= poolSize) { conflict = true; null } else {
                                    val c = tempPoolArray[currentSampleIdx++]
                                    usedInSimIndices[cardToIndex[c]!!] = true
                                    c
                                }
                            }
                            if (c2 == null) { conflict = true; break }
                            simOppHand1[idx] = c1
                            simOppHand2[idx] = c2
                        }
                    }
                }

                if (conflict) continue
                success = true
                successfulSims++

                val simBoardList = simBoard.toList()
                val heroScore = HandEvaluator.findBest5CardHand(listOf(h1, h2) + simBoardList)
                handCategoryCounts[heroScore.category.ordinal]++

                var bestOppScore: HandScore? = null
                for (i in activeOpponents.indices) {
                    val oScore = HandEvaluator.findBest5CardHand(listOf(simOppHand1[i]!!, simOppHand2[i]!!) + simBoardList)
                    if (bestOppScore == null || oScore > bestOppScore) {
                        bestOppScore = oScore
                    }
                }

                if (bestOppScore != null) {
                    if (heroScore > bestOppScore) heroWins++
                    else if (heroScore == bestOppScore) heroTies++
                    else heroLosses++
                } else {
                    heroWins++
                }
            }
        }

        if (successfulSims == 0) return SimulationResult(0f, 0f, 0f, emptyMap(), 0, false)
        val total = successfulSims.toFloat()
        val handFreqs = HandCategory.values().associateWith { (handCategoryCounts[it.ordinal].toFloat() / total) * 100f }

        return SimulationResult(
            heroWinPct = heroWins / total * 100f,
            heroTiePct = heroTies / total * 100f,
            heroLossPct = (successfulSims - heroWins - heroTies) / total * 100f,
            heroHandFrequencies = handFreqs,
            simulationsPerformed = successfulSims,
            isExact = false
        )
    }
}
