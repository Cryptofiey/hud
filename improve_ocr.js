const fs = require('fs');

let content = fs.readFileSync('app/src/main/java/com/example/ScreenScanner.kt', 'utf8');

const targetedCommScan = `
                // --- TARGETED COMM CARD SCAN ---
                if (commRect.width() > 0 && commRect.height() > 0) {
                    val safeComm = android.graphics.Rect(
                        maxOf(0, commRect.left - 10),
                        maxOf(0, commRect.top - 10),
                        minOf(cleanBitmap.width, commRect.right + 10),
                        minOf(cleanBitmap.height, commRect.bottom + 10)
                    )
                    if (safeComm.width() > 10 && safeComm.height() > 10) {
                        var croppedComm: Bitmap? = null
                        var scaledComm: Bitmap? = null
                        try {
                            croppedComm = Bitmap.createBitmap(cleanBitmap, safeComm.left, safeComm.top, safeComm.width(), safeComm.height())
                            val scale = 2
                            scaledComm = Bitmap.createScaledBitmap(croppedComm, croppedComm.width * scale, croppedComm.height * scale, true)
                            
                            val commInputImage = InputImage.fromBitmap(scaledComm, 0)
                            val commResult = recognizer.process(commInputImage).await()
                            
                            val rankPatternRegex = Regex("(10|1|T|[AKQJ]|[2-9])")
                            for (block in commResult.textBlocks) {
                                for (line in block.lines) {
                                    for (element in line.elements) {
                                        var rawText = element.text.uppercase(java.util.Locale.US).trim()
                                        rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")
                                        if (rawText == "O" || rawText == "D") rawText = "Q"
                                        if (rawText == "Z") rawText = "2"
                                        if (rawText == "B") rawText = "8"
                                        if (rawText == "S") rawText = "5"
                                        if (rawText == "I" || rawText == "l") rawText = "1"
                                        
                                        val match = rankPatternRegex.find(rawText)
                                        if (match != null) {
                                            val text = match.value
                                            val rank = parseRank(text) ?: continue
                                            val box = element.boundingBox ?: continue
                                            
                                            // Take only first matched rank from element to avoid trailing suit ghosts
                                            val matchRatio = if (rawText.isNotEmpty()) (match.range.first + match.range.last) / 2.0 / rawText.length else 0.5
                                            val localCenterX = box.left + (box.width() * matchRatio).toInt()
                                            
                                            val approxCenterBoxX = safeComm.left + (localCenterX / scale)
                                            
                                            var redCount = 0
                                            var greenCount = 0
                                            var blueCount = 0
                                            var blackCount = 0
                                            
                                            val startX = maxOf(0, approxCenterBoxX - (box.width() / scale))
                                            val endX = minOf(cleanBitmap.width - 1, approxCenterBoxX + (box.width() / scale / 4))
                                            val startY = maxOf(0, safeComm.top + (box.top / scale) - 5)
                                            val endY = minOf(cleanBitmap.height - 1, safeComm.top + (box.bottom / scale) + 20)
                                            
                                            if (startX <= endX && startY <= endY) {
                                                for (px in startX..endX step 2) {
                                                    for (py in startY..endY step 2) {
                                                        val pixel = cleanBitmap.getPixel(px, py)
                                                        val r = android.graphics.Color.red(pixel)
                                                        val g = android.graphics.Color.green(pixel)
                                                        val b = android.graphics.Color.blue(pixel)
                                                        
                                                        if (r - g > 40 && r - b > 40 && r > 100) redCount++
                                                        else if (g - r > 30 && g - b > 20 && g > 90) greenCount++
                                                        else if ((b - r > 30 && b > 90) || (b > 120 && g > 100 && r < 100)) blueCount++
                                                        else if (r < 90 && g < 90 && b < 90) blackCount++
                                                    }
                                                }
                                            }
                                            
                                            var suit = Suit.SPADES 
                                            if (redCount > greenCount && redCount > blueCount && redCount > blackCount && redCount > 5) suit = Suit.HEARTS 
                                            else if (greenCount > redCount && greenCount > blueCount && greenCount > blackCount && greenCount > 5) suit = Suit.CLUBS
                                            else if (blueCount > redCount && blueCount > greenCount && blueCount > blackCount && blueCount > 5) suit = Suit.DIAMONDS
                                            else if (blackCount > redCount && blackCount > greenCount && blackCount > blueCount && blackCount > 5) suit = Suit.SPADES
                                            else {
                                                if (redCount > 0) suit = Suit.HEARTS
                                                else if (greenCount > 0) suit = Suit.CLUBS
                                                else if (blueCount > 0) suit = Suit.DIAMONDS
                                            }
                                            
                                            val card = Card(rank, suit)
                                            foundCommCardsRaw.add(Pair(card, approxCenterBoxX))
                                        }
                                    }
                                }
                            }
                        } catch(e: Throwable) {} finally { 
                            croppedComm?.recycle()
                            scaledComm?.recycle()
                        }
                    }
                }
                // --- END TARGETED COMM CARD SCAN ---
`;

// Insert before TARGETED HOLE CARD SCAN
content = content.replace('// --- TARGETED HOLE CARD SCAN ---', targetedCommScan + '\n                // --- TARGETED HOLE CARD SCAN ---');

// Enhance Hole Card text replacement
content = content.replace('rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")', 
\`rawText = rawText.replace("1O", "10").replace("I0", "10").replace("IO", "10").replace("L0", "10")
                                        if (rawText == "O" || rawText == "D") rawText = "Q"
                                        if (rawText == "Z") rawText = "2"
                                        if (rawText == "B") rawText = "8"
                                        if (rawText == "S") rawText = "5"
                                        if (rawText == "I" || rawText == "l") rawText = "1"\`);

// Switch `findAll` to `find` and take first match the hole card section
content = content.replace(
\`                                        val matches = rankPatternRegex.findAll(rawText).toList()
                                        for (match in matches) {\`,
\`                                        val match = rankPatternRegex.find(rawText)
                                        if (match != null) {\`);

// In community card fallback, ensure it doesn't duplicate targeting Comm
// The fallback was doing: val inComm = expCommRect.contains(box.centerX(), box.centerY())
// Let's modify the fallback so that it only adds to CommCards if foundCommCardsRaw is empty
content = content.replace(
\`                                if (inComm) {
                                    foundCommCardsRaw.add(Pair(card, approxCenterBoxX))
                                } else if (inHole && foundHoleCardsRaw.isEmpty()) {\`,
\`                                if (inComm && foundCommCardsRaw.isEmpty()) {
                                    foundCommCardsRaw.add(Pair(card, approxCenterBoxX))
                                } else if (inHole && foundHoleCardsRaw.isEmpty()) {\`);

fs.writeFileSync('app/src/main/java/com/example/ScreenScanner.kt', content);
console.log("Scanner updated");
