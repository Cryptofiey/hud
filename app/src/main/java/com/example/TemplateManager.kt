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

    data class MatchResult(val text: String, val rect: android.graphics.Rect)

    // Returns a list of matches found from left to right
    fun matchMultiple(inputBmp: Bitmap, isHoleCards: Boolean, maxCards: Int): List<MatchResult> {
        if (templates.isEmpty()) return emptyList()
        
        val matchingTemplates = templates.filter { it.isHoleCards == isHoleCards }
        if (matchingTemplates.isEmpty()) return emptyList()
        
        val foundCards = mutableListOf<Triple<Int, String, android.graphics.Rect>>() // X coord, Card Text, Rect
        
        // Ensure we don't pick the same X coordinate multiple times by storing matched regions
        val matchedRegions = mutableListOf<IntRange>()
        
        for (template in matchingTemplates) {
            val tBmp = template.bitmap
            if (tBmp.width > inputBmp.width || tBmp.height > inputBmp.height) continue
            
            // Slide template across inputBmp's X axis
            // We assume the Y axis is mostly aligned, so we just check a small range of Y or Y=0 to Y = inputHeight - templateHeight
            val maxY = inputBmp.height - tBmp.height
            val maxX = inputBmp.width - tBmp.width
            
            val yStep = Math.max(1, maxY / 4) // check a few Y offsets
            val xStep = 2 // slide every 2 pixels
            
            for (x in 0..maxX step xStep) {
                // If this X is already covered by a matched region, skip
                if (matchedRegions.any { x in it }) continue
                
                var bestMseLoc = Float.MAX_VALUE
                for (y in 0..maxY step yStep) {
                    val mse = computeMseRegion(inputBmp, tBmp, x, y)
                    if (mse < bestMseLoc) bestMseLoc = mse
                }
                
                // If we found a strong match
                if (bestMseLoc < 500.0f) { 
                    val rect = android.graphics.Rect(x, 0, x + tBmp.width, tBmp.height)
                    foundCards.add(Triple(x, template.text, rect))
                    // Mark region as matched to avoid overlapping matches
                    matchedRegions.add(x until (x + tBmp.width - tBmp.width/4)) // Allow slight overlap
                }
            }
        }
        
        // Sort by X coordinate (left to right)
        foundCards.sortBy { it.first }
        
        return foundCards.take(maxCards).map { MatchResult(it.second, it.third) }
    }

    private fun computeMseRegion(bigBmp: Bitmap, smallBmp: Bitmap, startX: Int, startY: Int): Float {
        val w = smallBmp.width
        val h = smallBmp.height
        val pixelsBig = IntArray(w * h)
        val pixelsSmall = IntArray(w * h)
        
        bigBmp.getPixels(pixelsBig, 0, w, startX, startY, w, h)
        smallBmp.getPixels(pixelsSmall, 0, w, 0, 0, w, h)
        
        var sumSq = 0L
        for (i in pixelsBig.indices) {
            val c1 = pixelsBig[i]
            val c2 = pixelsSmall[i]
            
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
        
        return sumSq.toFloat() / (w * h)
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
