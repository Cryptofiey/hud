const fs = require('fs');

let content = fs.readFileSync('app/src/main/java/com/example/MainActivity.kt', 'utf8');

// Colors
content = content.replace(/0xFF1B4D22/g, '0xFF121212');
content = content.replace(/0xFFD82229/g, '0xFF1E88E5'); // nice blue

// Fonts
content = content.replace(/20\.sp/g, '18.sp');
content = content.replace(/15\.sp/g, '13.sp');
content = content.replace(/14\.sp/g, '12.sp');
content = content.replace(/13\.sp/g, '11.sp');
content = content.replace(/12\.sp/g, '10.sp');
content = content.replace(/11\.sp/g, '9.sp');
content = content.replace(/10\.sp/g, '9.sp');

// Paddings & spacing
content = content.replace(/padding\(16\.dp\)/g, 'padding(12.dp)');
content = content.replace(/horizontal = 16\.dp, vertical = 14\.dp/g, 'horizontal = 12.dp, vertical = 8.dp');
content = content.replace(/height\(24\.dp\)/g, 'height(18.dp)');
content = content.replace(/height\(30\.dp\)/g, 'height(20.dp)');
content = content.replace(/spacedBy\(8\.dp\)/g, 'spacedBy(6.dp)');
content = content.replace(/spacedBy\(10\.dp\)/g, 'spacedBy(8.dp)');
content = content.replace(/spacedBy\(12\.dp\)/g, 'spacedBy(10.dp)');
content = content.replace(/padding\(12\.dp\)/g, 'padding(8.dp)');

fs.writeFileSync('app/src/main/java/com/example/MainActivity.kt', content);

let sContent = fs.readFileSync('app/src/main/java/com/example/PokerHudService.kt', 'utf8');

sContent = sContent.replace(/dpToPx\(60f\)/g, 'dpToPx(48f)');
sContent = sContent.replace(/textSize = 10\.5f/g, 'textSize = 8.5f');
sContent = sContent.replace(/textSize = 10f/g, 'textSize = 8f');
sContent = sContent.replace(/textSize = 9\.5f/g, 'textSize = 7.5f');
sContent = sContent.replace(/textSize = 8\.5f/g, 'textSize = 7f');
sContent = sContent.replace(/textSize = 8f/g, 'textSize = 6.5f');
sContent = sContent.replace(/textSize = 7\.5f/g, 'textSize = 6f');
sContent = sContent.replace(/textSize = 7f/g, 'textSize = 5.5f');
sContent = sContent.replace(/textSize = 6\.5f/g, 'textSize = 5f');

// Padding reducations in HUD
sContent = sContent.replace(/dpToPx\(50f\)/g, 'dpToPx(40f)');
sContent = sContent.replace(/dpToPx\(40f\)/g, 'dpToPx(32f)');
sContent = sContent.replace(/dpToPx\(30f\)/g, 'dpToPx(24f)');
sContent = sContent.replace(/dpToPx\(24f\)/g, 'dpToPx(20f)');
sContent = sContent.replace(/dpToPx\(20f\)/g, 'dpToPx(16f)');
sContent = sContent.replace(/dpToPx\(18f\)/g, 'dpToPx(14f)');
sContent = sContent.replace(/dpToPx\(16f\)/g, 'dpToPx(12f)');
sContent = sContent.replace(/dpToPx\(14f\)/g, 'dpToPx(11f)');
sContent = sContent.replace(/dpToPx\(12f\)/g, 'dpToPx(10f)');
sContent = sContent.replace(/dpToPx\(10f\)/g, 'dpToPx(8f)');
sContent = sContent.replace(/dpToPx\(8f\)/g, 'dpToPx(6f)');
sContent = sContent.replace(/dpToPx\(6f\)/g, 'dpToPx(4f)');
sContent = sContent.replace(/dpToPx\(4f\)/g, 'dpToPx(2f)');

fs.writeFileSync('app/src/main/java/com/example/PokerHudService.kt', sContent);

console.log("Done");
