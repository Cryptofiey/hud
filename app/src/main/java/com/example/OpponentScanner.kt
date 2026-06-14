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
        var consecutiveMisses: Int = 0,
        var consecutiveHits: Int = 1,
        var isMature: Boolean = false,
        var pendingNickname: String = "",
        var pendingNicknameCount: Int = 0,
        var lastKnownStackSize: Float = 100f,
        var lastKnownBetSize: Float = 0f,
        var lastKnownActive: Boolean = true,
        var lastKnownVpip: Float? = null
    )

    private val trackedAnchors = mutableListOf<TrackedAnchor>()

    fun mergeRightsideDigits(box: Rect, baseText: String, lines: List<Text.Line>): String {
        var text = baseText
        val h = box.height()
        if (h <= 0) return text
        var searchRight = box.right
        
        // Find any line to the right of 'box' that is vertically aligned
        while (true) {
            val nextLine = lines.firstOrNull { l ->
                val b = l.boundingBox ?: return@firstOrNull false
                if (l.text.trim() == baseText.trim()) return@firstOrNull false
                val isToRight = b.left >= searchRight - (h * 0.2f) && b.left <= searchRight + (h * 4.0f)
                val isVertAligned = Math.abs(b.centerY() - box.centerY()) < (h * 0.7f)
                isToRight && isVertAligned
            }
            
            if (nextLine != null) {
                val nextText = nextLine.text.trim()
                val cleanNext = nextText.replace(Regex("[^0-9.,KMBkmb]"), "")
                if (cleanNext.isNotEmpty()) {
                    text += " " + nextText
                    searchRight = nextLine.boundingBox!!.right
                } else {
                    break
                }
            } else {
                break
            }
        }
        return text
    }

    private fun isSeatEmptyMarkerPresent(anchorBox: Rect, linesList: List<Text.Line>, width: Int): Boolean {
        for (line in linesList) {
            val lineBox = line.boundingBox ?: continue
            val dist = Math.hypot((lineBox.centerX() - anchorBox.centerX()).toDouble(), (lineBox.centerY() - anchorBox.centerY()).toDouble())
            if (dist < width * 0.16) {
                val text = line.text.trim().uppercase()
                if (text == "+" || text.contains("JOIN") || text.contains("TAKE SEAT") || text.contains("SIT DOWN") || text.contains("SITOUT") || text.contains("SIT OUT")) {
                    return true
                }
            }
        }
        return false
    }

    private fun isValidPlayerName(name: String): Boolean {
        val upper = name.trim().uppercase()
        if (upper.isEmpty()) return false
        if (upper == "UNKNOWN") return false
        if (upper.length < 2 || upper.length > 20) return false
        if (name.trim().split(Regex("\\s+")).size > 2) return false // Names usually are 1-2 words max
        
        // Ensure it doesn't have too many weird symbols (allow periods for truncated names like "Name...")
        val validCharCount = name.count { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' || it == '.' }
        if (validCharCount < name.length * 0.6) return false
        
        // Skip action keywords and internal UI text
        val actions = setOf(
            "FOLD", "CALL", "RAISE", "CHECK", "ALL-IN", "ALL IN", "BET", "POT", 
            "ФОЛД", "КОЛЛ", "РЕЙЗ", "ЧЕК", "ОЛЛ-ИН", "БЕТ", "ПОТ", "ПАС", "ДИЛЕР",
            "DEALER", "PASS", "SIT OUT", "SIT-OUT", "SITOUT", "CHOICE", "CHIPS",
            "FOLDED", "JOIN", "SIMILAR", "NLH", "VPIP", "PFR", "WTSD", "WSD",
            "BALANCE", "PROFILE", "HUD", "MONITOR", "MONIT", "RUNS", "GAME", "INTERPRETATION",
            "EQUITY", "OPPONENT", "LIVE", "STATS", "TELEMETRY", "ADVISOR", "STRATEGY",
            "LEVEL", "UP", "FREEROLL", "LATE", "REG", "RANK", "PAID", "TOURNEY", "BUY-IN"
        )
        if (upper in actions) return false
        
        // Exclude specific UI titles that OCR might catch
        if (upper.contains("GAME INTERPRETATION") || 
            upper.contains("VISUALIZATION OF") ||
            upper.contains("CALCULATIONS") ||
            upper.contains("POKER EQUITY") ||
            upper.contains("HUD MONITOR") ||
            upper.contains("OPPONENT PROFILE") ||
            upper.contains("LIVE DATA TELEMETRY") ||
            upper.contains("OZZIE 128") || upper.contains("CAPA25")) return false
        
        for (action in actions) {
            if (upper.contains(action) && action.length >= 4) return false
        }
        
        // Skip clocks (e.g. "3:03") and paths / strings with special chars like : or /
        if (name.contains(":") || name.contains("/")) return false
        
        // Skip purely numeric names (since those might be bet sizes or blinds)
        if (upper.all { it.isDigit() || it == ',' || it == '.' || it == '$' || it == 'K' || it == 'M' }) return false
        
        return true
    }

    fun scan(result: Text, cleanBitmap: Bitmap, hudRects: List<Rect> = listOf(), commRect: Rect? = null, holeRect: Rect? = null): List<OpponentState> {
        if (PokerHudSharedState.appScreenContext.value != AppScreenState.COINPOKER_TABLE) {
            return emptyList()
        }
        val candidates = mutableListOf<OpponentState>()
        
        // Filter out text that intersects with HUD rectangles, break down into lines to prevent grouping issues
        val linesList = mutableListOf<Text.Line>()
        for (block in result.textBlocks) {
            val boundingBox = block.boundingBox ?: continue
            if (hudRects.any { android.graphics.Rect.intersects(it, boundingBox) || it.contains(boundingBox) }) continue
            if (commRect != null && (android.graphics.Rect.intersects(commRect, boundingBox) || commRect.contains(boundingBox))) continue
            if (holeRect != null && (android.graphics.Rect.intersects(holeRect, boundingBox) || holeRect.contains(boundingBox))) continue
            
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                if (!hudRects.any { android.graphics.Rect.intersects(it, lineBox) || it.contains(lineBox) } &&
                    !(commRect != null && android.graphics.Rect.intersects(commRect, lineBox)) &&
                    !(holeRect != null && android.graphics.Rect.intersects(holeRect, lineBox))) {
                    linesList.add(line)
                }
            }
        }

        val height = cleanBitmap.height
        val width = cleanBitmap.width
        
        // 1. Find potential player names in the horseshoe boundary
        val nameLines = linesList.filter { line ->
            val box = line.boundingBox ?: return@filter false
            val x = box.centerX().toFloat()
            val y = box.centerY().toFloat()
            
            // Define exclusion zones based on the new layout
            val inTopHeader = y < height * 0.09f
            // Community cards
            val inCommunityCards = x > width * 0.10f && x < width * 0.90f && y > height * 0.40f && y < height * 0.54f
            // Hero pocket cards region near the bottom center avatar, slightly off-center to the right
            val inHeroCards = x > width * 0.44f && x < width * 0.79f && y > height * 0.69f && y < height * 0.83f
            
            // Check if it's explicitly inside the known rects (redundant but safe)
            val inKnownComm = commRect != null && commRect.contains(x.toInt(), y.toInt())
            val inKnownHole = holeRect != null && holeRect.contains(x.toInt(), y.toInt())
            
            val inSearchZone = !(inTopHeader || inCommunityCards || inHeroCards || inKnownComm || inKnownHole)
            inSearchZone && isValidPlayerName(line.text)
        }

        var oppId = 1
        for (nameLine in nameLines) {
            val nameBox = nameLine.boundingBox ?: continue
            val nameText = nameLine.text.trim()
            
            // 2. Find stack size below the name
            var chipBox: Rect? = null
            var stackValue = 0f
            
            for (line in linesList) {
                if (line === nameLine) continue
                val box = line.boundingBox ?: continue
                
                // Stack is usually directly below the name or within its horizontal region.
                val isBelow = box.top >= nameBox.bottom - (height * 0.02f) && box.top < nameBox.bottom + (height * 0.12f)
                val isAlignedHorizontally = Math.abs(box.centerX() - nameBox.centerX()) < (width * 0.18f)
                
                if (isBelow && isAlignedHorizontally) {
                    val mergedText = mergeRightsideDigits(box, line.text, linesList)
                    val textTrimmed = mergedText.trim()
                    // Reject if it contains generic letters (to avoid parsing chat messages as stack sizes)
                    val genericLetterCount = textTrimmed.count { it.isLetter() && it.uppercaseChar() !in listOf('K', 'M', 'B') }
                    if (genericLetterCount > 4) continue // More lenient to allow things like "14.2 BBs" or similar anomalies

                    val s = textTrimmed.uppercase().replace(",", ".")
                    val multiplier = when {
                        s.contains("K") -> 1000f
                        s.contains("M") -> 1000000f
                        else -> 1f
                    }
                    val rawText = s.replace(Regex("[^0-9.]"), "")
                    if (rawText.isNotEmpty() && rawText.count { it == '.' } <= 1) {
                        stackValue = (rawText.toFloatOrNull() ?: 0f) * multiplier
                        chipBox = box
                        break
                    }
                }
            }

            // 3. Find Action (near the name, often above or overlapping)
            val actionRegion = Rect(
                (nameBox.left - width * 0.12f).toInt(),
                (nameBox.top - height * 0.15f).toInt(),
                (nameBox.right + width * 0.12f).toInt(),
                (nameBox.bottom + height * 0.05f).toInt()
            )

            var detectedAction = PlayerAction.NONE
            
            for (line in linesList) {
                val box = line.boundingBox ?: continue
                if (box.top > height * 0.85f) continue // skip hero action buttons
                if (actionRegion.contains(box.centerX(), box.centerY())) {
                    val txt = line.text.uppercase(java.util.Locale.US)
                    if (txt.contains("FOLD") || txt.contains("ФОЛД") || txt.contains("ПАС") || txt.contains("СБРОС")) detectedAction = PlayerAction.FOLD
                    else if (txt.contains("CALL") || txt.contains("КОЛЛ")) detectedAction = PlayerAction.CALL
                    else if (txt.contains("RAISE") || txt.contains("РЕЙЗ")) detectedAction = PlayerAction.RAISE
                    else if (txt.contains("CHECK") || txt.contains("ЧЕК")) detectedAction = PlayerAction.CHECK
                    else if (txt.contains("ALL-IN") || txt.contains("ALL IN") || txt.contains("ОЛЛ-ИН") || txt.contains("ОЛЛ ИН")) detectedAction = PlayerAction.ALL_IN
                    else if (txt.contains("SIT OUT") || txt.contains("SITTING OUT") || txt.contains("ОТСУТСТВУЕТ") || txt.contains("ВНЕ ИГРЫ") || txt.contains("AWAY")) detectedAction = PlayerAction.SIT_OUT
                    
                    if (detectedAction != PlayerAction.NONE) break
                }
            }
            
            // Require a stack element OR a valid action to confirm this is a player profile
            // This prevents players who went all-in (and have 0/no stack text) from disappearing.
            if (chipBox == null && detectedAction == PlayerAction.NONE) {
                // If we also want to keep players with no chips and no action (e.g. sitting out), 
                // we'd need to match against existing anchors, but we'll do that below by looking up tracked anchors.
                val matchesExisting = trackedAnchors.any { 
                    Math.hypot((nameBox.centerX() - it.boundingBox.centerX()).toDouble(), 
                               (nameBox.centerY() - it.boundingBox.centerY()).toDouble()) < (width * 0.15)
                }
                if (!matchesExisting) continue
            }

            // Create unified bounding box for the player combining their name and stack
            val playerBox = Rect(nameBox)
            if (chipBox != null) {
                playerBox.union(chipBox)
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

        val uniqueCandidates = candidates.distinctBy { it.nickname }

        val finalOpponents = mutableListOf<OpponentState>()
        val matchedCandidates = mutableSetOf<OpponentState>()

        synchronized(trackedAnchors) {
            val iterator = trackedAnchors.iterator()
            while (iterator.hasNext()) {
                val anchor = iterator.next()
                
                // Allow some movement
                val anchorRadius = width * 0.18
                
                val bestCandidate = uniqueCandidates
                    .filter { 
                        android.graphics.Rect.intersects(it.boundingBox!!, anchor.boundingBox) || 
                        Math.hypot((it.boundingBox.centerX() - anchor.boundingBox.centerX()).toDouble(), 
                                   (it.boundingBox.centerY() - anchor.boundingBox.centerY()).toDouble()) < anchorRadius
                    }
                    .minByOrNull { 
                        Math.hypot((it.boundingBox!!.centerX() - anchor.boundingBox.centerX()).toDouble(), 
                                   (it.boundingBox.centerY() - anchor.boundingBox.centerY()).toDouble())
                    }

                if (bestCandidate != null) {
                    matchedCandidates.add(bestCandidate)
                    anchor.consecutiveMisses = 0
                    anchor.consecutiveHits++
                    
                    // Update bounding box strictly to the new candidate to prevent endless growth
                    anchor.boundingBox = android.graphics.Rect(bestCandidate.boundingBox!!)
                    
                    // Allow name update if the new name is valid, but require stability
                    if (bestCandidate.nickname != anchor.nickname) {
                        if (bestCandidate.nickname == anchor.pendingNickname) {
                            anchor.pendingNicknameCount++
                            if (anchor.pendingNicknameCount >= 3) {
                                anchor.nickname = bestCandidate.nickname
                                anchor.pendingNicknameCount = 0
                            }
                        } else {
                            anchor.pendingNickname = bestCandidate.nickname
                            anchor.pendingNicknameCount = 1
                        }
                    } else {
                        anchor.pendingNicknameCount = 0
                    }
                    
                    // Save last known values
                    if (bestCandidate.stackSize > 0f) {
                        anchor.lastKnownStackSize = bestCandidate.stackSize
                    }
                    anchor.lastKnownBetSize = bestCandidate.betSize
                    anchor.lastKnownActive = bestCandidate.isActive
                    
                    if (anchor.consecutiveHits >= 3) {
                        anchor.isMature = true
                        val usedStackSize = if (bestCandidate.stackSize > 0f) bestCandidate.stackSize else anchor.lastKnownStackSize
                        finalOpponents.add(bestCandidate.copy(
                            nickname = anchor.nickname, 
                            boundingBox = anchor.boundingBox,
                            stackSize = usedStackSize
                        ))
                    }
                } else {
                    anchor.consecutiveMisses++
                    
                    // Proactive check: if we see an empty seat marker at this player location, remove instantly
                    val isEmptyMarker = isSeatEmptyMarkerPresent(anchor.boundingBox, linesList, width)
                    if (isEmptyMarker) {
                        anchor.consecutiveMisses = 10 // force clean up immediately!
                    }
                    
                    if (anchor.consecutiveMisses > 8) { // Clean up empty positions faster (reduced from 25 to 8)
                        iterator.remove() // Player left, or we missed them too many times
                    } else if (anchor.isMature) {
                        // Remember them for a few frames only if they were confirmed
                        finalOpponents.add(OpponentState(
                            id = 0,
                            nickname = anchor.nickname,
                            stackSize = anchor.lastKnownStackSize,
                            isActive = anchor.lastKnownActive,
                            isRandom = true,
                            currentAction = "NONE",
                            boundingBox = anchor.boundingBox,
                            betSize = anchor.lastKnownBetSize
                        ))
                    }
                }
            }

            for (candidate in uniqueCandidates) {
                if (candidate !in matchedCandidates) {
                    val newAnchor = TrackedAnchor(
                        candidate.boundingBox!!, 
                        candidate.nickname, 
                        0, 1, false,
                        lastKnownStackSize = if (candidate.stackSize > 0f) candidate.stackSize else 100f,
                        lastKnownBetSize = candidate.betSize,
                        lastKnownActive = candidate.isActive
                    )
                    trackedAnchors.add(newAnchor)
                }
            }
        }

        // Deduplicate opponents by nickname to prevent OCR feedback loops from UI renders
        val dedupedOpponents = finalOpponents.distinctBy { it.nickname }

        // Find bet sizes mapped to closest players
        val screenCenterX = cleanBitmap.width / 2f
        val screenCenterY = cleanBitmap.height / 2f
        
        val opponentsWithBets = dedupedOpponents.map { opp ->
            var closestBet = 0f
            var minDistSq = Float.MAX_VALUE
            
            for (line in linesList) {
                val lineBox = line.boundingBox ?: continue
                val textUpper = line.text.uppercase()
                
                if (textUpper.contains("POT") || textUpper.contains("ПОТ") || !textUpper.any { it.isDigit() }) continue
                
                // Reject if it contains too many letters (to avoid parsing "LEVEL 6" or chat bubbles as bets)
                val genericLetterCount = textUpper.count { it.isLetter() && it !in listOf('K', 'M', 'B', 'S', 'C') }
                if (genericLetterCount > 2 && !textUpper.contains("BB") && !textUpper.contains("ББ")) continue
                
                val s = textUpper.replace(",", ".")
                val multiplier = when {
                    s.contains("K") -> 1000f
                    s.contains("M") -> 1000000f
                    else -> 1f
                }
                val numStr = s.replace(Regex("[^0-9.]"), "")
                if (numStr.isEmpty() || numStr.count { it == '.' } > 1) continue
                val betVal = (numStr.toFloatOrNull() ?: continue) * multiplier
                if (betVal <= 0f) continue
                
                val intersectsPlayer = uniqueCandidates.any { android.graphics.Rect.intersects(it.boundingBox!!, lineBox) }
                if (intersectsPlayer) continue // This is probably their stack
                
                // Exclude central pot amounts
                val distToCenterSq = Math.pow((lineBox.centerX() - screenCenterX).toDouble(), 2.0) + Math.pow((lineBox.centerY() - screenCenterY).toDouble(), 2.0)
                if (distToCenterSq < Math.pow(cleanBitmap.width * 0.15, 2.0)) continue
                
                val oppBox = opp.boundingBox ?: continue
                
                // Directional check: bets are placed in front of the player (towards the center)
                val vecPx = lineBox.centerX() - oppBox.centerX()
                val vecPy = lineBox.centerY() - oppBox.centerY()
                val vecCx = screenCenterX - oppBox.centerX()
                val vecCy = screenCenterY - oppBox.centerY()
                
                // Dot product > 0 means the angle is < 90 degrees (meaning it's roughly towards the center)
                val dotProduct = vecPx * vecCx + vecPy * vecCy
                if (dotProduct <= 0) continue
                
                val distSq = Math.pow(vecPx.toDouble(), 2.0) + Math.pow(vecPy.toDouble(), 2.0)
                
                if (distSq < minDistSq && distSq < Math.pow(cleanBitmap.width * 0.18, 2.0)) {
                    minDistSq = distSq.toFloat()
                    closestBet = betVal
                }
            }
            opp.copy(betSize = closestBet)
        }

        // Find session VPIP mapped to players
        val opponentsWithVpip = opponentsWithBets.map { opp ->
            var detectedVpip: Float? = null
            var vpipBox: Rect? = null
            
            val oppBox = opp.boundingBox
            if (oppBox != null) {
                for (line in linesList) {
                    val lineBox = line.boundingBox ?: continue
                    
                    // The VPIP window "91" is located to the right side of the avatar, above the name/stack plate.
                    // oppBox is the union of name and stack text. The avatar is directly above oppBox.
                    // The name box is often wider than the avatar.
                    // So VPIP box is vertically ABOVE the oppBox (between top - 2.5*height and top).
                    // And horizontally on the right side of the avatar (around right half of oppBox or slightly outside).
                    
                    // Robust y-bounds based on screen height to handle cases where the stack box wasn't parsed (which shrinks oppBox)
                    // and lenient horizontal checks to support both active (highlighted) and folded (non-highlighted) players.
                    val minTopY = oppBox.top - (height * 0.12f)
                    val maxBottomY = oppBox.top + (height * 0.04f)
                    val isAbove = lineBox.top >= minTopY && lineBox.bottom <= maxBottomY
                    
                    val isRightSide = lineBox.centerX() > oppBox.centerX() - (oppBox.width() * 0.35f) && 
                                      lineBox.left <= oppBox.right + (oppBox.width() * 0.6f)
                    
                    if (isAbove && isRightSide) {
                        val text = line.text.trim()
                        
                        // Most VIP looks like a simple 1-3 digit number (e.g., "91" or "100" or "0").
                        // Make sure it doesn't contain "$", "BB", letters, etc., to avoid confusing it with blind indicators or bets (though bets are usually below).
                        val numStr = text.replace(Regex("[^0-9]"), "")
                        if (numStr.isNotEmpty() && numStr.length <= 3 && !text.contains("BB", ignoreCase = true) && !text.contains("$")) {
                            val num = numStr.toFloatOrNull()
                            // VPIP is 0..100.
                            if (num != null && num in 0f..100f) {
                                detectedVpip = num
                                vpipBox = lineBox
                                break
                            }
                        }
                    }
                }
            }
            var finalVpip = detectedVpip
            synchronized(trackedAnchors) {
                val anchor = trackedAnchors.firstOrNull { it.nickname == opp.nickname }
                if (anchor != null) {
                    if (detectedVpip != null) {
                        anchor.lastKnownVpip = detectedVpip
                    } else {
                        finalVpip = anchor.lastKnownVpip
                    }
                }
            }
            opp.copy(sessionVpip = finalVpip, sessionVpipBox = vpipBox)
        }

        return opponentsWithVpip.mapIndexed { i, opp -> opp.copy(id = i + 1) }
    }
}
