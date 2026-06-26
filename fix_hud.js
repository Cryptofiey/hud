const fs = require('fs');

let sContent = fs.readFileSync('app/src/main/java/com/example/PokerHudService.kt', 'utf8');

// Fix the cascade replacement bug manually for buttons and margins
sContent = sContent.replace(/layoutParams = LinearLayout\.LayoutParams\(dpToPx\(2f\), dpToPx\(2f\)\)/g, 'layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f))');
sContent = sContent.replace(/layoutParams = FrameLayout\.LayoutParams\(dpToPx\(2f\), dpToPx\(2f\)\)/g, 'layoutParams = FrameLayout.LayoutParams(dpToPx(24f), dpToPx(24f))');

// Increase text sizes for CheckBoxes and Titles
sContent = sContent.replace(/textSize = 4\.5f/g, 'textSize = 8f');
sContent = sContent.replace(/textSize = 5f/g, 'textSize = 9f');

// Padding on CheckBoxes
sContent = sContent.replace(/setPadding\(dpToPx\(2f\), dpToPx\(2f\), dpToPx\(2f\), dpToPx\(2f\)\)/g, 'setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))');

// Padding for READ PROFILE STATS
sContent = sContent.replace(/setPadding\(dpToPx\(2f\), 0, dpToPx\(2f\), 0\)/g, 'setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))');
sContent = sContent.replace(/dpToPx\(28f\)/g, 'dpToPx(36f)');

// Remove showScannerBoxes.value = true from READ PROFILE STATS
sContent = sContent.replace(/PokerHudSharedState\.showScannerBoxes\.value = true\n\s*if \(screenScanner != null\)/, 'if (screenScanner != null)');

fs.writeFileSync('app/src/main/java/com/example/PokerHudService.kt', sContent);

console.log("Fixed HUD");
