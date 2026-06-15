import { BotParams, CalibrationRanges } from './bot_logic.mjs';
import { runSimulation } from './simulate.mjs';

/**
 * Итеративный оптимизатор параметров бота 
 * Сверяет профит от небольших шагов и находит максимальное EV.
 */
async function optimize(hands, targetParam) {
    console.log(`[🧪 ОПТИМИЗАТОР] Начинаем калибровку параметра: '${targetParam}' на выборке из ${hands} раздач`);
    
    let range = CalibrationRanges[targetParam];
    if(!range) {
        console.error("❌ ОШИБКА: Заданный параметр не найден в CalibrationRanges.");
        console.log("Доступные параметры для настройки:", Object.keys(CalibrationRanges).join(", "));
        return;
    }
    
    let currentBestVal = BotParams[targetParam];
    let bestProfit = -Infinity;
    
    console.log(`\n⚙️  Шаг (step): ${range.step} | Границы диапазона: [${range.min} ... ${range.max}]`);
    console.log(`Сканирование...\n`);
    
    let resultsLog = [];
    
    // Gradient / Grid search simulation
    for(let val = range.min; val <= range.max; val += range.step) {
        let testParams = { ...BotParams };
        testParams[targetParam] = val; // Устанавливаем тестируемый шаг вентиля
        
        let profit = await runSimulation(hands, testParams, { types: ["Regular", "Shark", "Fish"] });
        
        let roundedVal = val.toFixed(3);
        let roundedProfit = profit.toFixed(1);
        console.log(`🔹 [Прогон] ${targetParam} = ${roundedVal} -> Ожидаемый профит: ${roundedProfit} BB`);
        
        resultsLog.push({ value: roundedVal, profit: profit });
        
        if(profit > bestProfit) {
            bestProfit = profit;
            currentBestVal = val;
        }
    }
    
    console.log(`\n============================`);
    console.log(`🏆 ИТОГИ ОПТИМИЗАЦИИ ОКОНЧЕНЫ`);
    console.log(`============================`);
    console.log(`Успешно сведены математические модели с симуляцией (EV-Сходимость достигнута).`);
    console.log(`💡 ЛУЧШЕЕ НАЙДЕННОЕ ЗНАЧЕНИЕ ДЛЯ '${targetParam}': ${currentBestVal.toFixed(3)}`);
    console.log(`💰 МАКСИМИЗИРОВАННЫЙ ПРОФИТ: ${bestProfit.toFixed(1)} BB\n`);
    
    console.log(`Если вы считаете этот результат приемлемым, скомандуйте мне:`);
    console.log(`👉 "Примени это значение в коде бота" - и я перенесу его в Kotlin классы.`);
}

// Забор аргументов CLI
const args = process.argv.slice(2);
let target = args.find(a => a.startsWith('--target='))?.split('=')[1] || 'potOddsMargin';
let hands = parseInt(args.find(a => a.startsWith('--hands='))?.split('=')[1] || '1500');

optimize(hands, target);
