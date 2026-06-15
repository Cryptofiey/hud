const fs = require('fs');

let pokerModel = fs.readFileSync('app/src/main/java/com/example/PokerModel.kt', 'utf8');
if (!pokerModel.includes('data class ScannedBox')) {
    pokerModel = pokerModel.replace(
        'data class OpponentState(',
        `data class ScannedBox(
    val rect: android.graphics.Rect,
    val label: String
)

data class OpponentState(`
    );
    fs.writeFileSync('app/src/main/java/com/example/PokerModel.kt', pokerModel);
}

let advisorEngine = fs.readFileSync('app/src/main/java/com/example/AdvisorEngine.kt', 'utf8');
advisorEngine = advisorEngine.replace(
    '@Transient var profileBoundingBoxes: List<android.graphics.Rect>? = null',
    '@Transient var profileBoundingBoxes: List<ScannedBox>? = null'
);
fs.writeFileSync('app/src/main/java/com/example/AdvisorEngine.kt', advisorEngine);

let pokerUiState = fs.readFileSync('app/src/main/java/com/example/PokerViewModel.kt', 'utf8');
pokerUiState = pokerUiState.replace(
    'val profileBoxes: List<android.graphics.Rect>? = null',
    'val profileBoxes: List<ScannedBox>? = null'
);
fs.writeFileSync('app/src/main/java/com/example/PokerViewModel.kt', pokerUiState);

let pokerHudService = fs.readFileSync('app/src/main/java/com/example/PokerHudService.kt', 'utf8');
pokerHudService = pokerHudService.replace(
    'val profileBoxes: List<android.graphics.Rect>? = null',
    'val profileBoxes: List<ScannedBox>? = null'
);
// Also increase display time from 2500 to 5500 (2500 + 3000 = 5500 ms) as requested by user.
pokerHudService = pokerHudService.replace(
    'kotlinx.coroutines.delay(2500)',
    'kotlinx.coroutines.delay(5500)'
);
fs.writeFileSync('app/src/main/java/com/example/PokerHudService.kt', pokerHudService);

let screenScanner = fs.readFileSync('app/src/main/java/com/example/ScreenScanner.kt', 'utf8');
screenScanner = screenScanner.replace(
    'var profileBoxesToHighlight: List<android.graphics.Rect>? = null',
    'var profileBoxesToHighlight: List<ScannedBox>? = null'
);
fs.writeFileSync('app/src/main/java/com/example/ScreenScanner.kt', screenScanner);
