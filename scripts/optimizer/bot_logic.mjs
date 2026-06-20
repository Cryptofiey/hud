// bot_logic.mjs
// Порт логики Advisor Engine для симулятора (песочница принятия решений)

export const BotParams = {
    // Вентили уровня L1 (Математика и шансы)
    potOddsMargin: 0.05,       // Насколько запас по шансам должен быть выше Pot Odds 
    multiwayHazardBase: 0.005, // Пенальти на каждого доп. игрока в банке
    
    // Вентили уровня L2 (Статистика оппонентов и стек)
    shortStackThreshold: 8.0,  // ББ: грань, ниже которой переходим в пуш/фолд
    shortStackRiskFactor: 0.08,// Фактор риска привязанности стека
    
    // Вентили уровня L3 (Поведение)
    bluffTolerance: 0.10,      // Дельта для колла/рейза против блефера
    
    // Вентили уровня L4 (Синтетика/Герои)
    sharkRespectOverfold: 0.05 // Доп. накидка на фолд против типажа Акулы
};

// Диапазоны калибровки (min, max, step) для оптимизатора (небольшие шаги для подгонки)
export const CalibrationRanges = {
    potOddsMargin:       { min: 0.00, max: 0.15, step: 0.01 },
    multiwayHazardBase:  { min: 0.001, max: 0.02, step: 0.002 },
    shortStackThreshold: { min: 5.0, max: 15.0, step: 1.0 },
    bluffTolerance:      { min: 0.00, max: 0.20, step: 0.02 },
    sharkRespectOverfold:{ min: 0.00, max: 0.15, step: 0.01 }
};

/**
 * JS-Имитация механики принятия решения как в AdvisorEngine.kt
 * @param {number} handStrength Собственное эквити / Сила комбинации [0.0 - 1.0]
 * @param {number} potOdds Шансы банка (сколько доставить / общий пот) [0.0 - 1.0]
 * @param {number} stackBB Размер нашего стека в ББ
 * @param {number} numOpponents Количество активных оппонентов в раздаче
 * @param {object} params Текущие вентили калибровки
 * @param {string} oppArchetype Психологический паттерн оппонента (например, "Shark", "Fish")
 * @returns {object} { action: "FOLD"|"CALL"|"RAISE"|"ALL-IN", level: "L1"|"L2"|"L3"|"L4" }
 */
export function botDecide(handStrength, potOdds, stackBB, numOpponents, params, oppArchetype) {
    // 1. Формирование целевых шансов наложениями (Penalty Adjustments)
    let targetOdds = potOdds + params.potOddsMargin + 
                    (numOpponents > 1 ? params.multiwayHazardBase * numOpponents : 0);
    
    // Влияние профилей оппонентов
    if (oppArchetype === "Shark") targetOdds += params.sharkRespectOverfold;
    if (oppArchetype === "Fish") targetOdds -= params.bluffTolerance;

    // L2 Short Stack Push/Fold
    if (stackBB < Math.floor(params.shortStackThreshold)) {
        // Трешхолд для пуша коротким стеком
        if (handStrength >= targetOdds - 0.05) return { action: "ALL-IN", level: "L2" };
        else return { action: "FOLD", level: "L2" };
    }
    
    // Core GTO Decision Check
    if (handStrength >= targetOdds) {
        // При запасе эквити >15% делаем рейз
        if (handStrength > targetOdds + 0.15) return { action: "RAISE", level: "L1" };
        return { action: "CALL", level: "L1" };
    }
    
    return { action: "FOLD", level: "L1" };
}
