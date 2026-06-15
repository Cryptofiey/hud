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
            debugLog = "Starting Auto-Validation Test Suite...\n"
            var passed = 0
            var failed = 0
            
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, uri)
                dir?.listFiles()?.forEach { file ->
                    if (file.name?.endsWith(".png") == true || file.name?.endsWith(".jpg") == true) {
                        val bmp = decodeUri(context, file.uri) ?: return@forEach
                        val expectedRegex = Regex("([2-9TJQKA][hdcs])", RegexOption.IGNORE_CASE)
                        val matches = expectedRegex.findAll(file.name ?: "")
                        val expectedCards = matches.map { it.value.lowercase() }.toList()

                        debugLog += "\n[TEST] ${file.name}"
                        
                        val isHole = file.name!!.contains("hole", ignoreCase = true) || expectedCards.size == 2
                        
                        val hRect = if (isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)
                        val cRect = if (!isHole) android.graphics.Rect(0, 0, bmp.width, bmp.height) else android.graphics.Rect(0, 0, 0, 0)

                        val hudService = PokerHudService.instance
                        if (hudService == null) {
                            withContext(Dispatchers.Main) {
                                debugLog += "\nERROR: HUD Service is not running! Start it first!"
                            }
                            return@forEach
                        }
                        
                        val result = ScreenScanner(hudService, android.content.Intent(), 0).processGivenBitmap(context, bmp, hRect, cRect)
                        val (detectedHole, detectedComm) = result
                        
                        val detectedStr = (detectedHole.filterNotNull().map { it.toString().lowercase() } + 
                                          detectedComm.filterNotNull().map { it.toString().lowercase() })
                        
                        // We check if all expected cards are in the detected list and vice versa
                        val expectedSet = expectedCards.toSet()
                        val detectedSet = detectedStr.toSet()
                        
                        val allMatch = expectedSet == detectedSet && expectedCards.size == detectedStr.size
                        
                        withContext(Dispatchers.Main) {
                            if (allMatch) {
                                debugLog += " -> PASS"
                                passed++
                            } else {
                                debugLog += " -> FAIL!\n   Expected: $expectedCards\n   Detected: $detectedStr"
                                failed++
                            }
                        }
                        bmp.recycle()
                    }
                }
                withContext(Dispatchers.Main) {
                    debugLog += "\n\n=== TEST REPORT ===\nTotal: ${passed + failed}\nPassed: $passed\nFailed: $failed"
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

