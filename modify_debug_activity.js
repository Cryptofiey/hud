const fs = require('fs');

let code = fs.readFileSync('app/src/main/java/com/example/DebugImageActivity.kt', 'utf8');

// Optimize the smoothing logic in the tester because a static image has static OCR
let testerLogic = `
                        val scanner = ScreenScanner(hudService, android.content.Intent(), 0)
                        val result = scanner.processGivenBitmap(context, bmp, hRect, cRect)
                        val (detectedHole, detectedComm) = result
                        val activeRawList = if (isHole) detectedHole else detectedComm
                        
                        val nonNulls = activeRawList.count { it != null }
                        val rawStr = activeRawList.filterNotNull().map { it.toString().lowercase() }
                        val allMatch = expectedCards.size == rawStr.size && expectedCards.toSet() == rawStr.toSet()

                        withContext(Dispatchers.Main) {
                            if (allMatch) {
                                debugLog += "\\n ✅ PASS - Immediate Hit."
                                passed++
                            } else {
                                debugLog += "\\n ❌ FAIL - Incorrect detection."
                                debugLog += "\\n   Raw Detected: $rawStr"
                                failed++
                                
                                if (nonNulls == 0 && expectedCards.isNotEmpty()) {
                                    debugLog += "\\n ⚠️ ROOT ISSUE: Not finding cards at all (0 detected)."
                                    missingAll++
                                } else if (activeRawList.firstOrNull() == null && expectedCards.isNotEmpty()) {
                                    debugLog += "\\n ⚠️ ROOT ISSUE: Skipping first card."
                                    missingFirst++
                                } else if (nonNulls < expectedCards.size) {
                                    debugLog += "\\n ⚠️ ROOT ISSUE: Skipping random internal card(s)."
                                    missingRandom++
                                }
                            }
                        }
`;

// Replace the old test logic
let startIdx = code.indexOf('val scanner = ScreenScanner(hudService, android.content.Intent(), 0)');
let endIdx = code.indexOf('bmp.recycle()', startIdx);
code = code.substring(0, startIdx) + testerLogic + "                        " + code.substring(endIdx);


// Add Auto-Crop Pipeline
let cropPipeline = `
    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.Main) {
            debugLog = "Starting Auto-Crop Pipeline from full screenshots...\\n"
            var crCount = 0
            
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, uri)
                val outDir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "PokerCrops")
                if (!outDir.exists()) outDir.mkdirs()
                
                val hudService = PokerHudService.instance
                val scanner = if (hudService != null) ScreenScanner(hudService, android.content.Intent(), 0) else null

                dir?.listFiles()?.forEach { file ->
                    if (file.name?.endsWith(".png") == true || file.name?.endsWith(".jpg") == true) {
                        val bmp = decodeUri(context, file.uri) ?: return@forEach
                        
                        val w = bmp.width
                        val h = bmp.height
                        
                        // Default regions for CoinPoker
                        val cLeft = (w * 0.22f).toInt()
                        val cTop = (h * 0.38f).toInt()
                        val cRight = (w * 0.78f).toInt()
                        val cBottom = (h * 0.68f).toInt()
                        
                        val hLeft = (w * 0.50f).toInt()
                        val hTop = (h * 0.65f).toInt()
                        val hRight = (w * 0.98f).toInt()
                        val hBottom = (h * 0.98f).toInt()
                        
                        val cRect = android.graphics.Rect(cLeft, cTop, cRight, cBottom)
                        val hRect = android.graphics.Rect(hLeft, hTop, hRight, hBottom)
                        
                        val commCropTotal = Bitmap.createBitmap(bmp, cLeft, cTop, cRight - cLeft, cBottom - cTop)
                        val holeCropTotal = Bitmap.createBitmap(bmp, hLeft, hTop, hRight - hLeft, hBottom - hTop)

                        var commName = "comm_\${System.currentTimeMillis()}"
                        var holeName = "hole_\${System.currentTimeMillis()}"

                        // If scanner exists, try to guess the cards to auto-name the files for batch tester
                        if (scanner != null) {
                            val result = scanner.processGivenBitmap(context, bmp, hRect, cRect)
                            val holeCards = result.first.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                            val commCards = result.second.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                            
                            if (holeCards.isNotEmpty()) holeName = "hole_$holeCards"
                            if (commCards.isNotEmpty()) commName = "comm_$commCards"
                        }
                        
                        val commOut = java.io.File(outDir, "\${commName}.png")
                        java.io.FileOutputStream(commOut).use { commCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }

                        val holeOut = java.io.File(outDir, "\${holeName}.png")
                        java.io.FileOutputStream(holeOut).use { holeCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        
                        commCropTotal.recycle()
                        holeCropTotal.recycle()
                        bmp.recycle()
                        
                        withContext(Dispatchers.Main) {
                            debugLog += "\\n✅ Cropped \${file.name} -> $commName.png, $holeName.png"
                        }
                        crCount++
                    }
                }
                
                withContext(Dispatchers.Main) {
                    debugLog += "\\n\\n🎉 Pipeline Complete! Saved $crCount full pairs to Downloads/PokerCrops/\\nYou can copy them anywhere or point test suite directly there."
                }
            }
        }
    }
`;

// Insert the new launcher right before testLauncher
code = code.replace('val testLauncher =', cropPipeline + '\n    val testLauncher =');

// Add the button
let btnCode = `
        Button(onClick = { cropLauncher.launch(android.net.Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
            Text("3. Auto-Crop Pipeline (Screenshots -> Tests)")
        }
`;
code = code.replace('Spacer(Modifier.height(16.dp))', btnCode + '\n        Spacer(Modifier.height(16.dp))');

fs.writeFileSync('app/src/main/java/com/example/DebugImageActivity.kt', code);
console.log("Updated DebugImageActivity.kt!");
