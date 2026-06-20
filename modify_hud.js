const fs = require('fs');

let sContent = fs.readFileSync('app/src/main/java/com/example/PokerHudService.kt', 'utf8');

// Replace D82229 with 1E88E5 inside PokerHudService as well
sContent = sContent.replace(/FFD82229/g, 'FF1E88E5'); // Blue frame instead of red frame for HUD DASHBOARD

// More padding reductions for the HUD
// dpToPx(8f) -> dpToPx(4f)
sContent = sContent.replace(/\bdpToPx\(8f\)/g, 'dpToPx(4f)');
// dpToPx(12f) -> dpToPx(6f)
sContent = sContent.replace(/\bdpToPx\(12f\)/g, 'dpToPx(6f)');
// dpToPx(24f) -> dpToPx(16f)
sContent = sContent.replace(/\bdpToPx\(24f\)/g, 'dpToPx(16f)');
// minWidth = dpToPx(150f) -> 120f
sContent = sContent.replace(/minWidth = dpToPx\(150f\)/g, 'minWidth = dpToPx(120f)');

// Sizes
sContent = sContent.replace(/textSize = 6f/g, 'textSize = 5f');
sContent = sContent.replace(/textSize = 6\.5f/g, 'textSize = 5f');
sContent = sContent.replace(/textSize = 5\.5f/g, 'textSize = 4.5f');
sContent = sContent.replace(/textSize = 5f/g, 'textSize = 4.5f');
sContent = sContent.replace(/textSize = 9f/g, 'textSize = 5f'); // Live Opponent stuff

fs.writeFileSync('app/src/main/java/com/example/PokerHudService.kt', sContent);

console.log("HUD modified");
