package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

object TemplateManager {
    
    val templates = mutableListOf<Template>()
    private var isInitialized = false

    class Template(
        val bitmap: Bitmap,
        val text: String, // e.g. "Ah 8c"
        val isHoleCards: Boolean
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also {
            bitmap.getPixels(it, 0, w, 0, 0, w, h)
        }
    }

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

    data class MatchResult(val text: String, val rect: android.graphics.Rect)

    // Returns a list of matches found from left to right
    fun matchMultiple(inputBmp: Bitmap, maxCards: Int): List<MatchResult> {
        if (templates.isEmpty()) return emptyList()
        
        val inputW = inputBmp.width
        val inputH = inputBmp.height
        if (inputW <= 0 || inputH <= 0) return emptyList()
        
        val inputPixels = IntArray(inputW * inputH)
        inputBmp.getPixels(inputPixels, 0, inputW, 0, 0, inputW, inputH)
        
        val foundCards = mutableListOf<Triple<Int, String, android.graphics.Rect>>() // X coord, Card Text, Rect
        val matchedRegions = mutableListOf<IntRange>()
        
        for (template in templates) {
            val tW = template.w
            val tH = template.h
            if (tW > inputW || tH > inputH) continue
            
            val maxY = inputH - tH
            val maxX = inputW - tW
            val yStep = Math.max(1, maxY / 4) // check a few Y offsets
            val xStep = 2 // slide every 2 pixels
            
            for (x in 0..maxX step xStep) {
                if (matchedRegions.any { x in it }) continue
                
                var bestMseLoc = Float.MAX_VALUE
                for (y in 0..maxY step yStep) {
                    val mse = computeMseFast(inputPixels, inputW, x, y, template.pixels, tW, tH)
                    if (mse < bestMseLoc) bestMseLoc = mse
                }
                
                if (bestMseLoc < 500.0f) { 
                    val rect = android.graphics.Rect(x, 0, x + tW, tH)
                    foundCards.add(Triple(x, template.text, rect))
                    matchedRegions.add(x until (x + tW - tW / 4))
                }
            }
        }
        
        foundCards.sortBy { it.first }
        return foundCards.take(maxCards).map { MatchResult(it.second, it.third) }
    }

    private fun computeMseFast(
        inputPixels: IntArray, inputW: Int, startX: Int, startY: Int,
        templatePixels: IntArray, tW: Int, tH: Int
    ): Float {
        var sumSq = 0L
        var tmplIdx = 0
        for (ty in 0 until tH) {
            var inputIdx = (startY + ty) * inputW + startX
            for (tx in 0 until tW) {
                val c1 = inputPixels[inputIdx]
                val c2 = templatePixels[tmplIdx]
                
                val r1 = (c1 shr 16) and 0xFF
                val g1 = (c1 shr 8) and 0xFF
                val b1 = c1 and 0xFF
                val gray1 = (r1 + g1 + b1) / 3
                
                val r2 = (c2 shr 16) and 0xFF
                val g2 = (c2 shr 8) and 0xFF
                val b2 = c2 and 0xFF
                val gray2 = (r2 + g2 + b2) / 3
                
                val diff = gray1 - gray2
                sumSq += (diff * diff)
                
                inputIdx++
                tmplIdx++
            }
        }
        return sumSq.toFloat() / (tW * tH)
    }
}
