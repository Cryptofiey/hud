package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

object TemplateManager {
    
    private val templates = mutableListOf<Template>()
    private var isInitialized = false

    data class Template(
        val bitmap: Bitmap,
        val text: String, // e.g. "Ah 8c"
        val isHoleCards: Boolean
    )

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadTemplates(context)
    }

    private fun loadTemplates(context: Context) {
        templates.clear()
        val dir = File(context.filesDir, "templates")
        if (!dir.exists()) dir.mkdirs()
        
        dir.listFiles()?.forEach { file ->
            if (file.extension == "png") {
                val name = file.nameWithoutExtension
                val parts = name.split("_", limit = 2)
                if (parts.size == 2) {
                    val isHole = parts[0] == "hole"
                    val text = parts[1].replace("-", " ")
                    try {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            templates.add(Template(bmp, text, isHole))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun saveTemplate(context: Context, bitmap: Bitmap, text: String, isHole: Boolean) {
        init(context)
        val dir = File(context.filesDir, "templates")
        if (!dir.exists()) dir.mkdirs()
        
        val safeText = text.replace(" ", "-").replace("/", "-")
        val prefix = if (isHole) "hole" else "comm"
        val fileName = "${prefix}_$safeText.png"
        val file = File(dir, fileName)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        // Remove existing with same text and type
        templates.removeAll { it.text == text && it.isHoleCards == isHole }
        templates.add(Template(bitmap, text, isHole))
    }

    // Returns parsing format "Ah 8c" or null
    fun match(inputBmp: Bitmap, isHoleCards: Boolean): String? {
        if (templates.isEmpty()) return null
        
        val matchingTemplates = templates.filter { it.isHoleCards == isHoleCards }
        if (matchingTemplates.isEmpty()) return null
        
        var bestMatch: String? = null
        var bestMse = Float.MAX_VALUE
        
        for (template in matchingTemplates) {
            val mse = computeMse(inputBmp, template.bitmap)
            if (mse < bestMse) {
                bestMse = mse
                bestMatch = template.text
            }
        }
        
        // Threshold: MSE < 600.0 means it's extremely close visually. 
        // 600 MSE -> sqrt(600) = ~24 avg diff out of 255 per pixel.
        // This tolerates small background noise and compression.
        if (bestMse < 600.0f) { 
            return bestMatch
        }
        return null
    }

    private fun computeMse(bmp1: Bitmap, bmp2: Bitmap): Float {
        // Resize bmp2 to bmp1 if they don't match, though ideally they should.
        val targetBmp = if (bmp1.width != bmp2.width || bmp1.height != bmp2.height) {
            Bitmap.createScaledBitmap(bmp2, bmp1.width, bmp1.height, true)
        } else {
            bmp2
        }
        
        val w = bmp1.width
        val h = bmp1.height
        val pixels1 = IntArray(w * h)
        val pixels2 = IntArray(w * h)
        
        bmp1.getPixels(pixels1, 0, w, 0, 0, w, h)
        targetBmp.getPixels(pixels2, 0, w, 0, 0, w, h)
        
        var sumSq = 0L
        for (i in pixels1.indices) {
            val c1 = pixels1[i]
            val c2 = pixels2[i]
            
            // Grayscale
            val r1 = Color.red(c1)
            val g1 = Color.green(c1)
            val b1 = Color.blue(c1)
            val gray1 = (r1 + g1 + b1) / 3
            
            val r2 = Color.red(c2)
            val g2 = Color.green(c2)
            val b2 = Color.blue(c2)
            val gray2 = (r2 + g2 + b2) / 3
            
            val diff = gray1 - gray2
            sumSq += (diff * diff)
        }
        
        if (targetBmp !== bmp2) {
            targetBmp.recycle()
        }
        
        return sumSq.toFloat() / (w * h)
    }
}
