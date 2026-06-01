const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/example/ProfileScanner.kt', 'utf8');

code = code.replace(
`        val lines = mutableListOf<String>()
        val lineBoxes = mutableListOf<android.graphics.Rect?>()
        val matchedBoxes = mutableListOf<android.graphics.Rect>()`,
`        val lines = mutableListOf<String>()
        val lineBoxes = mutableListOf<android.graphics.Rect?>()
        val matchedBoxes = mutableListOf<ScannedBox>()`);

code = code.replace(
`        val extractPercentAboveOrNearWithBox: (Int) -> Float? = { index ->
            var res: Float? = null
            for (i in maxOf(0, index - 2)..index) {
                val p = extractPercentInLine(lines[i])
                if (p != null) {
                    res = p
                    lineBoxes[i]?.let { matchedBoxes.add(it) }
                    if (i != index) lineBoxes[index]?.let { matchedBoxes.add(it) }
                    break
                }
            }
            res
        }

        val extractPercentInLineWithBox: (String, Int, Int) -> Float? = { line, i, matchIndex ->
            val res = extractPercentInLine(line, matchIndex)
            if (res != null) {
                lineBoxes[i]?.let { matchedBoxes.add(it) }
            }
            res
        }`,
`        val extractPercentAboveOrNearWithBox: (Int, String) -> Float? = { index, label ->
            var res: Float? = null
            for (i in maxOf(0, index - 2)..index) {
                val p = extractPercentInLine(lines[i])
                if (p != null) {
                    res = p
                    lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "$label: \${p.toInt()}%")) }
                    if (i != index) lineBoxes[index]?.let { matchedBoxes.add(ScannedBox(it, label)) }
                    break
                }
            }
            res
        }

        val extractPercentInLineWithBox: (String, Int, Int, String) -> Float? = { line, i, matchIndex, label ->
            val res = extractPercentInLine(line, matchIndex)
            if (res != null) {
                lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "$label: \${res.toInt()}%")) }
            }
            res
        }`
);

code = code.replace(
`                    if (nickname.isEmpty()) {
                        nickname = text.split(" ")[0] // take first part before any other badges
                        lineBoxes[i]?.let { matchedBoxes.add(it) }
                    }`,
`                    if (nickname.isEmpty()) {
                        nickname = text.split(" ")[0] // take first part before any other badges
                        lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, nickname)) }
                    }`);

code = code.replace(
`            // Extract percentages. To be safe, we also extract percentage based on surrounding labels
            if (line.contains("VPIP", ignoreCase = true)) vpip = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("PFR", ignoreCase = true)) pfr = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("3-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) bet3 = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("Fold to 3-Bet", ignoreCase = true)) fold3Bet = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("C-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) cBet = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("Fold to C-Bet", ignoreCase = true)) foldCBet = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("Steal", ignoreCase = true)) steal = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            if (line.contains("Check/Raise", ignoreCase = true)) checkRaise = extractPercentAboveOrNearWithBox(i) ?: extractPercentInLineWithBox(line, i, 0)
            
            // WTSD and WSD are sometimes on the same line "34% WTSD WSD 41%"
            if (line.contains("WTSD", ignoreCase = true)) {
                wtsd = extractPercentInLineWithBox(line, i, 0) ?: extractPercentAboveOrNearWithBox(i)
            }
            if (line.contains("WSD", ignoreCase = true) && !line.contains("WTSD", ignoreCase = true)) {
                wsd = extractPercentInLineWithBox(line, i, 0) ?: extractPercentAboveOrNearWithBox(i)
            }`,
`            // Extract percentages. To be safe, we also extract percentage based on surrounding labels
            if (line.contains("VPIP", ignoreCase = true)) vpip = extractPercentAboveOrNearWithBox(i, "VPIP") ?: extractPercentInLineWithBox(line, i, 0, "VPIP")
            if (line.contains("PFR", ignoreCase = true)) pfr = extractPercentAboveOrNearWithBox(i, "PFR") ?: extractPercentInLineWithBox(line, i, 0, "PFR")
            if (line.contains("3-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) bet3 = extractPercentAboveOrNearWithBox(i, "3Bet") ?: extractPercentInLineWithBox(line, i, 0, "3Bet")
            if (line.contains("Fold to 3-Bet", ignoreCase = true)) fold3Bet = extractPercentAboveOrNearWithBox(i, "F3B") ?: extractPercentInLineWithBox(line, i, 0, "F3B")
            if (line.contains("C-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) cBet = extractPercentAboveOrNearWithBox(i, "CBet") ?: extractPercentInLineWithBox(line, i, 0, "CBet")
            if (line.contains("Fold to C-Bet", ignoreCase = true)) foldCBet = extractPercentAboveOrNearWithBox(i, "FCBet") ?: extractPercentInLineWithBox(line, i, 0, "FCBet")
            if (line.contains("Steal", ignoreCase = true)) steal = extractPercentAboveOrNearWithBox(i, "Steal") ?: extractPercentInLineWithBox(line, i, 0, "Steal")
            if (line.contains("Check/Raise", ignoreCase = true)) checkRaise = extractPercentAboveOrNearWithBox(i, "C/R") ?: extractPercentInLineWithBox(line, i, 0, "C/R")
            
            // WTSD and WSD are sometimes on the same line "34% WTSD WSD 41%"
            if (line.contains("WTSD", ignoreCase = true)) {
                wtsd = extractPercentInLineWithBox(line, i, 0, "WTSD") ?: extractPercentAboveOrNearWithBox(i, "WTSD")
            }
            if (line.contains("WSD", ignoreCase = true) && !line.contains("WTSD", ignoreCase = true)) {
                wsd = extractPercentInLineWithBox(line, i, 0, "WSD") ?: extractPercentAboveOrNearWithBox(i, "WSD")
            }`);

code = code.replace(
`            if (line.contains("WTSD") && line.contains("WSD")) {
                val matches = Regex("(\\\\d+)[%]?").findAll(line).toList()
                if (matches.size >= 2) {
                    wtsd = matches[0].groupValues[1].toFloatOrNull()
                    wsd = matches[1].groupValues[1].toFloatOrNull()
                    lineBoxes[i]?.let { matchedBoxes.add(it) }
                }
            }`,
`            if (line.contains("WTSD") && line.contains("WSD")) {
                val matches = Regex("(\\\\d+)[%]?").findAll(line).toList()
                if (matches.size >= 2) {
                    wtsd = matches[0].groupValues[1].toFloatOrNull()
                    wsd = matches[1].groupValues[1].toFloatOrNull()
                    lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "WTSD/WSD: \${wtsd?.toInt()}% / \${wsd?.toInt()}%")) }
                }
            }`);

code = code.replace(
`            profileBoundingBoxes = matchedBoxes.distinct()`,
`            profileBoundingBoxes = matchedBoxes.distinctBy { it.rect }`
);

fs.writeFileSync('app/src/main/java/com/example/ProfileScanner.kt', code);
