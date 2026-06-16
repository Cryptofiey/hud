package com.example

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import org.junit.Assert.fail

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ScannerDebuggerTest {

    @Test
    fun testScannerOnProcessedScreenshots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Setup TemplateManager using provided template samples if any
        // Where are the templates?
        
        val dir = File("../processed_screenshots")
        if (!dir.exists()) {
            println("No processed_screenshots found at ${dir.absolutePath}")
            return
        }

        var passed = 0
        var failed = 0

        dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.forEach { file ->
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp == null) {
                println("Failed to decode ${file.name}")
                return@forEach
            }
            
            // Expected
            val fileName = file.name
            val expectedRegex = Regex("((?:10|[2-9TJQKA])[hdcs])", RegexOption.IGNORE_CASE)
            
            val commPart = fileName.substringAfter("comm_").substringBefore("_hole_")
            val holePart = fileName.substringAfter("_hole_").substringBeforeLast(".")
            
            val expectedComm = expectedRegex.findAll(commPart).map { it.value.lowercase() }.toList()
            val expectedHole = expectedRegex.findAll(holePart).map { it.value.lowercase() }.toList()
            
            // Run Scanner
            val scanner = ScreenScanner(context, null, 0)
            // It expects a full screenshot context with specific coordinate boundaries for coinpoker if they are full screenshots, 
            // BUT wait! These are full screenshots or cropped?
            // "comm_10dqcqh3s_hole_.jpg" are full size screen screenshots or cropped?
            // Let's assume full size for now
            
            val hRect = android.graphics.Rect(0,0,bmp.width,bmp.height)
            val cRect = android.graphics.Rect(0,0,bmp.width,bmp.height)
            // Wait, DebugImageActivity has the rects logic for this dataset.
            val w = bmp.width
            val h = bmp.height
            val cLeft = (w * 0.10f).toInt()
            val cTop = (h * 0.40f).toInt()
            val cRight = cLeft + (w * 0.80f).toInt()
            val cBottom = cTop + (h * 0.14f).toInt()
            
            val hLeft = (w * 0.35f).toInt()
            val hTop = (h * 0.65f).toInt()
            val hRight = hLeft + (w * 0.35f).toInt()
            val hBottom = hTop + (h * 0.14f).toInt()
            
            val actualCRect = android.graphics.Rect(cLeft, cTop, cRight, cBottom)
            val actualHRect = android.graphics.Rect(hLeft, hTop, hRight, hBottom)
            
            val result = kotlinx.coroutines.runBlocking { scanner.processGivenBitmap(context, bmp, actualHRect, actualCRect) }
            
            val detectedHole = result.first.filterNotNull().map { it.toString().lowercase() }
            val detectedComm = result.second.filterNotNull().map { it.toString().lowercase() }
            
            val holeMatch = expectedHole == detectedHole
            val commMatch = expectedComm == detectedComm
            
            if (holeMatch && commMatch) {
                passed++
                println("PASS: ${file.name}")
            } else {
                failed++
                println("FAIL: ${file.name}")
                println("  Expected Hole: $expectedHole, Comm: $expectedComm")
                println("  Detected Hole: $detectedHole, Comm: $detectedComm")
                println("  Diagnostics: ${scanner.debugLogInfo.replace("\n", " | ")}")
            }
        }
        
        println("SUMMARY: $passed passed, $failed failed.")
    }
}
