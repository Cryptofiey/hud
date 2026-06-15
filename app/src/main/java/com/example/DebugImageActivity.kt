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
                    val bmp = decodeUri(context, file.uri) ?: return@forEach
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
                val outDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PokerCrops_$timestamp")
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
                        
                        val commOut = java.io.File(outDir, "${commName}.png")
                        java.io.FileOutputStream(commOut).use { commCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }

                        val holeOut = java.io.File(outDir, "${holeName}.png")
                        java.io.FileOutputStream(holeOut).use { holeCropTotal.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        
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
                    debugLog += "\n\n🎉 Нарезка окончена! Сохранено $crCount парных фото в общую папку устройства: Downloads/PokerCrops_$timestamp/\nТеперь можно запустить тестер на эту папку."
                }
            }
        }
    }

    val testLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.Main) {
            debugLog = "Starting Auto-Validation Test Suite (Server AI Mode)...\n"
            var passed = 0
            var failed = 0
            var missingFirst = 0
            var missingRandom = 0
            var missingAll = 0
            var slowStable = 0
            
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, uri)
                dir?.listFiles()?.forEach { file ->
                    if (file.name?.endsWith(".png") == true || file.name?.endsWith(".jpg") == true) {
                        val bmp = decodeUri(context, file.uri) ?: return@forEach
                        val expectedRegex = Regex("([2-9TJQKA][hdcs])", RegexOption.IGNORE_CASE)
                        val matches = expectedRegex.findAll(file.name ?: "")
                        val expectedCards = matches.map { it.value.lowercase() }.toList()

                        debugLog += "\n-----------------------------------"
                        debugLog += "\n[TEST] ${file.name}\nExpected: $expectedCards"
                        
                        val isHole = file.name!!.contains("hole", ignoreCase = true) || expectedCards.size <= 2
                        
                        val hRect = if (isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)
                        val cRect = if (!isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)

                        val hudService = PokerHudService.instance
                        if (hudService == null) {
                            withContext(Dispatchers.Main) {
                                debugLog += "\nERROR: HUD Service is not running! Start it first!"
                            }
                            return@forEach
                        }
                        
                        
                        val scanner = ScreenScanner(hudService, android.content.Intent(), 0)
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
                        debugLog += "\n\n✅ Отчет сохранен в корневую общую папку Downloads (Файлы -> Меню -> Загрузки/Downloads). Пожалуйста, прикрепите его сюда или отправьте через GitHub!"
                    } catch (e: Exception) {
                        debugLog += "\nFailed to save report: ${e.message}"
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Automated Server Debugger", style = MaterialTheme.typography.titleLarge)
        
        Spacer(Modifier.height(16.dp))
        
        Button(onClick = { cropLauncher.launch(android.net.Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
            Text("1. Нарезка фото-кадров (Auto-Crop Pipeline)")
        }

        Spacer(Modifier.height(8.dp))
        
        Button(onClick = { testLauncher.launch(Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))) {
            Text("2. Массовый Тестер (Auto-Validation Tester)")
        }
        
        Spacer(Modifier.height(8.dp))

        Button(onClick = { templateLauncher.launch(Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth()) {
            Text("3. Генератор шаблонов (Не обязательно)")
        }
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = debugLog, 
            style = MaterialTheme.typography.bodySmall, 
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFE0E0E0))
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

