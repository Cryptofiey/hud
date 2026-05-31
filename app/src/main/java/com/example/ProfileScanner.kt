package com.example

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

object ProfileScanner {
    // Looks through the recognized text to find a profile screen.
    // We detect a profile if we see the words "Profile", "VPIP", "PFR", etc.
    fun scan(result: Text, cleanBitmap: Bitmap): PlayerStats? {
        val textBlocks = result.textBlocks
        var hasProfile = false
        var hasVpip = false
        var hasPfr = false
        
        val lines = mutableListOf<String>()
        for (block in textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                lines.add(text)
                if (text.contains("Profile", ignoreCase = true)) hasProfile = true
                if (text.contains("VPIP", ignoreCase = true)) hasVpip = true
                if (text.contains("PFR", ignoreCase = true)) hasPfr = true
            }
        }
        
        // Ensure it looks like a profile screen
        if (!hasProfile || (!hasVpip && !hasPfr)) return null
        
        // Nickname is typically right below "Profile" or has a diamond symbol next to it.
        // It's tricky to parse. Let's look for "VPIP" and "PFR" and the percentages before them.
        // Profile text layout often contains: 
        // 60% VPIP
        // 25% PFR
        
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
        
        // The nickname is mostly at the top below "Profile"
        var nickname = ""
        
        var profileIdx = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.equals("Profile", ignoreCase = true)) {
                profileIdx = i
            }
            if (profileIdx != -1 && i > profileIdx && i < profileIdx + 5) {
                // Often the nickname is the next substantial word
                if (line.length > 2 && !line.contains("%") && !line.contains("Game") && nickname.isEmpty()) {
                    nickname = line
                }
            }
            
            // Extract percentages. To be safe, we also extract percentage based on surrounding labels
            if (line.contains("VPIP", ignoreCase = true)) vpip = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("PFR", ignoreCase = true)) pfr = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("3-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) bet3 = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("Fold to 3-Bet", ignoreCase = true)) fold3Bet = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("C-Bet", ignoreCase = true) && !line.contains("Fold", ignoreCase = true)) cBet = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("Fold to C-Bet", ignoreCase = true)) foldCBet = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("Steal", ignoreCase = true)) steal = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            if (line.contains("Check/Raise", ignoreCase = true)) checkRaise = extractPercentAboveOrNear(lines, i) ?: extractPercentInLine(line)
            
            // WTSD and WSD are sometimes on the same line "34% WTSD WSD 41%"
            if (line.contains("WTSD", ignoreCase = true)) {
                wtsd = extractPercentInLine(line, 0) ?: extractPercentAboveOrNear(lines, i)
            }
            if (line.contains("WSD", ignoreCase = true) && !line.contains("WTSD", ignoreCase = true)) {
                wsd = extractPercentInLine(line) ?: extractPercentAboveOrNear(lines, i)
            }
            if (line.contains("WTSD") && line.contains("WSD")) {
                val matches = Regex("(\\d+)[%]?").findAll(line).toList()
                if (matches.size >= 2) {
                    wtsd = matches[0].groupValues[1].toFloatOrNull()
                    wsd = matches[1].groupValues[1].toFloatOrNull()
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
            histWsd = wsd
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
