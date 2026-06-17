package com.example

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

object ProfileScanner {
    // Looks through the recognized text to find a profile screen.
    // We detect a profile if we see the words "Profile", "VPIP", "PFR", etc.
    fun scan(result: Text?, cleanBitmap: Bitmap, hudRects: List<android.graphics.Rect> = listOf()): PlayerStats? {
        if (result == null) return null
        val screenH = cleanBitmap.height
        val blocks = result.textBlocks.filter { block ->
            val b = block.boundingBox
            if (b == null) true
            else {
                // Ignore blocks in the top 12% of screen (usually table header) to avoid false profile detections
                if (b.top < screenH * 0.12) return@filter false
                !hudRects.any { android.graphics.Rect.intersects(it, b) || it.contains(b) }
            }
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
        
        // Ensure it looks like a profile screen - stricter check
        if (!hasVpip || !hasPfr) return null
        
        val extractPercentNearWithBox: (Int, String) -> Float? = { index, label ->
            var res: Float? = null
            var bestMatchI = -1
            val labelBox = lineBoxes[index]
            
            // 1. Spatial Search (Handles cases where MLKit groups columns into separate blocks)
            if (labelBox != null) {
                var bestDist = Float.MAX_VALUE
                for (i in lines.indices) {
                    val num = extractPercentInLine(lines[i])
                    val box = lineBoxes[i]
                    if (num != null && box != null) {
                        val dx = Math.abs(labelBox.centerX() - box.centerX()).toFloat()
                        val dy = Math.abs(labelBox.centerY() - box.centerY()).toFloat()
                        
                        // We use height as a consistent baseline unit because text width varies wildly
                        val baseUnit = labelBox.height().toFloat()
                        
                        // Look for a number that is vertically close and horizontally aligned
                        if (dx < baseUnit * 25f && dy < baseUnit * 6.5f) {
                            val dist = (dx * 0.5f) + (dy * 2f) // Penalize vertical distance more to keep aligned
                            if (dist < bestDist) {
                                bestDist = dist
                                bestMatchI = i
                                res = num
                            }
                        }
                    }
                }
            }
            
            // 2. Index Fallback (If spatial fails, look in adjacent text lines)
            if (res == null) {
                for (i in (index - 3)..(index + 3)) {
                    if (i < 0 || i >= lines.size) continue
                    val p = extractPercentInLine(lines[i])
                    if (p != null) {
                        res = p
                        bestMatchI = i
                        break
                    }
                }
            }
            
            if (res != null && bestMatchI != -1) {
                lineBoxes[bestMatchI]?.let { matchedBoxes.add(ScannedBox(it, lines[bestMatchI])) }
                if (bestMatchI != index) lineBoxes[index]?.let { matchedBoxes.add(ScannedBox(it, lines[index])) }
            }
            
            res
        }

        val extractPercentInLineWithBox: (String, Int, Int, String) -> Float? = { line, i, matchIndex, label ->
            val res = extractPercentInLine(line, matchIndex)
            if (res != null) {
                lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, lines[i])) }
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
            val normalizedLine = line.replace(" ", "").replace("-", "").replace("/", "").uppercase()
            
            // Extract percentages.
            if (normalizedLine.contains("VPIP")) vpip = extractPercentNearWithBox(i, "VPIP") ?: extractPercentInLineWithBox(line, i, 0, "VPIP")
            if (normalizedLine.contains("PFR")) pfr = extractPercentNearWithBox(i, "PFR") ?: extractPercentInLineWithBox(line, i, 0, "PFR")
            if (normalizedLine.contains("3BET") && !normalizedLine.contains("FOLD")) bet3 = extractPercentNearWithBox(i, "3Bet") ?: extractPercentInLineWithBox(line, i, 0, "3Bet")
            if (normalizedLine.contains("FOLDTO3BET") || normalizedLine.contains("F3B")) fold3Bet = extractPercentNearWithBox(i, "F3B") ?: extractPercentInLineWithBox(line, i, 0, "F3B")
            if (normalizedLine.contains("CBET") && !normalizedLine.contains("FOLD")) cBet = extractPercentNearWithBox(i, "CBet") ?: extractPercentInLineWithBox(line, i, 0, "CBet")
            if (normalizedLine.contains("FOLDTOCBET")) foldCBet = extractPercentNearWithBox(i, "FCBet") ?: extractPercentInLineWithBox(line, i, 0, "FCBet")
            if (normalizedLine.contains("STEAL")) steal = extractPercentNearWithBox(i, "Steal") ?: extractPercentInLineWithBox(line, i, 0, "Steal")
            if (normalizedLine.contains("CHECKRAISE") || normalizedLine.contains("C/R")) checkRaise = extractPercentNearWithBox(i, "C/R") ?: extractPercentInLineWithBox(line, i, 0, "C/R")
            
            // WTSD and WSD are sometimes on the same line "34% WTSD WSD 41%"
            if (normalizedLine.contains("WTSD")) {
                wtsd = extractPercentInLineWithBox(line, i, 0, "WTSD") ?: extractPercentNearWithBox(i, "WTSD")
            }
            if (normalizedLine.contains("WSD") && !normalizedLine.contains("WTSD")) {
                wsd = extractPercentInLineWithBox(line, i, 0, "WSD") ?: extractPercentNearWithBox(i, "WSD")
            }
            if (normalizedLine.contains("WTSD") && normalizedLine.contains("WSD")) {
                val matches = Regex("(\\d+)[%]?").findAll(line).toList()
                if (matches.size >= 2) {
                    wtsd = matches[0].groupValues[1].toFloatOrNull()
                    wsd = matches[1].groupValues[1].toFloatOrNull()
                    lineBoxes[i]?.let { matchedBoxes.add(ScannedBox(it, lines[i])) }
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
