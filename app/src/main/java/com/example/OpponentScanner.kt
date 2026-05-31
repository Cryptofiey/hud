package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

enum class PlayerAction {
    NONE, FOLD, CALL, RAISE, CHECK, ALL_IN, SIT_OUT
}

object OpponentScanner {

    private fun isValidPlayerName(name: String): Boolean {
        val upper = name.trim().uppercase()
        if (upper.isEmpty()) return false
        if (upper == "UNKNOWN") return false
        
        // Skip action keywords
        val actions = setOf(
            "FOLD", "CALL", "RAISE", "CHECK", "ALL-IN", "ALL IN", "BET", "POT", 
            "DEALER", "PASS", "SIT OUT", "SIT-OUT", "SITOUT", "CHOICE", "CHIPS"
        )
        if (upper in actions) return false
        
        // Skip clocks (e.g. "3:03") and paths / strings with special chars like : or /
        if (name.contains(":") || name.contains("/")) return false
        
        // Skip purely numeric names (since those might be bet sizes or blinds)
        if (upper.all { it.isDigit() || it == ',' || it == '.' || it == '$' || it == 'K' || it == 'M' }) return false
        
        return true
    }

    fun scan(result: Text, cleanBitmap: Bitmap): List<OpponentState> {
        val opponents = mutableListOf<OpponentState>()
        val blocks = result.textBlocks

        // 1. Find all possible chips/stack texts to locate opponent frames.
        // A player frame generally has a name and a stack. Stack is numbers + commas.
        val chipBlocks = blocks.filter { block ->
            val text = block.text.replace(",", "").trim()
            text.all { it.isDigit() } && text.isNotEmpty() && text.length > 2
        }

        val height = cleanBitmap.height
        val width = cleanBitmap.width
        
        var oppId = 1
        for (chipBlock in chipBlocks) {
            val chipBox = chipBlock.boundingBox ?: continue
            
            // Ignore the user's own frame at the bottom center of the screen
            if (chipBox.centerY() > height * 0.70f && chipBox.centerX() > width * 0.25f && chipBox.centerX() < width * 0.75f) {
                continue
            }
            val chipText = chipBlock.text

            // Ignore likely pot sizes (usually near the center of the screen)
            // But let's keep it simple for now, as we verify by checking name above.

            var nameBox: Rect? = null
            var nameText = "Unknown"
            
            for (block in blocks) {
                if (block == chipBlock) continue
                val box = block.boundingBox ?: continue
                
                // Checking if the block is visually above the chipBox
                val isAbove = box.bottom <= chipBox.top + 20 && box.bottom > chipBox.top - 100
                val isAlignedHorizontally = Math.abs(box.centerX() - chipBox.centerX()) < 100
                
                if (isAbove && isAlignedHorizontally && isValidPlayerName(block.text)) {
                    nameBox = box
                    nameText = block.text.trim()
                    break
                }
            }
            
            // If we didn't find a name, it might be the pot. Skip.
            if (nameBox == null) continue

            // 3. Action (Fold, Call, Raise, etc.) is slightly above Name/Chips or overlapping avatar.
            val referenceTop = nameBox.top
            val actionRegion = Rect(
                chipBox.left - 50,
                referenceTop - 150,
                chipBox.right + 50,
                referenceTop
            )

            var detectedAction = PlayerAction.NONE
            
            // Check text blocks inside actionRegion
            for (block in blocks) {
                val box = block.boundingBox ?: continue
                if (actionRegion.contains(box.centerX(), box.centerY())) {
                    val txt = block.text.uppercase(java.util.Locale.US)
                    if (txt.contains("FOLD")) detectedAction = PlayerAction.FOLD
                    else if (txt.contains("CALL")) detectedAction = PlayerAction.CALL
                    else if (txt.contains("RAISE")) detectedAction = PlayerAction.RAISE
                    else if (txt.contains("CHECK")) detectedAction = PlayerAction.CHECK
                    else if (txt.contains("ALL-IN") || txt.contains("ALL IN")) detectedAction = PlayerAction.ALL_IN
                    
                    if (detectedAction != PlayerAction.NONE) break
                }
            }
            
            // Fallback: Check pixel colors in the actionRegion.
            if (detectedAction == PlayerAction.NONE) {
                var greenPixels = 0
                var slatePixels = 0
                
                val startX = maxOf(0, actionRegion.left)
                val endX = minOf(cleanBitmap.width - 1, actionRegion.right)
                val startY = maxOf(0, actionRegion.top)
                val endY = minOf(cleanBitmap.height - 1, actionRegion.bottom)
                
                if (endX > startX && endY > startY) {
                   for (x in startX..endX step 5) {
                       for (y in startY..endY step 5) {
                           val px = cleanBitmap.getPixel(x, y)
                           val r = Color.red(px)
                           val g = Color.green(px)
                           val b = Color.blue(px)
                           
                           // Call (Green): ~ #1D995F (r < 80, g > 100, b < 120 and g > r)
                           if (g > 100 && r < 80 && b < 120 && g > r) greenPixels++
                           // Fold (Slate): ~ #416062
                           if (r in 40..80 && g in 70..110 && b in 70..110) slatePixels++
                       }
                   }
                   if (greenPixels > 20) detectedAction = PlayerAction.CALL
                   else if (slatePixels > 20) detectedAction = PlayerAction.FOLD
                }
            }
            
            val stackValue = chipText.replace(",", "").toIntOrNull() ?: 0

            opponents.add(OpponentState(
                id = oppId++,
                nickname = nameText,
                stackSize = stackValue,
                isActive = detectedAction != PlayerAction.FOLD && detectedAction != PlayerAction.SIT_OUT,
                isRandom = true,
                currentAction = detectedAction.name
            ))
        }

        return opponents
    }
}
