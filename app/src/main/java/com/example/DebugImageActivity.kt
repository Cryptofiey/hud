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
import androidx.compose.foundation.layout.*
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
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var debugLog by remember { mutableStateOf("Select an image to start debugging") }
    
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        selectedImageUri = uri
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    
                    if (bitmap != null) {
                        debugLog = "Image loaded: ${bitmap.width}x${bitmap.height}\nRunning ML Kit..."
                        runCardRecognition(bitmap) { processedBmp, log ->
                            resultBitmap = processedBmp
                            debugLog += "\n$log"
                        }
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
        Text("Poker Card OCR Debugger", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { launcher.launch("image/*") }) {
            Text("Select Screenshot")
        }
        Spacer(Modifier.height(8.dp))
        
        if (resultBitmap != null) {
            Image(
                bitmap = resultBitmap!!.asImageBitmap(),
                contentDescription = "Debug Result",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.DarkGray)
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.DarkGray))
        }
        
        Spacer(Modifier.height(8.dp))
        Text(debugLog, style = MaterialTheme.typography.bodySmall, modifier = Modifier.height(150.dp))
    }
}

private suspend fun runCardRecognition(bitmap: Bitmap, onResult: (Bitmap, String) -> Unit) {
    withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        
        val logBuilder = StringBuilder()
        
        try {
            val result = recognizer.process(image).await()
            
            // Draw on a copy of bitmap
            val outBmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(outBmp)
            val paintBox = Paint().apply {
                color = android.graphics.Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val paintText = Paint().apply {
                color = android.graphics.Color.GREEN
                textSize = 34f
                style = Paint.Style.FILL
            }

            logBuilder.appendLine("Found ${result.textBlocks.size} text blocks.")
            
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val text = element.text.trim()
                        val box = element.boundingBox
                        if (box != null && text.isNotEmpty()) {
                            canvas.drawRect(box, paintBox)
                            canvas.drawText(text, box.left.toFloat(), (box.bottom + 30).toFloat(), paintText)
                            
                            // Log finding
                            logBuilder.appendLine("'$text' at [${box.left},${box.top}] W:${box.width()} H:${box.height()}")
                        }
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                onResult(outBmp, logBuilder.toString())
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(bitmap, "ML Kit Error: ${e.message}")
            }
        }
    }
}
