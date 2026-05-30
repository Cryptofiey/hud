package com.example

import java.util.Locale
import kotlin.random.Random
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
    SPADES(0, "♠", "#1A1A1A", "Spades"),       // Midnight Black
    HEARTS(1, "♥", "#E53935", "Hearts"),       // Crimson Red
    DIAMONDS(2, "♦", "#1E88E5", "Diamonds"),   // Royal Blue (4-color deck)
    CLUBS(3, "♣", "#43A047", "Clubs");         // Forest Green (4-color deck)
}

data class Card(val rank: Rank, val suit: Suit) {
    override fun toString(): String = "${rank.symbol}${suit.symbol}"
}

enum class HandCategory(val value: Int, val displayName: String) {
    HIGH_CARD(1, "High Card"),
    ONE_PAIR(2, "One Pair"),
    TWO_PAIR(3, "Two Pair"),
    THREE_OF_A_KIND(4, "Three of a Kind"),
    STRAIGHT(5, "Straight"),
    FLUSH(6, "Flush"),
    FULL_HOUSE(7, "Full House"),
    FOUR_OF_A_KIND(8, "Four of a Kind"),
    STRAIGHT_FLUSH(9, "Straight Flush"),
    ROYAL_FLUSH(10, "Royal Flush")
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

data class OpponentState(
    val id: Int,
    val card1: Card? = null,
    val card2: Card? = null,
    val isRandom: Boolean = true,
    val isActive: Boolean = false,
    val nickname: String = "Player $id",
    val betSize: Int = 0,
    val stackSize: Int = 1000,
    val stats: PlayerStats? = null
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

    fun findBest5CardHand(allCards: List<Card>): HandScore {
        if (allCards.size < 5) {
            return HandScore(HandCategory.HIGH_CARD, emptyList())
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

    suspend fun runHoldemSimulation(
        heroCard1: Card?,
        heroCard2: Card?,
        opponents: List<OpponentState>,
        board: List<Card?>,
        simulations: Int = 3000
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

        var randomOpponentsCount = activeOpponents.count { it.isRandom }
        var knownOpponentsWithNullCount = activeOpponents.count { !it.isRandom && (it.card1 == null || it.card2 == null) }

        // Total number of random cards we need to pull per simulation run
        val boardCardsToFill = board.indices.filter { board[it] == null }
        val randomHoleCardsNeeded = (if (needsHero1) 1 else 0) + (if (needsHero2) 1 else 0) +
                activeOpponents.sumOf { opp ->
                    if (opp.isRandom) 2 else {
                        (if (opp.card1 == null) 1 else 0) + (if (opp.card2 == null) 1 else 0)
                    }
                }
        val totalToSample = nullBoardCount + randomHoleCardsNeeded

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

        // Hybrid approach: If total remaining combos are small (e.g. Turn/River with exact cards), we can calculate exact!
        // For simplicity and solid real-time performance across all states, we'll run Monte Carlo.
        // If remaining deck is smaller than what we need to pull, we can't run.
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

        // Run Monte Carlo loops
        for (sim in 0 until simulations) {
            if (sim % 200 == 0) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    return SimulationResult(0f, 0f, 0f, emptyMap(), sim, false)
                }
            }
            // High-performance partial shuffle: we only shuffle as many items as we need to sample!
            for (i in 0 until totalToSample) {
                val swapIdx = rng.nextInt(poolSize - i) + i
                val temp = tempPoolArray[i]
                tempPoolArray[i] = tempPoolArray[swapIdx]
                tempPoolArray[swapIdx] = temp
            }

            // Cards from index 0 to totalToSample - 1 are now perfectly random samples!
            var sampleIdx = 0

            // Fill Hero hand
            val simHero1 = if (needsHero1) tempPoolArray[sampleIdx++] else heroCard1!!
            val simHero2 = if (needsHero2) tempPoolArray[sampleIdx++] else heroCard2!!

            // Fill board
            val simBoard = Array(5) { Card(Rank.TWO, Suit.SPADES) }
            for (b in 0 until 5) {
                val existing = board[b]
                if (existing != null) {
                    simBoard[b] = existing
                } else {
                    simBoard[b] = tempPoolArray[sampleIdx++]
                }
            }
            val simBoardList = simBoard.toList()

            // Evaluate Hero Hand
            val hero7Cards = listOf(simHero1, simHero2) + simBoardList
            val heroScore = HandEvaluator.findBest5CardHand(hero7Cards)
            handCategoryCounts[heroScore.category] = handCategoryCounts.getOrDefault(heroScore.category, 0) + 1

            // Evaluate all active opponents
            var bestOpponentScore: HandScore? = null

            for (opp in activeOpponents) {
                val oppCard1 = if (opp.isRandom) {
                    tempPoolArray[sampleIdx++]
                } else {
                    opp.card1 ?: tempPoolArray[sampleIdx++]
                }

                val oppCard2 = if (opp.isRandom) {
                    tempPoolArray[sampleIdx++]
                } else {
                    opp.card2 ?: tempPoolArray[sampleIdx++]
                }

                val opp7Cards = listOf(oppCard1, oppCard2) + simBoardList
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

        // Compute final statistics in percentage
        val totalRun = simulations.toFloat()
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
