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
                        
                        // Categorize raw failures
                        val nonNulls = activeRawList.count { it != null }
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
                        
                        // Simulate multi-frame smoothing
                        val history = mutableListOf<List<Card?>>()
                        val confirmed = mutableListOf<Card?>()
                        var stabilizedFrame = -1
                        var finalSmoothedStr = listOf<String>()
                        
                        // Emulate 8 frames of seeing this exact raw output
                        for (frame in 1..8) {
                            val smoothed = scanner.getSmoothedCards(history, activeRawList, confirmed, if (isHole) 3 else 4)
                            val smoothedStr = smoothed.filterNotNull().map { it.toString().lowercase() }
                            
                            val allMatch = expectedCards.size == smoothedStr.size && expectedCards.toSet() == smoothedStr.toSet()
                            if (allMatch && stabilizedFrame == -1) {
                                stabilizedFrame = frame
                                finalSmoothedStr = smoothedStr
                            }
                            if (frame == 8 && stabilizedFrame == -1) {
                                finalSmoothedStr = smoothedStr
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (stabilizedFrame == 1) {
                                debugLog += "\n ✅ PASS - Immediate Hit."
                                passed++
                            } else if (stabilizedFrame > 1) {
                                debugLog += "\n ⚠️ PASS (Delayed) - Flickering / Iteration Build-Up: Stabilized at frame $stabilizedFrame."
                                debugLog += "\n   Result: $finalSmoothedStr"
                                slowStable++
                                passed++
                            } else {
                                debugLog += "\n ❌ FAIL - Could not resolve correctly after 8 frames."
                                debugLog += "\n   Detected: $finalSmoothedStr / Raw: ${activeRawList.map{it?.toString()}}"
                                failed++
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
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Automated Server Debugger", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        
        Button(onClick = { templateLauncher.launch(Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth()) {
            Text("1. Auto-Template Generator (Select Folder)")
        }
        
        Button(onClick = { testLauncher.launch(Uri.parse("content://")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))) {
            Text("2. Auto-Validation Tester (Select Folder)")
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

