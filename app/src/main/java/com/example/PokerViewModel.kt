package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SelectionTarget {
    object Hero1 : SelectionTarget()
    object Hero2 : SelectionTarget()
    data class Board(val index: Int) : SelectionTarget() // 0=Flop1, 1=Flop2, 2=Flop3, 3=Turn, 4=River
    data class OpponentCard(val opponentId: Int, val cardIndex: Int) : SelectionTarget() // cardIndex = 1 or 2
}

data class PokerUiState(
    val heroCard1: Card? = null,
    val heroCard2: Card? = null,
    val board: List<Card?> = listOf(null, null, null, null, null), // Flop1..3, Turn, River
    val opponents: List<OpponentState> = listOf(
        OpponentState(id = 1, isActive = false, isRandom = true, nickname = "Opponent 1", betSize = 0f, stackSize = 0f),
        OpponentState(id = 2, isActive = false, isRandom = true, nickname = "Opponent 2", betSize = 0f, stackSize = 0f),
        OpponentState(id = 3, isActive = false, isRandom = true, nickname = "Opponent 3", betSize = 0f, stackSize = 0f),
        OpponentState(id = 4, isActive = false, isRandom = true, nickname = "Opponent 4", betSize = 0f, stackSize = 0f),
        OpponentState(id = 5, isActive = false, isRandom = true, nickname = "Opponent 5", betSize = 0f, stackSize = 0f)
    ),
    val heroActionOptions: List<String> = emptyList(),
    val heroTurn: Boolean = false,
    val activeTarget: SelectionTarget? = SelectionTarget.Hero1,
    val isCalculating: Boolean = false,
    val calculationProgress: Float = 0f,
    val simulationResult: SimulationResult? = null,
    val errorMessage: String? = null,
    val simulationSize: Int = 3000, // default MC size
    val potSize: Float = 100f,
    val heroBet: Float = 0f,
    val smallBlind: Float = 10f,
    val bigBlind: Float = 20f,
    val heroStack: Float = 1000f,
    val position: TablePosition = TablePosition.BTN,
    val stage: TournamentStage = TournamentStage.EARLY,
    val settings: AdvisorSettings = AdvisorSettings(),
    val recommendation: Recommendation? = null,
    val advancedSimulationResult: SimulationResult? = null,
    val l2Recommendation: Recommendation? = null,
    val advancedRecommendation: Recommendation? = null,
    val l4Recommendation: Recommendation? = null,
    val multiL1Recs: Map<Int, Recommendation> = emptyMap(),
    val multiL2Recs: Map<Int, Recommendation> = emptyMap(),
    val multiAdvRecs: Map<Int, Recommendation> = emptyMap(),
    val multiSimResults: Map<Int, SimulationResult> = emptyMap(),
    val multiAdvSimResults: Map<Int, SimulationResult> = emptyMap(),
    val profileBoxes: List<ScannedBox>? = null,
    val rawScannerBoxes: List<ScannedBox>? = null,
    val isBbDisplay: Boolean = false
) {
    // Collect all selected cards on the table to dim them in the card picker grid
    fun getAllSelectedCards(): Set<Card> {
        val selected = mutableSetOf<Card>()
        heroCard1?.let { selected.add(it) }
        heroCard2?.let { selected.add(it) }
        board.filterNotNull().forEach { selected.add(it) }
        opponents.filter { it.isActive && !it.isRandom }.forEach { opp ->
            opp.card1?.let { selected.add(it) }
            opp.card2?.let { selected.add(it) }
        }
        return selected
    }
}

class PokerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)

    val uiState: StateFlow<PokerUiState> = PokerHudSharedState.uiState.asStateFlow()

    private val _uiState = PokerHudSharedState.uiState

    private var calculationJob: Job? = null
    private var autocalcJob: Job? = null

    init {
        // Load initial general configurations and player models in card holder
        _uiState.update { state ->
            val settings = prefsManager.loadAdvisorSettings()
            state.copy(
                settings = settings,
                opponents = state.opponents.map { opp ->
                    val loadedStats = prefsManager.loadPlayerStats(opp.nickname)
                    opp.copy(stats = loadedStats)
                }
            )
        }
        triggerAutoCalculation()
    }

    fun selectTarget(target: SelectionTarget?) {
        _uiState.update { it.copy(activeTarget = target, errorMessage = null) }
    }

    fun setCardInActiveTarget(card: Card) {
        val currentState = _uiState.value
        val currentTarget = currentState.activeTarget ?: return

        // Check if card is already used elsewhere
        if (currentState.getAllSelectedCards().contains(card)) {
            _uiState.update { it.copy(errorMessage = "Card already in use!") }
            return
        }

        _uiState.update { state ->
            var newState = when (currentTarget) {
                is SelectionTarget.Hero1 -> state.copy(heroCard1 = card)
                is SelectionTarget.Hero2 -> state.copy(heroCard2 = card)
                is SelectionTarget.Board -> {
                    val newBoard = state.board.toMutableList()
                    newBoard[currentTarget.index] = card
                    state.copy(board = newBoard)
                }
                is SelectionTarget.OpponentCard -> {
                    val newOpponents = state.opponents.map { opp ->
                        if (opp.id == currentTarget.opponentId) {
                            if (currentTarget.cardIndex == 1) opp.copy(card1 = card)
                            else opp.copy(card2 = card)
                        } else opp
                    }
                    state.copy(opponents = newOpponents)
                }
            }
            
            // Auto advance target to the next empty spot
            val nextTarget = findNextEmptyTarget(newState)
            newState.copy(activeTarget = nextTarget, errorMessage = null)
        }

        triggerAutoCalculation()
    }

    fun clearSlot(target: SelectionTarget) {
        _uiState.update { state ->
            when (target) {
                is SelectionTarget.Hero1 -> state.copy(heroCard1 = null)
                is SelectionTarget.Hero2 -> state.copy(heroCard2 = null)
                is SelectionTarget.Board -> {
                    val newBoard = state.board.toMutableList()
                    newBoard[target.index] = null
                    state.copy(board = newBoard)
                }
                is SelectionTarget.OpponentCard -> {
                    val newOpponents = state.opponents.map { opp ->
                        if (opp.id == target.opponentId) {
                            if (target.cardIndex == 1) opp.copy(card1 = null)
                            else opp.copy(card2 = null)
                        } else opp
                    }
                    state.copy(opponents = newOpponents)
                }
            }
        }
        triggerAutoCalculation()
    }

    fun resetAll() {
        calculationJob?.cancel()
        autocalcJob?.cancel()
        val defaultState = PokerUiState()
        _uiState.update {
            defaultState.copy(
                settings = prefsManager.loadAdvisorSettings()
            ).copy(
                opponents = defaultState.opponents.map { opp ->
                    val loadedStats = prefsManager.loadPlayerStats(opp.nickname)
                    opp.copy(stats = loadedStats)
                }
            )
        }
        triggerAutoCalculation()
    }

    fun clearBoard() {
        _uiState.update { it.copy(board = listOf(null, null, null, null, null)) }
        triggerAutoCalculation()
    }

    fun loadGamePreset(hero1: Card?, hero2: Card?, boardList: List<Card?>) {
        _uiState.update { state ->
            state.copy(
                heroCard1 = hero1,
                heroCard2 = hero2,
                board = boardList,
                errorMessage = null
            )
        }
        startCalculation()
    }

    fun setOpponentCount(count: Int) {
        // Enforce bounds 1 to 3
        val boundedCount = count.coerceIn(1, 3)
        _uiState.update { state ->
            val newOpponents = state.opponents.mapIndexed { idx, opp ->
                opp.copy(isActive = idx < boundedCount)
            }
            state.copy(opponents = newOpponents)
        }
        triggerAutoCalculation()
    }

    fun toggleOpponentType(opponentId: Int) {
        _uiState.update { state ->
            val newOpponents = state.opponents.map { opp ->
                if (opp.id == opponentId) {
                    opp.copy(isRandom = !opp.isRandom)
                } else opp
            }
            state.copy(opponents = newOpponents)
        }
        triggerAutoCalculation()
    }

    fun changeSimulationSize(size: Int) {
        _uiState.update { it.copy(simulationSize = size) }
        triggerAutoCalculation()
    }

    // --- NEW INTERACTIVE HUD ADVISOR STATE MANIPULATIONS ---

    fun updatePotSize(pot: Float) {
        _uiState.update { it.copy(potSize = pot.coerceAtLeast(1f)) }
        triggerAutoCalculation()
    }

    fun updateHeroBet(bet: Float) {
        _uiState.update { it.copy(heroBet = bet.coerceAtLeast(0f)) }
        triggerAutoCalculation()
    }

    fun updateBlinds(small: Float, big: Float) {
        _uiState.update { it.copy(smallBlind = small.coerceAtLeast(1f), bigBlind = big.coerceAtLeast(2f)) }
        triggerAutoCalculation()
    }

    fun updateHeroStack(stack: Float) {
        _uiState.update { it.copy(heroStack = stack.coerceAtLeast(1f)) }
        triggerAutoCalculation()
    }

    fun updatePosition(pos: TablePosition) {
        _uiState.update { it.copy(position = pos) }
        triggerAutoCalculation()
    }

    fun updateTournamentStage(stg: TournamentStage) {
        _uiState.update { it.copy(stage = stg) }
        triggerAutoCalculation()
    }

    fun updateOpponentNickname(opponentId: Int, name: String) {
        if (name.isBlank()) return
        _uiState.update { state ->
            val newOpponents = state.opponents.map { opp ->
                if (opp.id == opponentId) {
                    val loadedStats = prefsManager.loadPlayerStats(name)
                    opp.copy(nickname = name, stats = loadedStats)
                } else opp
            }
            state.copy(opponents = newOpponents)
        }
        triggerAutoCalculation()
    }

    fun updateOpponentBetSize(opponentId: Int, size: Float) {
        _uiState.update { state ->
            val newOpponents = state.opponents.map { opp ->
                if (opp.id == opponentId) opp.copy(betSize = size.coerceAtLeast(0f)) else opp
            }
            state.copy(opponents = newOpponents)
        }
        triggerAutoCalculation()
    }

    fun updateOpponentStackSize(opponentId: Int, size: Float) {
        _uiState.update { state ->
            val newOpponents = state.opponents.map { opp ->
                if (opp.id == opponentId) opp.copy(stackSize = size.coerceAtLeast(1f)) else opp
            }
            state.copy(opponents = newOpponents)
        }
        triggerAutoCalculation()
    }

    // Interactive custom update for loaded player stats profiles
    fun incrementPlayerStat(nickname: String, category: String) {
        val currentStats = prefsManager.loadPlayerStats(nickname)
        val updatedStats = when (category) {
            "hand" -> currentStats.copy(handsPlayed = currentStats.handsPlayed + 1)
            "vpip" -> currentStats.copy(
                handsPlayed = currentStats.handsPlayed + 1,
                vpipCount = currentStats.vpipCount + 1
            )
            "pfr" -> currentStats.copy(
                handsPlayed = currentStats.handsPlayed + 1,
                pfrCount = currentStats.pfrCount + 1
            )
            "fold_3bet" -> currentStats.copy(
                foldTo3betCount = currentStats.foldTo3betCount + 1
            )
            "showdown_win" -> currentStats.copy(
                showdownTotal = currentStats.showdownTotal + 1,
                showdownWins = currentStats.showdownWins + 1
            )
            "showdown_loss" -> currentStats.copy(
                showdownTotal = currentStats.showdownTotal + 1
            )
            "agg_bet_raise" -> currentStats.copy(
                aggressionCount = currentStats.aggressionCount + 1
            )
            "agg_call" -> currentStats.copy(
                aggressionCalls = currentStats.aggressionCalls + 1
            )
            else -> currentStats
        }
        prefsManager.savePlayerStats(updatedStats)

        // Sync loaded stats configurations with UI State opponents
        _uiState.update { state ->
            state.copy(
                opponents = state.opponents.map { opp ->
                    if (opp.nickname.equals(nickname, ignoreCase = true)) {
                        opp.copy(stats = updatedStats)
                    } else opp
                }
            )
        }
        triggerAutoCalculation()
    }

    // Save Live Advisor Settings config & update UI state
    fun updateAdvisorSettings(settings: AdvisorSettings) {
        prefsManager.saveAdvisorSettings(settings)
        _uiState.update { it.copy(settings = settings) }
        triggerAutoCalculation()
    }

    // Retrieve list of unique player profile names loaded in user session
    fun getSavedPlayerNamesList(): List<String> {
        return prefsManager.getPlayerProfilesList()
    }

    // Core simulation task handler
    fun startCalculation(highAccuracy: Boolean = false) {
        calculationJob?.cancel()
        autocalcJob?.cancel()
        
        val simSize = if (highAccuracy) 10000 else _uiState.value.simulationSize

        _uiState.update { it.copy(isCalculating = true, errorMessage = null) }

        calculationJob = viewModelScope.launch(Dispatchers.Default) {
            val state = _uiState.value
            try {
                // Precalculate maps for 1..5 opponents in parallel
                val multiSims = SimulationEngine.runMultiOpponentSimulation(
                    heroCard1 = state.heroCard1,
                    heroCard2 = state.heroCard2,
                    opponents = state.opponents,
                    board = state.board,
                    simulations = simSize
                )

                val multiAdvSims = SimulationEngine.runMultiOpponentSimulationAdvanced(
                    heroCard1 = state.heroCard1,
                    heroCard2 = state.heroCard2,
                    opponents = state.opponents,
                    board = state.board,
                    simulations = simSize
                )

                val multiL1 = mutableMapOf<Int, Recommendation>()
                val multiL2 = mutableMapOf<Int, Recommendation>()
                val multiAdv = mutableMapOf<Int, Recommendation>()

                for (n in 1..5) {
                    val subset = SimulationEngine.getOpponentSubsetForN(state.opponents, n)
                    
                    val recL1 = AdvisorEngine.computeRecommendation(
                        heroCard1 = state.heroCard1,
                        heroCard2 = state.heroCard2,
                        board = state.board,
                        potSize = state.potSize,
                        heroBet = state.heroBet,
                        opponents = subset,
                        activeOpponentsCount = n,
                        simResult = multiSims[n],
                        settings = state.settings,
                        position = state.position,
                        stage = state.stage,
                        smallBlind = state.smallBlind,
                        bigBlind = state.bigBlind,
                        heroStack = state.heroStack,
                        isBbDisplay = state.isBbDisplay
                    )
                    multiL1[n] = recL1

                    val recL2 = AdvisorEngine.computeRecommendationL2(
                        heroCard1 = state.heroCard1,
                        heroCard2 = state.heroCard2,
                        board = state.board,
                        potSize = state.potSize,
                        heroBet = state.heroBet,
                        opponents = subset,
                        activeOpponentsCount = n,
                        simResult = multiSims[n],
                        settings = state.settings,
                        position = state.position,
                        stage = state.stage,
                        smallBlind = state.smallBlind,
                        bigBlind = state.bigBlind,
                        heroStack = state.heroStack,
                        isBbDisplay = state.isBbDisplay
                    )
                    multiL2[n] = recL2

                    val recAdv = AdvisorEngine.computeRecommendationAdvanced(
                        heroCard1 = state.heroCard1,
                        heroCard2 = state.heroCard2,
                        board = state.board,
                        potSize = state.potSize,
                        heroBet = state.heroBet,
                        opponents = subset,
                        activeOpponentsCount = n,
                        simResult = multiAdvSims[n],
                        settings = state.settings,
                        position = state.position,
                        stage = state.stage,
                        smallBlind = state.smallBlind,
                        bigBlind = state.bigBlind,
                        heroStack = state.heroStack,
                        isBbDisplay = state.isBbDisplay
                    )
                    multiAdv[n] = recAdv
                }

                // Pick the target branch corresponding to the actual active count + potential pending behind us
                val actualActiveCount = state.opponents.count { it.isActive }
                val isPreflop = state.board.filterNotNull().isEmpty()
                val pendingBehind = if (isPreflop) {
                    when (state.position) {
                        TablePosition.UTG -> 5
                        TablePosition.MP -> 4
                        TablePosition.CO -> 3
                        TablePosition.BTN -> 2
                        TablePosition.SB -> 1
                        TablePosition.BB -> 0
                    }
                } else {
                    0
                }
                // Effective opponent count is the actual active players plus those who can still act after us
                val decisionOpponentCount = maxOf(actualActiveCount, pendingBehind).coerceIn(1, 5)

                val result = multiSims[decisionOpponentCount]
                val advResult = multiAdvSims[decisionOpponentCount]
                val recommendation = multiL1[decisionOpponentCount]
                val l2Recommendation = multiL2[decisionOpponentCount]
                val advRecommendation = multiAdv[decisionOpponentCount]

                val l4Recommendation = AdvisorEngine.computeRecommendationL4(
                    heroCard1 = state.heroCard1,
                    heroCard2 = state.heroCard2,
                    board = state.board,
                    potSize = state.potSize,
                    heroBet = state.heroBet,
                    opponents = state.opponents,
                    activeOpponentsCount = decisionOpponentCount,
                    simResult = advResult,
                    settings = state.settings,
                    position = state.position,
                    stage = state.stage,
                    smallBlind = state.smallBlind,
                    bigBlind = state.bigBlind,
                    heroStack = state.heroStack,
                    isBbDisplay = state.isBbDisplay
                )

                withContext(Dispatchers.Main) {
                    val finalState = state.copy(
                        simulationResult = result,
                        advancedSimulationResult = advResult,
                        recommendation = recommendation,
                        l2Recommendation = l2Recommendation,
                        advancedRecommendation = advRecommendation,
                        l4Recommendation = l4Recommendation,
                        multiL1Recs = multiL1,
                        multiL2Recs = multiL2,
                        multiAdvRecs = multiAdv,
                        multiSimResults = multiSims,
                        multiAdvSimResults = multiAdvSims
                    )
                    DiagnosticsEngine.runDiagnostics(
                        finalState, 
                        recommendation, 
                        l2Recommendation, 
                        advRecommendation, 
                        l4Recommendation
                    )

                    _uiState.update {
                        it.copy(
                            isCalculating = false,
                            simulationResult = result,
                            recommendation = recommendation,
                            advancedSimulationResult = advResult,
                            l2Recommendation = l2Recommendation,
                            advancedRecommendation = advRecommendation,
                            l4Recommendation = l4Recommendation,
                            multiL1Recs = multiL1,
                            multiL2Recs = multiL2,
                            multiAdvRecs = multiAdv,
                            multiSimResults = multiSims,
                            multiAdvSimResults = multiAdvSims
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isCalculating = false, errorMessage = "Sim failed: ${e.message}") }
                }
            }
        }
    }

    private fun triggerAutoCalculation() {
        autocalcJob?.cancel()
        autocalcJob = viewModelScope.launch(Dispatchers.Default) {
            // Debounce automatically to avoid calculations on quick successive taps
            delay(250)
            withContext(Dispatchers.Main) {
                startCalculation()
            }
        }
    }

    private fun findNextEmptyTarget(state: PokerUiState): SelectionTarget? {
        // Find logical empty target to navigate to:
        // Hero Card 1 -> Hero Card 2 -> Flop 1..3 -> Turn -> River -> Active Opponent Cards
        if (state.heroCard1 == null) return SelectionTarget.Hero1
        if (state.heroCard2 == null) return SelectionTarget.Hero2

        // Check Board
        for (i in 0 until 5) {
            if (state.board[i] == null) return SelectionTarget.Board(i)
        }

        // Check Active Non-Random Opponents with empty card slots
        for (opp in state.opponents.filter { it.isActive && !it.isRandom }) {
            if (opp.card1 == null) return SelectionTarget.OpponentCard(opp.id, 1)
            if (opp.card2 == null) return SelectionTarget.OpponentCard(opp.id, 2)
        }

        return null // No free slots
    }

    fun importHandHistory(inputStream: java.io.InputStream): Int {
        val count = HandHistoryParser.parseHandHistory(inputStream, prefsManager)
        if (count > 0) {
            // Re-sync all current opponents' stats with the newly imported counts
            _uiState.update { state ->
                state.copy(
                    opponents = state.opponents.map { opp ->
                        val loadedStats = prefsManager.loadPlayerStats(opp.nickname)
                        opp.copy(stats = loadedStats)
                    }
                )
            }
            triggerAutoCalculation()
        }
        return count
    }
}
