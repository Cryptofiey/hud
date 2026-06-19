package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Rect
import com.example.ui.theme.MyApplicationTheme
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks

class DebugImageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF131313)
                ) {
                    DebugScreen()
                }
            }
        }
    }

    @Composable
    fun DebugScreen() {
        var results by remember { mutableStateOf<List<DebugResult>>(emptyList()) }
        var isProcessing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Button(
                onClick = {
                    if (!isProcessing) {
                        isProcessing = true
                        scope.launch {
                            results = processAllTestScreens()
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isProcessing) "Ожидайте..." else "Прогнать ${"assets/test_screens/"} через дебагер")
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { res ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF222222))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("File: ${res.fileName}", color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Actions: ${res.actions}", color = Color(0xFF4CAF50), fontSize = 14.sp)
                            Text("Sizing/Preactions: ${res.sizing}", color = Color.Yellow, fontSize = 12.sp)
                            Text("Transitions: ${res.transitions}", color = Color.Cyan, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processAllTestScreens(): List<DebugResult> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DebugResult>()
        val files = try {
            assets.list("test_screens") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        val recognizer = ScreenScanner.recognizer
        for (file in files) {
            if (!file.endsWith(".jpg") && !file.endsWith(".png")) continue
            val istream = assets.open("test_screens/$file")
            val bmp = BitmapFactory.decodeStream(istream)
            istream.close()

            if (bmp == null) continue

            // Run ML Kit over the whole image
            val inputImage = InputImage.fromBitmap(bmp, 0)
            val ocrRes = try {
                Tasks.await(recognizer.process(inputImage), 10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                list.add(DebugResult(file, "ERROR: ${e.message}", "", ""))
                bmp.recycle()
                continue
            }

            val actions = mutableSetOf<String>()
            val sizing = mutableSetOf<String>()
            val transitions = mutableSetOf<String>()

            val bottomZoneTop = bmp.height * 0.82
            val elementsInBottomZone = mutableListOf<com.google.mlkit.vision.text.Text.Element>()

            for (block in ocrRes.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        
                        // Transition matching (anywhere)
                        val rawText = element.text.uppercase(java.util.Locale.US)
                        val normalized = rawText.replace(" ", "").replace("-", "").replace("_", "")
                                .replace("А", "A").replace("В", "B").replace("Е", "E").replace("К", "K")
                                .replace("М", "M").replace("Н", "H").replace("О", "O").replace("Р", "P")
                                .replace("С", "C").replace("Т", "T").replace("Х", "X").replace("У", "Y")
                                .replace("И", "I")

                        var transitionKey = ""
                        if (normalized == "REGISTER" || normalized.contains("РЕГИСТР") || normalized.contains("УЧАСТВ")) transitionKey = "REGISTER"
                        else if (normalized == "BUYIN" || normalized == "BUIIN" || normalized.contains("БАЙИН") || normalized.contains("ПОПОЛНИТЬ")) transitionKey = "BUY_IN"
                        else if (normalized == "PLAYNOW" || normalized == "PLAY" || normalized == "ИГРАТЬ" || normalized == "НАЧАТЬ") transitionKey = "PLAY"
                        else if (normalized == "TAKESEAT" || normalized == "SITDOWN" || normalized.contains("ЗАНЯТЬМ")) transitionKey = "TAKE_SEAT"
                        else if (normalized == "JOINBACK" || normalized == "IMBACK" || normalized == "RETURN" || normalized.contains("ВЕРНУТЬСЯ")) transitionKey = "JOIN_BACK"
                        
                        if (transitionKey.isNotEmpty()) transitions.add(transitionKey)

                        // Sizing and Preactions (bottom zone)
                        if (box.top > bottomZoneTop) {
                            elementsInBottomZone.add(element)
                        }
                    }
                }
            }

            elementsInBottomZone.sortByDescending { it.boundingBox?.top ?: 0 }

            for (element in elementsInBottomZone) {
                val box = element.boundingBox ?: continue
                val originalTextUpper = element.text.uppercase()
                val textUpper = originalTextUpper.replace(" ", "")

                if (box.width() < bmp.width * 0.01f || box.height() < bmp.height * 0.005f) continue

                val isBright = isColorfulButton(bmp, box)
                
                val isAll = textUpper.contains("ALL-IN") || textUpper.contains("ALLIN") || (textUpper.contains("ALL") && !textUpper.contains("CALL")) || textUpper.contains("ОЛЛ") || textUpper.contains("ВЫСТАВИТЬ")
                val isSizing = textUpper.contains("MAX") || textUpper.contains("МАКС") || textUpper.contains("POT") || textUpper.contains("ПОТ") || isAll || textUpper.contains("1/2") || textUpper.contains("3/4") || textUpper == "+" || textUpper == "-"
                if (isSizing) sizing.add(originalTextUpper)

                val qualifiesAsAction = isBright

                if ((textUpper.contains("FOLD") || textUpper.contains("ФОЛД") || textUpper.contains("ПАС")) && !textUpper.contains("ANY")) {
                    if (qualifiesAsAction) actions.add("Fold") else sizing.add("PreFold: $originalTextUpper")
                } 
                else if ((textUpper.contains("CHECK") || textUpper.contains("ЧЕК")) && !textUpper.contains("FOLD") && !textUpper.contains("ФОЛД")) {
                    if (qualifiesAsAction) actions.add("Check") else sizing.add("PreCheck: $originalTextUpper")
                } 
                else if ((textUpper.contains("CALL") || textUpper.contains("КОЛЛ")) && !textUpper.contains("ANY") && !textUpper.contains("ЛЮБ")) {
                    if (qualifiesAsAction) actions.add("Call") else sizing.add("PreCall: $originalTextUpper")
                } 
                else if (textUpper.contains("RAISE") || textUpper.contains("РЕЙЗ") || 
                            textUpper.contains("BET") || textUpper.contains("БЕТ") || 
                            textUpper.contains("CONFIRM") || textUpper.contains("ПОДТВЕРДИТЬ")) {
                    if (qualifiesAsAction) actions.add("Raise") else sizing.add("PreRaise: $originalTextUpper")
                } 
                else if (textUpper.contains("ALL-IN") || textUpper.contains("ALLIN") || 
                            textUpper.contains("ОЛЛ-ИН") || textUpper.contains("ALL") || 
                            textUpper.contains("ОЛЛ") || textUpper.contains("ВЫСТАВИТЬ")) {
                    if (qualifiesAsAction) actions.add("All-in")
                }
            }

            list.add(DebugResult(file, actions.toString(), sizing.toString(), transitions.toString()))
            bmp.recycle()
        }

        list
    }

    private fun isColorfulButton(bitmap: android.graphics.Bitmap, box: android.graphics.Rect): Boolean {
        val xExpand = (box.width() * 0.2f).toInt()
        val yExpand = (box.height() * 0.5f).toInt()
        
        val bounds = android.graphics.Rect(
            (box.left - xExpand).coerceAtLeast(0),
            (box.top - yExpand).coerceAtLeast(0),
            (box.right + xExpand).coerceAtMost(bitmap.width - 1),
            (box.bottom + yExpand).coerceAtMost(bitmap.height - 1)
        )
        
        var brightColorPixels = 0
        val xStep = (bounds.width() / 15).coerceAtLeast(1)
        val yStep = (bounds.height() / 15).coerceAtLeast(1)
        
        for (x in bounds.left..bounds.right step xStep) {
            for (y in bounds.top..bounds.bottom step yStep) {
                val color = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(color)
                val g = android.graphics.Color.green(color)
                val b = android.graphics.Color.blue(color)
                
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val diff = maxC - minC
                
                if (maxC > 80 && diff > 35) brightColorPixels++
            }
        }
        return brightColorPixels >= 3
    }
}

data class DebugResult(val fileName: String, val actions: String, val sizing: String, val transitions: String)
