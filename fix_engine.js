const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/example/AdvisorEngine.kt', 'utf8');

// 1. Fix getTargetPotOdds
code = code.replace(
    /val multiwayHazard = if \(activeOpponentsCount > 1\) \{(?:\r?\n.*?)*?\} else 0f/,
    `val multiwayHazard = if (activeOpponentsCount > 1) {
            (activeOpponentsCount - 1) * 0.015f // Reduced from 0.04f to stop exponential overfolds
        } else 0f`
);

code = code.replace(
    /val positionalHazard = getPositionalHazard\(position, isPreflop\)/,
    `val positionalHazard = getPositionalHazard(position, isPreflop) * 0.5f // Halved the hazard`
);

// 2. Fix L1 else condition
code = code.replace(
    /if \(l1Score > 0\.52f && s1 > 0\.28f\) \{/,
    `if (l1Score > 0.48f && s1 > (0.9f / (activeOpponentsCount + 1.0f))) {`
);

// 3. Fix L2 Unprofitable logic (+0.05 -> -0.05)
code = code.replace(
    /if \(l2Score > targetPotOdds \+ 0\.05f && \(isPreflop && sklanskyGroup <= 4\)\) \{/,
    `if (l2Score > targetPotOdds - 0.05f && (isPreflop && sklanskyGroup <= 4)) {`
);

// 4. Fix L3 L3.0 Branch
code = code.replace(
    /action = if \(isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds \+ 0\.05f\) "CALL" else "FOLD"/g,
    `action = if (isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds - 0.05f) "CALL" else "FOLD"`
);

// We need to catch other identical occurrences in L2.5 Branch as well
code = code.replace(
    /action = if \(isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds \+ 0\.05f\) "CALL" else "FOLD"/g,
    `action = if (isPreflop && sklanskyGroup <= 4 && l3Score > targetPotOdds - 0.05f) "CALL" else "FOLD"`
);

fs.writeFileSync('app/src/main/java/com/example/AdvisorEngine.kt', code);
console.log('Fixed AdvisorEngine folds!');
