package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

enum class PlayerAction {
    NONE, FOLD, CALL, RAISE, CHECK, ALL_IN, SIT_OUT
}

object OpponentScanner {

    private data class TrackedAnchor(
        var boundingBox: Rect,
        var nickname: String,
        var consecutiveMisses: Int = 0
    )

    private val trackedAnchors = mutableListOf<TrackedAnchor>()

    private fun isValidPlayerName(name: String): Boolean {
        val upper = name.trim().uppercase()
        if (upper.isEmpty()) return false
        if (upper == "UNKNOWN") return false
        if (upper.length < 2 || upper.length > 20) return false
        
        // Ensure it doesn't have too many weird symbols
        val validCharCount = name.count { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
        if (validCharCount < name.length * 0.6) return false
        
        // Skip action keywords
        val actions = setOf(
            "FOLD", "CALL", "RAISE", "CHECK", "ALL-IN", "ALL IN", "BET", "POT", 
            "DEALER", "PASS", "SIT OUT", "SIT-OUT", "SITOUT", "CHOICE", "CHIPS",
            "FOLDED", "JOIN", "SIMILAR", "NLH"
        )
        if (upper in actions) return false
        
        for (action in actions) {
            if (upper.contains(action) && action.length >= 4) return false
        }
        
        // Skip clocks (e.g. "3:03") and paths / strings with special chars like : or /
        if (name.contains(":") || name.contains("/")) return false
        
        // Skip purely numeric names (since those might be bet sizes or blinds)
        if (upper.all { it.isDigit() || it == ',' || it == '.' || it == '$' || it == 'K' || it == 'M' }) return false
        
        return true
    }

    fun scan(result: Text, cleanBitmap: Bitmap, hudRects: List<Rect> = listOf()): List<OpponentState> {
        val candidates = mutableListOf<OpponentState>()
        
        // Filter out text blocks that intersect with HUD rectangles
        val blocks = result.textBlocks.filter { block ->
            val boundingBox = block.boundingBox
            if (boundingBox == null) true
            else !hudRects.any { android.graphics.Rect.intersects(it, boundingBox) || it.contains(boundingBox) }
        }

        val height = cleanBitmap.height
        val width = cleanBitmap.width
        
        // 1. Find potential player names in the horseshoe boundary
        val nameBlocks = blocks.filter { block ->
            val box = block.boundingBox ?: return@filter false
            val x = box.centerX().toFloat()
            val y = box.centerY().toFloat()
            
            // Widen the edge zones to 35% from left and right to ensure we capture all names.
            val inSearchZone = (x < width * 0.35f || x > width * 0.65f || y < height * 0.35f)
            inSearchZone && isValidPlayerName(block.text)
        }

        var oppId = 1
        for (nameBlock in nameBlocks) {
            val nameBox = nameBlock.boundingBox ?: continue
            val nameText = nameBlock.text.trim()
            
            // 2. Find stack size below the name
            var chipBox: Rect? = null
            var stackValue = 0
            
            for (block in blocks) {
                if (block == nameBlock) continue
                val box = block.boundingBox ?: continue
                
                // Stack is usually directly below the name
                val isBelow = box.top >= nameBox.bottom - 20 && box.top < nameBox.bottom + 120
                val isAlignedHorizontally = Math.abs(box.centerX() - nameBox.centerX()) < 150
                
                if (isBelow && isAlignedHorizontally) {
                    val rawText = block.text.replace(Regex("[^0-9]"), "").trim()
                    if (rawText.isNotEmpty()) {
                        stackValue = rawText.toIntOrNull() ?: 0
                        chipBox = box
                        break
                    }
                }
            }

            // Require a stack element to confirm this is a player profile
            if (chipBox == null) continue

            // Create unified bounding box for the player combining their name and stack
            val playerBox = Rect(nameBox)
            playerBox.union(chipBox)

            // 3. Find Action (near the name, often above or overlapping)
            val actionRegion = Rect(
                nameBox.left - 150,
                nameBox.top - 300,
                nameBox.right + 150,
                nameBox.bottom + 100
            )

            var detectedAction = PlayerAction.NONE
            
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
            
            // Fallback color check for fold/call
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
                           
                           // Strict check for Call/Check (Bright Green)
                           if (g > 120 && r < 80 && b < 100 && g > r + 30) greenPixels++
                           
                           // Strict check for Fold (Slate/Grayish-blue background on labels)
                           if (r in 30..90 && g in 60..120 && b in 70..130 && Math.abs(g - b) < 20) slatePixels++
                       }
                   }
                   if (greenPixels > 40) detectedAction = PlayerAction.CALL
                   else if (slatePixels > 60) detectedAction = PlayerAction.FOLD
                }
            }

            candidates.add(OpponentState(
                id = oppId++,
                nickname = nameText,
                stackSize = stackValue, // 0 if not found, still useful to track opponent
                isActive = detectedAction != PlayerAction.FOLD && detectedAction != PlayerAction.SIT_OUT,
                isRandom = true,
                currentAction = detectedAction.name,
                boundingBox = playerBox
            ))
        }

        val finalOpponents = mutableListOf<OpponentState>()
        val matchedCandidates = mutableSetOf<OpponentState>()

        synchronized(trackedAnchors) {
            val iterator = trackedAnchors.iterator()
            while (iterator.hasNext()) {
                val anchor = iterator.next()
                
                // Allow some movement
                val anchorRadius = 250.0
                
                val bestCandidate = candidates.firstOrNull { 
                    Rect.intersects(it.boundingBox!!, anchor.boundingBox) || 
                    Math.hypot((it.boundingBox!!.centerX() - anchor.boundingBox.centerX()).toDouble(), 
                               (it.boundingBox!!.centerY() - anchor.boundingBox.centerY()).toDouble()) < anchorRadius
                }

                if (bestCandidate != null) {
                    matchedCandidates.add(bestCandidate)
                    anchor.consecutiveMisses = 0
                    
                    // Slowly adapt bounding box
                    anchor.boundingBox.union(bestCandidate.boundingBox!!)
                    
                    // Allow name update if the new name is valid, but anchor provides stability
                    anchor.nickname = bestCandidate.nickname
                    
                    finalOpponents.add(bestCandidate.copy(nickname = anchor.nickname, boundingBox = anchor.boundingBox))
                } else {
                    anchor.consecutiveMisses++
                    if (anchor.consecutiveMisses > 4) {
                        iterator.remove() // Player left, or we missed them too many times
                    } else {
                        // Remember them for a few frames
                        finalOpponents.add(OpponentState(
                            id = 0,
                            nickname = anchor.nickname,
                            stackSize = 0,
                            isActive = true,
                            isRandom = true,
                            currentAction = "NONE",
                            boundingBox = anchor.boundingBox
                        ))
                    }
                }
            }

            for (candidate in candidates) {
                if (candidate !in matchedCandidates) {
                    val newAnchor = TrackedAnchor(candidate.boundingBox!!, candidate.nickname, 0)
                    trackedAnchors.add(newAnchor)
                    finalOpponents.add(candidate)
                }
            }
        }

        return finalOpponents.mapIndexed { i, opp -> opp.copy(id = i + 1) }
    }
}
