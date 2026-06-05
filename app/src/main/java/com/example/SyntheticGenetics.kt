package com.example

import kotlin.random.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Уровни поколений мутантов (генов).
 * z0 - сырые параметры с канваса.
 * z1 - результат скрещивания z0 + z0.
 * z1.5 - результат скрещивания z0 + z1.
 * z2 - результат скрещивания z1 + z1.
 */
enum class GenLevel(val code: String) {
    Z0("z0"), Z1("z1"), Z1_5("z1.5"), Z2("z2"), Z_DEEP("z_deep")
}

/** 
 * Математические операции для синтеза (скрещивания мутантов).
 */
enum class RecombinationOp {
    ADD, SUBTRACT, MULTIPLY, DIVIDE, WEIGHTED_SUM;
    
    companion object {
        fun randomOp() = values()[Random.nextInt(values().size)]
    }
}

/** Базовый класс для Гена (Параметра). */
sealed class MutantGene(
    val id: String,
    val name: String,
    val genLevel: GenLevel
) {
    abstract fun calculate(stats: PlayerStats): Float
}

/** Z0: Сырые параметры снятые с канваса (VPIP, PFR, 3Bet и т.д.) */
class RawGene(
    id: String,
    name: String,
    val extractor: (PlayerStats) -> Float
) : MutantGene(id, name, GenLevel.Z0) {
    override fun calculate(stats: PlayerStats): Float = extractor(stats)
}

/** Z1+: Результат скрещивания параметров */
class CrossbredGene(
    id: String,
    name: String,
    val parentA: MutantGene,
    val parentB: MutantGene,
    val op: RecombinationOp,
    val weightA: Float = 1f,
    val weightB: Float = 1f
) : MutantGene(id, name, determineGeneration(parentA.genLevel, parentB.genLevel)) {

    override fun calculate(stats: PlayerStats): Float {
        val a = parentA.calculate(stats) * weightA
        val b = parentB.calculate(stats) * weightB
        return when (op) {
            RecombinationOp.ADD -> a + b
            RecombinationOp.SUBTRACT -> a - b
            RecombinationOp.MULTIPLY -> a * b
            RecombinationOp.DIVIDE -> if (b != 0f) a / b else 0f
            RecombinationOp.WEIGHTED_SUM -> a + b
        }
    }
}

/** Определение поколения мутанта на основе его "родителей" */
fun determineGeneration(a: GenLevel, b: GenLevel): GenLevel {
    return when {
        a == GenLevel.Z0 && b == GenLevel.Z0 -> GenLevel.Z1
        (a == GenLevel.Z0 && b == GenLevel.Z1) || (a == GenLevel.Z1 && b == GenLevel.Z0) -> GenLevel.Z1_5
        a == GenLevel.Z1 && b == GenLevel.Z1 -> GenLevel.Z2
        else -> GenLevel.Z_DEEP
    }
}

/**
 * -----------------------------------------------------------------------------------------
 * БИОХИМИЯ И НЕЙРОМОДЕЛИРОВАНИЕ (Creatures-inspired) 
 * -----------------------------------------------------------------------------------------
 */

/** Драйвы (Потребности), которые двигают алгоритмом. */
enum class Drive {
    HUNGER_EV,   // Голод по профиту (EV)
    CURIOSITY,   // Жажда исследований новых генов
    SURVIVAL     // Избегание дисперсии / банкротства
}

/** Химикаты (Реакции на события в игровом движке) */
enum class Chemical {
    DOPAMINE,    // Награда: Удачное предсказание или +EV действие 
    CORTISOL,    // Наказание / Убыток: -EV
    ADRENALINE   // Возбуждение: Крупный банк, стрессовая ситуация
}

/**
 * Нейронный лоб: связывает Ген со Статистикой успешности (Синапс)
 */
class Synapse(val gene: MutantGene) {
    var weight: Float = 0.5f     // Сила связи (0..1)
    var testCount: Int = 0       // Сколько раз ген тестировался
    
    // Хеббианское обучение: += Dopamine, -= Cortisol
    fun applyReinforcement(dopamine: Float, cortisol: Float) {
        weight = (weight + (dopamine * 0.1f) - (cortisol * 0.05f)).coerceIn(0.01f, 1f)
        testCount++

        // Точечная мутация веса с вероятностью 5% для избежания стагнации (Point Mutation)
        if (Random.nextFloat() < 0.05f) {
            weight = (weight + (Random.nextFloat() * 0.2f - 0.1f)).coerceIn(0.01f, 1f)
        }
    }
}

class BiochemistryEngine {
    private val drives = mutableMapOf(
        Drive.HUNGER_EV to 50f, 
        Drive.CURIOSITY to 50f, 
        Drive.SURVIVAL to 50f
    )
    
    private val chemicals = mutableMapOf(
        Chemical.DOPAMINE to 0f,
        Chemical.CORTISOL to 0f,
        Chemical.ADRENALINE to 0f
    )

    fun tickBiochemistry() {
        // Химия со временем распадается
        chemicals.replaceAll { _, v -> (v * 0.9f).coerceAtLeast(0f) }
        // Драйвы растут, если не удовлетворяются
        drives[Drive.HUNGER_EV] = (drives.getValue(Drive.HUNGER_EV) + 0.5f).coerceAtMost(100f)
        drives[Drive.CURIOSITY] = (drives.getValue(Drive.CURIOSITY) + 1.0f).coerceAtMost(100f)
    }

    fun injectChemical(chem: Chemical, amount: Float) {
        chemicals[chem] = (chemicals.getOrDefault(chem, 0f) + amount).coerceAtMost(100f)
        when(chem) {
            Chemical.DOPAMINE -> {
                drives[Drive.HUNGER_EV] = (drives.getValue(Drive.HUNGER_EV) - amount * 2).coerceAtLeast(0f)
            }
            Chemical.CORTISOL -> {
                drives[Drive.SURVIVAL] = (drives.getValue(Drive.SURVIVAL) + amount).coerceAtMost(100f)
            }
            Chemical.ADRENALINE -> {}
        }
    }
    
    fun getCuriosityLevel() = drives[Drive.CURIOSITY] ?: 50f
    fun getHungerLevel() = drives[Drive.HUNGER_EV] ?: 50f
    fun getSurvivalLevel() = drives[Drive.SURVIVAL] ?: 50f
    fun satisfyCuriosity() {
        drives[Drive.CURIOSITY] = (drives.getValue(Drive.CURIOSITY) - 30f).coerceAtLeast(0f)
    }
}

/** 
 * Синтетический геном.
 * Каталог, где мы будем хранить всех рабочих симулякров бота.
 */
object SyntheticGenome {
    val biochemistry = BiochemistryEngine()
    val synapsis = CopyOnWriteArrayList<Synapse>()
    
    // ---- Z0 GENES (Чистое сырье) ----
    val vpip = RawGene("z0_vpip", "VPIP") { it.histVpip ?: it.vpip }
    val pfr = RawGene("z0_pfr", "PFR") { it.histPfr ?: it.pfr }
    val wsd = RawGene("z0_wsd", "Won at Showdown") { it.histWsd ?: 50f }
    val wtsd = RawGene("z0_wtsd", "Went to Showdown") { it.histWtsd ?: 30f }
    val foldTo3Bet = RawGene("z0_f3b", "Fold to 3Bet") { it.histFoldTo3Bet ?: 45f }
    val cbet = RawGene("z0_cbet", "CBet") { it.histCBet ?: 55f }
    val checkRaise = RawGene("z0_cr", "Check-Raise") { it.histCheckRaise ?: 10f }
    val steal = RawGene("z0_steal", "Steal") { it.histSteal ?: 35f }
    val foldToCbet = RawGene("z0_fcbet", "Fold to CBet") { it.histFoldToCBet ?: 45f }
    val af = RawGene("z0_af", "Aggression Factor") { it.aggressionFactor }

    // ---- Z1 GENES (Первые мутанты) ----
    val callingStationIndex = CrossbredGene("z1_gap", "Passive Corridor (Gap)", vpip, pfr, RecombinationOp.SUBTRACT)
    val honestyIndex = CrossbredGene("z1_honesty", "Postflop Honesty Index", wsd, wtsd, RecombinationOp.SUBTRACT)
    val preflopBluffing = CrossbredGene("z1_pf_bluff", "Preflop Bluff Tendency", pfr, foldTo3Bet, RecombinationOp.MULTIPLY, weightB = 0.01f)
    val dangerIndex = CrossbredGene("z1_danger", "Multi-Street Danger", cbet, checkRaise, RecombinationOp.WEIGHTED_SUM, weightA = 0.7f, weightB = 3f)
    val stickyIndex = CrossbredGene("z1_sticky", "Stickiness (Low Folds + WTSD)", wtsd, foldToCbet, RecombinationOp.SUBTRACT)
    val postflopAgitation = CrossbredGene("z1_agitation", "Postflop Agitation", af, pfr, RecombinationOp.DIVIDE)

    // ---- Z1.5 (Агрессивные мутанты из смежных поколений) ----
    val aggroBluffIndex = CrossbredGene("z1.5_aggro_bluff", "Aggressive Bluffing", preflopBluffing, checkRaise, RecombinationOp.MULTIPLY)
    val stubbornBlindDefender = CrossbredGene("z1.5_stubborn_blind", "Stubborn Blind Defender", steal, stickyIndex, RecombinationOp.MULTIPLY, weightB = 0.5f)

    // ---- Z2 (Deep Mutants - Мутанты второго синтеза) ----
    val maniacIndex = CrossbredGene("z2_maniac", "Maniac Index", dangerIndex, postflopAgitation, RecombinationOp.ADD)
    val perfectVictim = CrossbredGene("z2_victim", "Perfect Victim (Whale)", callingStationIndex, stickyIndex, RecombinationOp.ADD)
    val trapVulnerability = CrossbredGene("z2_trap_vuln", "Vulnerability to Traps", maniacIndex, honestyIndex, RecombinationOp.SUBTRACT)

    val activeGenome = CopyOnWriteArrayList<MutantGene>(
        listOf(
            callingStationIndex, honestyIndex, preflopBluffing, dangerIndex,
            stickyIndex, postflopAgitation, aggroBluffIndex, stubbornBlindDefender,
            maniacIndex, perfectVictim, trapVulnerability
        )
    )
    
    // Инициализация синапсов (по умолчанию вес 0.5 - нейтральный)
    init {
        activeGenome.forEach { synapsis.add(Synapse(it)) }
    }
}

/**
 * Monte Carlo Воронка и Эволюционный Инкубатор.
 */
object EvolutionFunnel {
    
    // Эмуляция: "Случайный опыт из внешнего мира".
    // Если результат положительный впрыскиваем Дофамин. Если отрицательный - Кортизол.
    fun simulateExperience(realEvDelta: Float? = null, activeStats: PlayerStats? = null) {
        SyntheticGenome.biochemistry.tickBiochemistry()
        
        // 1. Учим текущие синапсы
        SyntheticGenome.synapsis.forEach { synapse ->
            // Используем реальный EV с дашборда (или симулируем если его нет)
            val ev = realEvDelta ?: ((Random.nextFloat() * 20f) - 10f) 
            
            // Возбуждение синапса зависит от того, насколько он был бы активен
            val activityMult = if (activeStats != null) (synapse.gene.calculate(activeStats) / 100f).coerceIn(0.1f, 1f) else 1f
            val adjustedEv = ev * activityMult

            if (adjustedEv > 0f) {
                // Успешное применение гена
                SyntheticGenome.biochemistry.injectChemical(Chemical.DOPAMINE, adjustedEv)
                synapse.applyReinforcement(dopamine = adjustedEv, cortisol = 0f)
            } else {
                // Ошибка применения гена
                val cost = Math.abs(adjustedEv)
                SyntheticGenome.biochemistry.injectChemical(Chemical.CORTISOL, cost)
                synapse.applyReinforcement(dopamine = 0f, cortisol = cost)
            }
        }
        
        val POPULATION_LIMIT = 30

        // 2. Если Дофамина мало, а Драйв CURIOSITY высок - это значит алгоритму скучно и он хочет размножать гены.
        if (SyntheticGenome.biochemistry.getCuriosityLevel() > 80f && SyntheticGenome.activeGenome.size < POPULATION_LIMIT) {
            breedNewMutation()
            SyntheticGenome.biochemistry.satisfyCuriosity()
        }
        
        // 3. Отбраковка мертвых синапсов (Хеббианская деградация)
        val deadSynapsis = SyntheticGenome.synapsis.filter { it.weight < 0.1f && it.testCount > 10 }
        deadSynapsis.forEach { 
            SyntheticGenome.synapsis.remove(it)
            SyntheticGenome.activeGenome.remove(it.gene)
        }

        // 4. Строгий отбор (Culling) при превышении лимита перенаселения
        if (SyntheticGenome.synapsis.size > POPULATION_LIMIT) {
            // Убиваем самого слабого по весу
            val weakest = SyntheticGenome.synapsis.minByOrNull { it.weight }
            weakest?.let {
                SyntheticGenome.synapsis.remove(it)
                SyntheticGenome.activeGenome.remove(it.gene)
            }
        }

        // 5. Защита от вымирания вида (Extinction Prevention Event)
        if (SyntheticGenome.synapsis.size < 5) {
            SyntheticGenome.biochemistry.injectChemical(Chemical.ADRENALINE, 50f) // Паника вида
            val baseGenes = listOf(SyntheticGenome.vpip, SyntheticGenome.pfr, SyntheticGenome.wsd, SyntheticGenome.wtsd, SyntheticGenome.cbet, SyntheticGenome.foldTo3Bet)
            val pA = baseGenes.random()
            val pB = baseGenes.random()
            if (pA != pB) {
               val rescueGene = CrossbredGene("z_rescue_${System.currentTimeMillis()}", "Rescue Mutation", pA, pB, RecombinationOp.randomOp())
               SyntheticGenome.activeGenome.add(rescueGene)
               SyntheticGenome.synapsis.add(Synapse(rescueGene))
            }
        }
    }
    
    // Размножение (Скрещивание двух лучших синапсов)
    private fun breedNewMutation() {
        val topGenes = SyntheticGenome.synapsis
            .filter { it.testCount > 5 }
            .sortedByDescending { it.weight }
            .take(5)
            .map { it.gene }
            
        if (topGenes.size >= 2) {
            val parentA = topGenes[Random.nextInt(topGenes.size)]
            val parentB = topGenes[Random.nextInt(topGenes.size)]
            
            if (parentA == parentB) return
            
            val newOp = RecombinationOp.randomOp()
            val newId = "z_auto_${System.currentTimeMillis()}"
            val newName = "Auto-Synth: ${parentA.id} + ${parentB.id}"
            
            val childGene = CrossbredGene(
                id = newId,
                name = newName,
                parentA = parentA,
                parentB = parentB,
                op = newOp,
                weightA = 1f + (Random.nextFloat() * 0.5f - 0.25f),
                weightB = 1f + (Random.nextFloat() * 0.5f - 0.25f)
            )
            
            SyntheticGenome.activeGenome.add(childGene)
            SyntheticGenome.synapsis.add(Synapse(childGene))
        }
    }
}

/**
 * Нейронный лоб принятия решений (Decision Lobe).
 * Пока что выполняет функцию заглушки, которая собирает сигналы 
 * от биохимии и синапсов, но не передает в основной движок.
 */
object DecisionLobe {
    var isActive: Boolean = false // Выключен по умолчанию
    
    enum class ProposedAction {
        FOLD, CALL, RAISE, WAIT
    }

    /**
     * Сбор данных из синапсов и биохимии для генерации синтетического решения
     */
    fun processSensoryInput(stats: PlayerStats?): ProposedAction {
        if (!isActive) return ProposedAction.WAIT
        if (stats == null) return ProposedAction.WAIT
        
        val hunger = SyntheticGenome.biochemistry.getHungerLevel()
        val survival = SyntheticGenome.biochemistry.getSurvivalLevel()
        
        // Берем только сильные связи
        val activeSynapses = SyntheticGenome.synapsis.filter { it.weight > 0.6f && it.testCount > 5 }
        
        var foldActivation = 0f
        var callActivation = 0f
        var raiseActivation = 0f
        
        // Влияние базовых химикатов (Драйвов)
        foldActivation += (survival * 0.5f)
        raiseActivation += (hunger * 0.4f)
        callActivation += (hunger * 0.2f)
        
        // Влияние генов (через синапсы)
        for (syn in activeSynapses) {
            val geneVal = syn.gene.calculate(stats)
            when {
                syn.gene.id.contains("bluff") -> raiseActivation += (geneVal * syn.weight)
                syn.gene.id.contains("station") || syn.gene.id.contains("sticky") -> callActivation += (geneVal * syn.weight)
                syn.gene.id.contains("danger") || syn.gene.id.contains("maniac") -> foldActivation += (geneVal * syn.weight)
            }
        }
        
        // Winner-Takes-All (Победитель забирает всё)
        return when {
            foldActivation > callActivation && foldActivation > raiseActivation -> ProposedAction.FOLD
            raiseActivation > callActivation && raiseActivation > foldActivation -> ProposedAction.RAISE
            callActivation > foldActivation && callActivation > raiseActivation -> ProposedAction.CALL
            else -> ProposedAction.WAIT
        }
    }
}
