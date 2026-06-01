package com.example

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

object ProfileScanner {
    // Looks through the recognized text to find a profile screen.
    // We detect a profile if we see the words "Profile", "VPIP", "PFR", etc.
    fun scan(result: Text, cleanBitmap: Bitmap, hudRects: List<android.graphics.Rect> = listOf()): PlayerStats? {
        val blocks = result.textBlocks.filter { block ->
            val boundingBox = block.boundingBox
            if (boundingBox == null) true
            else !hudRects.any { android.graphics.Rect.intersects(it, boundingBox) || it.contains(boundingBox) }
        }
        
        var hasProfile = false
        var hasVpip = false
        var hasPfr = false
        
        val lines = mutableListOf<String>()
        val lineBoxes = mutableListOf<android.graphics.Rect?>()
        val matchedBoxes = mutableListOf<ScannedBox>()
        
        for (block in blocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                lines.add(text)
                lineBoxes.add(line.boundingBox)
                
                if (text.contains("Profile", ignoreCase = true)) hasProfile = true
                if (text.contains("VPIP", ignoreCase = true)) hasVpip = true
                if (text.contains("PFR", ignoreCase = true)) hasPfr = true
            }
        }
        
        // Ensure it looks like a profile screen
        if (!hasVpip || !hasPfr) return null
        
        val extractPercentAboveOrNearWithBox: (Int, String) -> Float? = { index, label ->
            var res: Float? = null
            for (i in maxOf(0, index - 2)..index) {
                val p = extractPercentInLine(lines[i])
                if (p != null) {
                    res = p
                    lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "$label: ${p.toInt()}%")) }
                    if (i != index) lineBoxes[index]?.let { matchedBoxes.add(ScannedBox(it, label)) }
                    break
                }
            }
            res
        }

        val extractPercentInLineWithBox: (String, Int, Int, String) -> Float? = { line, i, matchIndex, label ->
            val res = extractPercentInLine(line, matchIndex)
            if (res != null) {
                lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "$label: ${res.toInt()}%")) }
            }
            res
        }
        
        // Let's create a map to hold the extracted values
        var vpip: Float? = null
        var pfr: Float? = null
        var bet3: Float? = null
        var fold3Bet: Float? = null
        var cBet: Float? = null
        var foldCBet: Float? = null
        var steal: Float? = null
        var checkRaise: Float? = null
        var wtsd: Float? = null
        var wsd: Float? = null
        
        // The nickname is usually the first or second word on the screen above "Game Stats" or the stack size
        var nickname = ""
        var gameStatsIdx = -1
        
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains("Game Stats", ignoreCase = true)) {
                gameStatsIdx = i
            }
        }
        
        // Try to find the nickname above "Game Stats"
        if (gameStatsIdx != -1) {
            for (i in maxOf(0, gameStatsIdx - 5) until gameStatsIdx) {
                val text = lines[i]
                // usually nickname is a single word or accompanied by device icon, avoid numbers (like stack)
                if (text.length >= 3 && !text.contains("%") && !text.contains("BB", ignoreCase=true) && !text.any { it.isDigit() && text.length < 4 }) {
                    if (nickname.isEmpty()) {
                        nickname = text.split(" ")[0] // take first part before any other badges
                        lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, nickname)) }
                    }
                }
            }
        }
        
        for (i in lines.indices) {
            val line = lines[i]
            
            // Extract percentages. To be safe, we also extract percentage based on surrounding labels
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
            }
            if (line.contains("WTSD") && line.contains("WSD")) {
                val matches = Regex("(\\d+)[%]?").findAll(line).toList()
                if (matches.size >= 2) {
                    wtsd = matches[0].groupValues[1].toFloatOrNull()
                    wsd = matches[1].groupValues[1].toFloatOrNull()
                    lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, "WTSD/WSD: ${wtsd?.toInt()}% / ${wsd?.toInt()}%")) }
                }
            }
        }
        
        if (nickname.isEmpty()) nickname = "Unknown_Profile"
        
        return PlayerStats(
            nickname = nickname,
            histVpip = vpip,
            histPfr = pfr,
            hist3Bet = bet3,
            histFoldTo3Bet = fold3Bet,
            histCBet = cBet,
            histFoldToCBet = foldCBet,
            histSteal = steal,
            histCheckRaise = checkRaise,
            histWtsd = wtsd,
            histWsd = wsd,
            profileBoundingBoxes = matchedBoxes.distinctBy { it.rect }
        )
    }
    
    private fun extractPercentAboveOrNear(lines: List<String>, index: Int): Float? {
        // Look 1-2 lines above
        for (i in maxOf(0, index - 2)..index) {
            val p = extractPercentInLine(lines[i])
            if (p != null) return p
        }
        return null
    }

    private fun extractPercentInLine(line: String, matchIndex: Int = 0): Float? {
        val matches = Regex("(\\d+)[%]?").findAll(line).toList()
        if (matches.isNotEmpty() && matchIndex < matches.size) {
            return matches[matchIndex].groupValues[1].toFloatOrNull()
        }
        return null
    }
}
