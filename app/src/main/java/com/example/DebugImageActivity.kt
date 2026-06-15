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

fun performAutoCropIfNeeded(context: android.content.Context, originalBmp: Bitmap, isHole: Boolean, cardIndex: Int? = null): Bitmap {
    var targetBmp = originalBmp
    if (targetBmp.height > 1000) {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val screenWidth = context.resources.displayMetrics.widthPixels
        
        if (isHole) {
            val wPx = (screenWidth * 0.35f).toInt()
            val hPx = (screenHeight * 0.14f).toInt()
            val cx = (screenWidth * 0.44f).toInt()
            val cy = (screenHeight * 0.69f).toInt()
            
            // Scale factor if screenshot resolution differs from device displayMetrics
            val scaleX = targetBmp.width.toFloat() / screenWidth
            val scaleY = targetBmp.height.toFloat() / screenHeight
            
            var cropX = (cx * scaleX).toInt()
            val cropY = (cy * scaleY).toInt()
            var cropW = (wPx * scaleX).toInt()
            val cropH = (hPx * scaleY).toInt()
            
            if (cardIndex != null) {
                // Slicing piece for a single card out of the 2-card block
                val sliceW = cropW / 2
                cropX += sliceW * cardIndex
                cropW = sliceW
            }
            
            if (cropX >= 0 && cropY >= 0 && cropX + cropW <= targetBmp.width && cropY + cropH <= targetBmp.height) {
                targetBmp = Bitmap.createBitmap(targetBmp, cropX, cropY, cropW, cropH)
            }
        } else {
            val wPx = (screenWidth * 0.80f).toInt()
            val hPx = (screenHeight * 0.14f).toInt()
            val cx = (screenWidth * 0.10f).toInt()
            val cy = (screenHeight * 0.40f).toInt()
            
            val scaleX = targetBmp.width.toFloat() / screenWidth
            val scaleY = targetBmp.height.toFloat() / screenHeight
            
            var cropX = (cx * scaleX).toInt()
            val cropY = (cy * scaleY).toInt()
            var cropW = (wPx * scaleX).toInt()
            val cropH = (hPx * scaleY).toInt()
            
            if (cardIndex != null) {
                // Slicing piece for a single card out of the 5-card block
                val sliceW = cropW / 5
                cropX += sliceW * cardIndex
                cropW = sliceW
            }
            
            if (cropX >= 0 && cropY >= 0 && cropX + cropW <= targetBmp.width && cropY + cropH <= targetBmp.height) {
                targetBmp = Bitmap.createBitmap(targetBmp, cropX, cropY, cropW, cropH)
            }
        }
    }
    return targetBmp
}

@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var debugLog by remember { mutableStateOf("Select a cropped card image to start auto-tuning.") }
    var optimalThreshold by remember { mutableStateOf<Int?>(null) }
    
    var manualTemplateText by remember { mutableStateOf("") }
    var isHoleTemplate by remember { mutableStateOf(true) }
    var selectedCardIndex by remember { mutableStateOf(0) }
    
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
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualTemplateText,
                onValueChange = { manualTemplateText = it },
                label = { Text("Target Single Card (e.g. 'Ah')") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = isHoleTemplate, onCheckedChange = { 
                    isHoleTemplate = it
                    if (isHoleTemplate && selectedCardIndex > 1) {
                        selectedCardIndex = 1
                    }
                })
                Text("Hole?")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Extract Card Position (left-to-right): ", style = MaterialTheme.typography.bodySmall)
            val maxCards = if (isHoleTemplate) 2 else 5
            for (i in 0 until maxCards) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = selectedCardIndex == i,
                        onClick = { selectedCardIndex = i }
                    )
                    Text("$i", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("Select Image")
            }
            Button(
                onClick = {
                    if (loadedBitmap != null && manualTemplateText.isNotBlank()) {
                        debugLog = "Tuning started...\nTrying various brightness/contrast thresholds."
                        optimalThreshold = null
                        val targetBmp = performAutoCropIfNeeded(context, loadedBitmap!!, isHoleTemplate, selectedCardIndex)
                        coroutineScope.launch {
                            tuneCardRecognition(targetBmp, manualTemplateText) { processedBmp, log, bestThresh ->
                                resultBitmap = processedBmp
                                debugLog = log
                                if (bestThresh != null) optimalThreshold = bestThresh
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                enabled = loadedBitmap != null && manualTemplateText.isNotBlank()
            ) {
                Text("Auto-Tune OCR")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Button(
            onClick = {
                if (loadedBitmap != null && manualTemplateText.isNotBlank()) {
                    val targetBmp = performAutoCropIfNeeded(context, loadedBitmap!!, isHoleTemplate, selectedCardIndex)
                    
                    TemplateManager.saveTemplate(context, targetBmp, manualTemplateText, isHoleTemplate)
                    resultBitmap = targetBmp // Show them the cropped pattern we saved
                    debugLog = "SUCCESS: Saved SINGLE CARD template override for '${manualTemplateText}' at position $selectedCardIndex!\nThe scanner will now slide this exact crop from left to right."
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
            enabled = loadedBitmap != null && manualTemplateText.isNotBlank()
        ) {
            Text("Save as Visual Template Override")
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (optimalThreshold != null) {
            Button(
                onClick = {
                    optimalThreshold?.let { thresh ->
                        PreferencesManager(context).saveOcrThreshold(thresh)
                        ScannerConfig.ocrThreshold = thresh
                        debugLog += "\n✔️ Threshold $thresh saved as default!"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
            ) {
                Text("Save Threshold $optimalThreshold as Default")
            }
            Spacer(Modifier.height(8.dp))
        }
        
        if (resultBitmap != null) {
            Image(
                bitmap = resultBitmap!!.asImageBitmap(),
                contentDescription = "Debug Result",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(450.dp)
                    .background(Color.DarkGray)
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(450.dp).background(Color.DarkGray))
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
    expectedTarget: String, 
    onProgress: (Bitmap, String, Int?) -> Unit
) {
    withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val logBuilder = StringBuilder()
        
        val thresholds = listOf(130, 150, 170, 180, 195, 210, 220, 230)
        var successBitmap: Bitmap? = null
        var foundMatches = 0
        var optimalThresh: Int? = null
        
        val isSuitTarget = expectedTarget in listOf("Spades", "Hearts", "Diamonds", "Clubs")
        
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
                
                var success = false
                if (isSuitTarget) {
                    if (detectedSuit.equals(expectedTarget, ignoreCase = true)) success = true
                } else {
                    // Try to match rank or full combination
                    val targetParts = expectedTarget.split(" ")
                    var partsMatched = 0
                    for (part in targetParts) {
                        val simplePart = part.replace("h","").replace("d","").replace("c","").replace("s","")
                            .replace("H","").replace("D","").replace("C","").replace("S","") 
                        if (ocrTextFull.contains(simplePart, ignoreCase = true)) {
                            partsMatched++
                        }
                    }
                    if (partsMatched == targetParts.size && targetParts.isNotEmpty()) {
                        success = true
                    }
                }
                
                if (success) {
                    logBuilder.appendLine(">>> SUCCESS! Target $expectedTarget matched at Threshold $thresh.")
                    successBitmap = testBmp
                    foundMatches++
                    optimalThresh = thresh
                    break // Optional: we can stop on first success
                }
                
                withContext(Dispatchers.Main) {
                    onProgress(testBmp, logBuilder.toString(), null)
                }
            } catch (e: Exception) {
                logBuilder.appendLine("Error at thresh $thresh: ${e.message}")
            }
        }
        
        logBuilder.appendLine("--- Auto-Tune Complete ---")
        if (foundMatches == 0) {
            logBuilder.appendLine("Could not find match for $expectedTarget.")
        }
        
        withContext(Dispatchers.Main) {
            onProgress(successBitmap ?: originalBitmap, logBuilder.toString(), optimalThresh)
        }
    }
}

