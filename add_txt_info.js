const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/example/PokerHudService.kt', 'utf8');

const txtCardsInfoStr = `        val txtCardsInfo = TextView(this).apply {
            text = "SCANNING"
            setTextColor(AndroidColor.WHITE)
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setShadowLayer(4f, 0f, 2f, AndroidColor.BLACK)
        }
        content.addView(txtCardsInfo)
`;

code = code.replace(
`        content.addView(spacer)

        val laserLine = View(this).apply {`,
`        content.addView(spacer)
        
` + txtCardsInfoStr + `
        val laserLine = View(this).apply {`
);

const stateCollectStr = `                    val cards = state.board.filterNotNull()
                    if (cards.isNotEmpty()) {
                        txtCardsInfo.text = cards.joinToString(" ") { "\${it.rank.symbol}\${it.suit.symbol}" }
                        txtCardsInfo.setTextColor(AndroidColor.parseColor("#FF2196F3"))
                    } else {
                        txtCardsInfo.text = "NOT FOUND"
                        txtCardsInfo.setTextColor(AndroidColor.GRAY)
                    }`;

code = code.replace(
`            launch {
                PokerHudSharedState.uiState.collect { state ->
                    // Removed txtCardsInfo updates
                }
            }`,
`            launch {
                PokerHudSharedState.uiState.collect { state ->
` + stateCollectStr + `
                }
            }`
);

fs.writeFileSync('app/src/main/java/com/example/PokerHudService.kt', code);
