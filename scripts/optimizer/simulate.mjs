import { botDecide } from './bot_logic.mjs';

/**
 * M.A.R.S - Monte-Carlo Simulator
 * Модуль проведения пакетных симуляций игр (1000-2000 раздач)
 */

// Простое распределение силы руки оппонентов
const OpponentSkillFactor = {
    "Fish": 0.90,   // Разыгрывает более слабые спектры
    "Regular": 1.05,// Играет спектры чуть выше нормы
    "Shark": 1.15   // Превосходный баланс велью/блефов
};

export async function runSimulation(iterations, params, opponentsConfig) {
    let rawProfit = 0; // в Больших Блайндах (BB)
    
    // Распаковка конфигурации оппонентов (например, { vpip: 0.30, types: ["Regular", "Fish"] })
    let oppTypes = opponentsConfig.types || ["Regular"];
    
    // Прогоняем заданное количество раздач
    for(let i = 0; i < iterations; i++) {
        // Выбираем случайного оппонента для текущей раздачи
        let currentOpp = oppTypes[Math.floor(Math.random() * oppTypes.length)];
        
        // --- ГЕНЕРАЦИЯ СИТУАЦИИ (Генератор Монте-Карло) ---
        // Истинная сила нашей руки во вскрытии (от 0.1 до 0.95 эквити)
        let handStrength = 0.10 + (Math.random() * 0.85);
        // Шансы банка, которые мы получаем на этой улице
        let potOdds = 0.20 + (Math.random() * 0.30); // 20% - 50%
        // Наш текущий эффективный стек в ББ
        let stackBB = 3.0 + Math.random() * 40.0; // от 3 ББ до 43 ББ
        let opponentsCount = 1 + Math.floor(Math.random() * 3); // 1-3 оппонента
        
        // --- РАБОТА МЕХАНИЗМОВ БОТА ---
        let decision = botDecide(handStrength, potOdds, stackBB, opponentsCount, params, currentOpp);
        
        // --- ПРОВЕРКА РЕЗУЛЬТАТА И ПРОФИТА ---
        // Имитация скрытой силы оппонента с учетом его профиля (Shark/Fish/Reg)
        let oppBaseStrength = 0.15 + (Math.random() * 0.85); 
        let oppStrength = Math.min(0.99, oppBaseStrength * (OpponentSkillFactor[currentOpp] || 1.0));
        
        let bbAtRisk = potOdds * 10; // Чем выше шансы - тем больше ставка к возврату. Эмуляция риска в ББ
        
        if (decision.action === "FOLD") {
            // Если бот пасует, он экономит текущую ставку, но теряет вложенные ранее мертвые деньги
            rawProfit -= (bbAtRisk * 0.2); 
        } else {
            // Если мы зашли в раздачу (Call / Raise / All-In)
            let isWin = handStrength > oppStrength;
            
            // Если All-In, риск равен всему стеку, если Рейз - риск х3
            let riskMultiplier = 1.0;
            if (decision.action === "ALL-IN") riskMultiplier = stackBB / bbAtRisk;
            if (decision.action === "RAISE") riskMultiplier = 3.0;
            
            if (isWin) {
                 rawProfit += bbAtRisk * riskMultiplier; 
            } else {
                 rawProfit -= bbAtRisk * riskMultiplier;
            }
        }
    }
    
    return rawProfit;
}

// Позволяет запускать файл напрямую из терминала
if (process.argv[1].includes('simulate.mjs')) {
    const args = process.argv.slice(2);
    let iterations = parseInt(args.find(a => a.startsWith('--hands='))?.split('=')[1] || '1000');
    
    import('./bot_logic.mjs').then(async (m) => {
        console.log(`[🚀 M.A.R.S.] Запуск тестовой симуляции: ${iterations} раздач.`);
        let profit = await runSimulation(iterations, m.BotParams, { types: ["Regular", "Shark", "Fish"] });
        console.log(`✅ Итоговый расчетный профит стратегии: ${(profit).toFixed(1)} BB`);
    });
}
