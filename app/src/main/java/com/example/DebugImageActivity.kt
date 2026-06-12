package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class DebugImageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DebugScreen()
                }
            }
        }
    }
}

@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var debugLog by remember { mutableStateOf("Select a cropped card image to start auto-tuning.") }
    
    val ranks = listOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")
    var expectedRank by remember { mutableStateOf(ranks[0]) }
    var expandedRank by remember { mutableStateOf(false) }

    val suits = listOf("Spades", "Hearts", "Diamonds", "Clubs")
    var expectedSuit by remember { mutableStateOf(suits[0]) }
    var expandedSuit by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        selectedImageUri = uri
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    
                    if (bitmap != null) {
                        loadedBitmap = bitmap
                        resultBitmap = bitmap
                        debugLog = "Image loaded: ${bitmap.width}x${bitmap.height}\nTap 'Auto-Tune' to find optimal OCR parameters."
                    } else {
                        debugLog = "Failed to decode image."
                    }
                } catch (e: Exception) {
                    debugLog = "Error: ${e.message}"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Poker Card OCR Auto-Tuner", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Rank Dropdown
            Box(modifier = Modifier.weight(1f)) {
                Button(onClick = { expandedRank = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Rank: $expectedRank")
                }
                DropdownMenu(expanded = expandedRank, onDismissRequest = { expandedRank = false }) {
                    ranks.forEach { r ->
                        DropdownMenuItem(text = { Text(r) }, onClick = { 
                            expectedRank = r
                            expandedRank = false 
                        })
                    }
                }
            }
            // Suit Dropdown
            Box(modifier = Modifier.weight(1f)) {
                Button(onClick = { expandedSuit = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Suit: $expectedSuit")
                }
                DropdownMenu(expanded = expandedSuit, onDismissRequest = { expandedSuit = false }) {
                    suits.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { 
                            expectedSuit = s
                            expandedSuit = false 
                        })
                    }
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("Select Crop")
            }
            Button(
                onClick = {
                    if (loadedBitmap != null) {
                        debugLog = "Tuning started...\nTrying various brightness/contrast thresholds."
                        coroutineScope.launch {
                            tuneCardRecognition(loadedBitmap!!, expectedRank, expectedSuit) { processedBmp, log ->
                                resultBitmap = processedBmp
                                debugLog = log
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                enabled = loadedBitmap != null
            ) {
                Text("Auto-Tune")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (resultBitmap != null) {
            Image(
                bitmap = resultBitmap!!.asImageBitmap(),
                contentDescription = "Debug Result",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.DarkGray)
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.DarkGray))
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = debugLog, 
            style = MaterialTheme.typography.bodySmall, 
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )
    }
}

private suspend fun tuneCardRecognition(
    originalBitmap: Bitmap, 
    expectedRank: String, 
    expectedSuit: String, 
    onProgress: (Bitmap, String) -> Unit
) {
    withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val logBuilder = StringBuilder()
        
        val thresholds = listOf(130, 150, 170, 180, 195, 210)
        var successBitmap: Bitmap? = null
        var foundMatches = 0
        
        for (thresh in thresholds) {
            val testBmp = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(testBmp.width * testBmp.height)
            testBmp.getPixels(pixels, 0, testBmp.width, 0, 0, testBmp.width, testBmp.height)
            
            var redInk = 0
            var greenInk = 0
            var blueInk = 0
            
            // Loop 1: Apply standard threshold and ink count
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                
                // Color profiling based on robustDetectSuit logic
                val isRed = (r > g + 25 && r > b + 25 && r > 50 && g < 170 && b < 170)
                val isGreen = (g > r + 20 && g > b + 20 && g > 50 && r < 170 && b < 170)
                val isBlue = (b > r + 25 && b > g + 20 && b > 50 && r < 170 && g < 170)
                
                if (isRed) redInk++
                else if (isGreen) greenInk++
                else if (isBlue) blueInk++
                
                // OCR Threshold mapping
                if (r > thresh && g > thresh && b > thresh) {
                    pixels[i] = android.graphics.Color.BLACK // Light backgrounds -> BLACK text
                } else {
                    pixels[i] = android.graphics.Color.WHITE // Dark inks/shadows -> WHITE background
                }
            }
            testBmp.setPixels(pixels, 0, testBmp.width, 0, 0, testBmp.width, testBmp.height)
            
            // Determine suit
            val maxChroma = maxOf(redInk, greenInk, blueInk)
            val detectedSuit = if (maxChroma >= 5) {
                if (redInk == maxChroma) "Hearts"
                else if (greenInk == maxChroma) "Clubs"
                else if (blueInk == maxChroma) "Diamonds"
                else "Spades"
            } else {
                "Spades"
            }
            
            try {
                // Run OCR
                val image = InputImage.fromBitmap(testBmp, 0)
                val result = recognizer.process(image).await()
                
                var ocrTextFull = ""
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            var text = element.text.trim().uppercase(java.util.Locale.US)
                            // Replacements
                            text = text.replace("&", "8").replace("$", "8").replace("@", "Q").replace("%", "8").replace("?", "7").replace("!", "1")
                            text = text.replace("O", "Q").replace("0", "Q").replace("D", "Q") // common Q failures
                            ocrTextFull += text + " "
                        }
                    }
                }
                
                logBuilder.appendLine("Trying threshold $thresh: Detect Suit=$detectedSuit, OCR Text='$ocrTextFull'")
                
                if (ocrTextFull.contains(expectedRank) && detectedSuit == expectedSuit) {
                    logBuilder.appendLine(">>> SUCCESS! Rank $expectedRank and Suit $expectedSuit found at Threshold $thresh.")
                    successBitmap = testBmp
                    foundMatches++
                    break // Optional: we can stop on first success
                } else if (ocrTextFull.contains(expectedRank)) {
                    logBuilder.appendLine(">>> Rank matched but Suit mismatched (Expected $expectedSuit but got $detectedSuit).")
                    if (successBitmap == null) successBitmap = testBmp
                }
                
                withContext(Dispatchers.Main) {
                    onProgress(testBmp, logBuilder.toString())
                }
            } catch (e: Exception) {
                logBuilder.appendLine("Error at thresh $thresh: ${e.message}")
            }
        }
        
        logBuilder.appendLine("--- Auto-Tune Complete ---")
        if (foundMatches == 0) {
            logBuilder.appendLine("Could not find exact match for $expectedRank of $expectedSuit.")
            logBuilder.appendLine("Try manually tweaking the replacements in ScreenScanner or uploading a clearer crop.")
        }
        
        withContext(Dispatchers.Main) {
            onProgress(successBitmap ?: originalBitmap, logBuilder.toString())
        }
    }
}

