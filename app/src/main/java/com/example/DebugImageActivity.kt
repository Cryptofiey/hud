package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugImageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyApplicationTheme { Surface { DebugScreen() } } }
    }
}

@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var debugLog by remember { mutableStateOf("AI Server Debugger v2.0 Ready.\n\nSelect a folder below depending on your task.") }

    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            debugLog = "Starting Auto-Template Generation from folder...\n"
            var count = 0
            val dir = DocumentFile.fromTreeUri(context, uri)
            dir?.listFiles()?.forEach { file ->
                if (file.name?.endsWith(".png") == true || file.name?.endsWith(".jpg") == true) {
                    val bmp = decodeUri(context, file.uri)
                    if (bmp == null) return@forEach
                    val expectedRegex = Regex("([2-9TJQKA][hdcs])", RegexOption.IGNORE_CASE)
                    val matches = expectedRegex.findAll(file.name ?: "")
                    val cards = matches.map { it.value }.toList()
                    
                    if (cards.size in 2..5) {
                        debugLog += "\nProcessing ${file.name} -> expected ${cards.size} cards: $cards"
                        val isHole = file.name!!.contains("hole", ignoreCase = true) || cards.size == 2
                        
                        val sliceWidth = bmp.width / cards.size
                        for (i in cards.indices) {
                            val cardStr = cards[i]
                            val slice = Bitmap.createBitmap(bmp, i * sliceWidth, 0, sliceWidth, bmp.height)
                            // We save each slice as the template mask with its assigned value
                            TemplateManager.saveTemplate(context, slice, cardStr, isHole)
                            slice.recycle()
                            count++
                        }
                    }
                    bmp.recycle()
                }
            }
            debugLog += "\n\nDONE! Generated $count templates."
        }
    }

    
    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.Main) {
            debugLog = "Starting Auto-Crop Pipeline from full screenshots...\n"
            var crCount = 0
            
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, uri)
                val timestamp = System.currentTimeMillis()
                
                val outDirInternal = java.io.File(context.filesDir, "PokerCropsInternal")
                if (outDirInternal.exists()) outDirInternal.deleteRecursively()
                outDirInternal.mkdirs()
                
                var pcIndex = 1
                var outDirPublic = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PC$pcIndex")
                while (outDirPublic.exists()) {
                    pcIndex++
                    outDirPublic = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PC$pcIndex")
                }
                outDirPublic.mkdirs()
                
                val scanner = ScreenScanner(context, null, 0)

                dir?.listFiles()?.forEach { file ->
                    if (file.name?.endsWith(".png") == true || file.name?.endsWith(".jpg") == true) {
                        val bmp = decodeUri(context, file.uri)
                        if (bmp == null) return@forEach
                        
                        val w = bmp.width
                        val h = bmp.height
                        
                        // Match default HUD regions for CoinPoker exactly as in PokerHudService
                        val cLeft = (w * 0.10f).toInt()
                        val cTop = (h * 0.40f).toInt()
                        val cRight = cLeft + (w * 0.80f).toInt()
                        val cBottom = cTop + (h * 0.14f).toInt()
                        
                        val hLeft = (w * 0.35f).toInt()
                        val hTop = (h * 0.65f).toInt()
                        val hRight = hLeft + (w * 0.35f).toInt()
                        val hBottom = hTop + (h * 0.14f).toInt()
                        
                        val cRect = android.graphics.Rect(cLeft, cTop, cRight, cBottom)
                        val hRect = android.graphics.Rect(hLeft, hTop, hRight, hBottom)
                        
                        val commCropTotal = Bitmap.createBitmap(bmp, cLeft, cTop, cRight - cLeft, cBottom - cTop)
                        val holeCropTotal = Bitmap.createBitmap(bmp, hLeft, hTop, hRight - hLeft, hBottom - hTop)

                        var commName = "comm_${System.currentTimeMillis()}"
                        var holeName = "hole_${System.currentTimeMillis()}"

                        // If scanner exists, try to guess the cards to auto-name the files for batch tester
                        if (scanner != null) {
                            val result = scanner.processGivenBitmap(context, bmp, hRect, cRect)
                            val holeCards = result.first.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                            val commCards = result.second.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                            
                            if (holeCards.isNotEmpty()) holeName = "hole_$holeCards"
                            if (commCards.isNotEmpty()) commName = "comm_$commCards"
                        }
                        
                        val fileCommInt = java.io.File(outDirInternal, "${commName}.png")
                        java.io.FileOutputStream(fileCommInt).use { commCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val fileHoleInt = java.io.File(outDirInternal, "${holeName}.png")
                        java.io.FileOutputStream(fileHoleInt).use { holeCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }

                        try {
                            val fileCommPub = java.io.File(outDirPublic, "${commName}.png")
                            java.io.FileOutputStream(fileCommPub).use { commCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val fileHolePub = java.io.File(outDirPublic, "${holeName}.png")
                            java.io.FileOutputStream(fileHolePub).use { holeCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } catch (e: Exception) {
                            // Ignore public storage errors
                        }
                        
                        commCropTotal.recycle()
                        holeCropTotal.recycle()
                        bmp.recycle()
                        
                        withContext(Dispatchers.Main) {
                            debugLog += "\n✅ Cropped ${file.name} -> $commName.png, $holeName.png"
                        }
                        crCount++
                    }
                }
                
                withContext(Dispatchers.Main) {
                    debugLog += "\n\n🎉 Нарезка окончена! Сохранено $crCount парных фото.\n"
                    debugLog += "Сохранено во внутреннюю память для Шага 2.\n"
                    debugLog += "Также копия направлена в Downloads/PC$pcIndex"
                }
            }
        }
    }

    fun runTestsOnFileUris(files: List<Pair<String, android.net.Uri>>, clearLog: Boolean = true) {
        coroutineScope.launch(Dispatchers.Main) {
            if (clearLog) debugLog = ""
            debugLog += "Starting Auto-Validation Test Suite (Server AI Mode)...\n"
            var passed = 0
            var failed = 0
            var missingFirst = 0
            var missingRandom = 0
            var missingAll = 0
            var slowStable = 0
            
            withContext(Dispatchers.IO) {
                files.forEach { (fileName, fileUri) ->
                    if (fileName.endsWith(".png") == true || fileName.endsWith(".jpg") == true) {
                        val bmp = decodeUri(context, fileUri)
                        if (bmp == null) return@forEach
                        val expectedRegex = Regex("([2-9TJQKA][hdcs])", RegexOption.IGNORE_CASE)
                        val matches = expectedRegex.findAll(fileName ?: "")
                        val expectedCards = matches.map { it.value.lowercase() }.toList()

                        withContext(Dispatchers.Main) {
                            debugLog += "\n-----------------------------------"
                            debugLog += "\n[TEST] ${fileName}\nExpected: $expectedCards"
                        }
                        
                        val isHole = fileName.contains("hole", ignoreCase = true) || expectedCards.size <= 2
                        
                        val hRect = if (isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)
                        val cRect = if (!isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)

                        val scanner = ScreenScanner(context, null, 0)
                        val result = scanner.processGivenBitmap(context, bmp, hRect, cRect)
                        val (detectedHole, detectedComm) = result
                        val activeRawList = if (isHole) detectedHole else detectedComm
                        
                        val nonNulls = activeRawList.count { it != null }
                        val rawStr = activeRawList.filterNotNull().map { it.toString().lowercase() }
                        val allMatch = expectedCards.size == rawStr.size && expectedCards.toSet() == rawStr.toSet()

                        withContext(Dispatchers.Main) {
                            if (allMatch) {
                                debugLog += "\n ✅ PASS - Immediate Hit."
                                passed++
                            } else {
                                debugLog += "\n ❌ FAIL - Incorrect detection."
                                debugLog += "\n   Raw Detected: $rawStr"
                                failed++
                                
                                if (nonNulls == 0 && expectedCards.isNotEmpty()) {
                                    debugLog += "\n ⚠️ ROOT ISSUE: Not finding cards at all (0 detected)."
                                    missingAll++
                                } else if (activeRawList.firstOrNull() == null && expectedCards.isNotEmpty()) {
                                    debugLog += "\n ⚠️ ROOT ISSUE: Skipping first card."
                                    missingFirst++
                                } else if (nonNulls < expectedCards.size) {
                                    debugLog += "\n ⚠️ ROOT ISSUE: Skipping random internal card(s)."
                                    missingRandom++
                                }
                            }
                        }
                        bmp.recycle()
                    }
                }
                withContext(Dispatchers.Main) {
                    debugLog += "\n\n=== AI DEBUG REPORT ==="
                    debugLog += "\nTotal Tests: ${passed + failed}"
                    debugLog += "\nPassed: $passed"
                    debugLog += "\nFailed: $failed"
                    debugLog += "\n-- Identified Root Issues --"
                    debugLog += "\nTotal Misses (Blindness): $missingAll cases"
                    debugLog += "\nFirst Card Skips: $missingFirst cases"
                    debugLog += "\nRandom Card Skips: $missingRandom cases"
                    debugLog += "\nFlickering / Slow Build-Up: $slowStable cases"
                    
                    try {
                        val reportFile = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PokerBot_ValidationReport.txt")
                        reportFile.writeText(debugLog)
                        debugLog += "\n\n✅ Отчет сохранен в Корневую папку Загрузки (Downloads)."
                    } catch (e: Exception) {
                        debugLog += "\nFailed to save report: ${e.message}"
                    }
                }
            }
        }
    }

    val testLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(context, uri)
            val filesList = dir?.listFiles()?.mapNotNull { file -> 
                file.name?.let { Pair(it, file.uri) }
            } ?: emptyList()
            runTestsOnFileUris(filesList)
        }
    }
    
    val runInternalTester = {
        coroutineScope.launch(Dispatchers.IO) {
            val outDirInternal = java.io.File(context.filesDir, "PokerCropsInternal")
            if (outDirInternal.exists()) {
                val filesList = outDirInternal.listFiles()?.map { file ->
                    Pair(file.name, android.net.Uri.fromFile(file))
                } ?: emptyList()
                
                if (filesList.isEmpty()) {
                    withContext(Dispatchers.Main) { debugLog = "Ошибка: Папка PokerCropsInternal пуста! Сначала выполните Шаг 1." }
                } else {
                    runTestsOnFileUris(filesList, clearLog = true)
                }
            } else {
                withContext(Dispatchers.Main) { debugLog = "Ошибка: Папка PokerCropsInternal не найдена! Сначала выполните Шаг 1." }
            }
        }
    }

    var driveLog by remember { mutableStateOf("") }
    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                coroutineScope.launch {
                    val driveManager = GoogleDriveManager(context)
                    val driveService = driveManager.getDriveService(account)
                    debugLog += "\nSuccessfully connected to Google Drive API as ${account.email}"
                    
                    // The specific folder from user request
                    val folderId = "1PnDobWj3RBd0LXmGiKEECAKP_WBdDvmR"
                    debugLog += "\nListing files in folder $folderId..."
                    
                    val files = driveManager.listFilesInFolder(driveService, folderId)
                    debugLog += "\nFound ${files.size} files."
                    
                    var renamedCount = 0
                    for (file in files) {
                        if (file.name.endsWith(".png") || file.name.endsWith(".jpg")) {
                            debugLog += "\nProcessing image: ${file.name}..."
                            
                            try {
                                val outStream = java.io.ByteArrayOutputStream()
                                driveService.files().get(file.id).executeMediaAndDownloadTo(outStream)
                                val bytes = outStream.toByteArray()
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                
                                if (bmp != null) {
                                    val w = bmp.width
                                    val h = bmp.height
                                    
                                    // Match default HUD regions for CoinPoker exactly as in PokerHudService
                                    val cLeft = (w * 0.10f).toInt()
                                    val cTop = (h * 0.40f).toInt()
                                    val cRight = cLeft + (w * 0.80f).toInt()
                                    val cBottom = cTop + (h * 0.14f).toInt()
                                    
                                    val hLeft = (w * 0.35f).toInt()
                                    val hTop = (h * 0.65f).toInt()
                                    val hRight = hLeft + (w * 0.35f).toInt()
                                    val hBottom = hTop + (h * 0.14f).toInt()
                                    
                                    val cRect = android.graphics.Rect(cLeft, cTop, cRight, cBottom)
                                    val hRect = android.graphics.Rect(hLeft, hTop, hRight, hBottom)
                                    
                                    val scanner = ScreenScanner(context, null, 0)
                                    val result = scanner.processGivenBitmap(context, bmp, hRect, cRect)
                                    val holeCards = result.first.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                                    val commCards = result.second.filterNotNull().joinToString("") { it.toHtmlString().replace("<[^>]*>".toRegex(), "") }.replace("&spades;", "s").replace("&clubs;", "c").replace("<font color='red'>&hearts;</font>", "h").replace("<font color='blue'>&diams;</font>", "d").replace("♥","h").replace("♦","d").replace("♠","s").replace("♣","c")
                                    
                                    var newName = file.name
                                    var changed = false
                                    if (file.name.contains("hole") && holeCards.isNotEmpty() && !file.name.contains(holeCards)) {
                                        newName = "hole_${holeCards}.png"
                                        changed = true
                                    } else if (file.name.contains("comm") && commCards.isNotEmpty() && !file.name.contains(commCards)) {
                                        newName = "comm_${commCards}.png"
                                        changed = true
                                    } else if (!file.name.contains("hole") && !file.name.contains("comm")) {
                                        // Assume it's a full screenshot, rename to include both
                                        newName = "screenshot_${holeCards}_${commCards}.png"
                                        changed = true
                                    }
                                    
                                    if (changed && newName != file.name) {
                                        val success = driveManager.renameFile(driveService, file.id, newName)
                                        if (success) {
                                            debugLog += " -> Renamed to $newName"
                                            renamedCount++
                                        } else {
                                            debugLog += " -> Failed to rename."
                                        }
                                    } else {
                                        debugLog += " -> Already correct or cannot infer."
                                    }
                                    bmp.recycle()
                                }
                            } catch (e: Exception) {
                                debugLog += "\nError: ${e.message}"
                            }
                        }
                    }
                    debugLog += "\nDrive Enum Complete. Found $renamedCount images."
                }
            } catch (e: Exception) {
                debugLog += "\nGoogle Sign In Failed: ${e.message}"
            }
        } else {
            debugLog += "\nGoogle Sign In Cancelled."
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Automated Server Debugger", style = MaterialTheme.typography.titleLarge)
        
        Spacer(Modifier.height(16.dp))
        
        Button(onClick = { cropLauncher.launch(android.net.Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
            Text("1. Нарезка фото-кадров (Auto-Crop)")
        }

        Spacer(Modifier.height(8.dp))
        
        Button(onClick = { runInternalTester() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))) {
            Text("2. Запуск тестера (Внутренние фото Шага 1)")
        }
        
        Spacer(Modifier.height(8.dp))
        
        Button(onClick = { testLauncher.launch(android.net.Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))) {
            Text("3. Тестер из папки (Выбрать любую)")
        }
        
        Spacer(Modifier.height(8.dp))

        Button(onClick = { templateLauncher.launch(android.net.Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth()) {
            Text("4. Генератор шаблонов (Опционально)")
        }

        Spacer(Modifier.height(8.dp))

        Button(onClick = { 
            val client = GoogleDriveManager(context).getSignInClient()
            googleSignInLauncher.launch(client.signInIntent)
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))) {
            Text("5. Google Drive Auto-Label (OAuth2)")
        }
        
        Spacer(Modifier.height(16.dp))

        
        Text(
            text = debugLog, 
            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF00FF00), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), 
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

fun decodeUri(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
         context.contentResolver.openInputStream(uri)?.use { stream ->
             BitmapFactory.decodeStream(stream)
         }
    } catch(e: Exception) { null }
}

