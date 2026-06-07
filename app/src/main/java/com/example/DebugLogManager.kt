package com.example

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugLogManager {
    private var lastSaveTime = 0L

    fun savePeriodicScreenshot(bitmap: Bitmap, context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 5000) return // Max 1 per 5 seconds
        lastSaveTime = now

        try {
            val dir = File(context.cacheDir, "bot_screenshots").apply { mkdirs() }
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= 10) {
                files.take(files.size - 9).forEach { it.delete() } // keep max 10
            }
            
            val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            val file = File(dir, "screen_${now}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
            }
            scaled.recycle()
            Log.d("DebugLogManager", "Saved screenshot: ${file.name}")
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Error saving screenshot", e)
        }
    }

    suspend fun createExportZip(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
            val zipFile = File(exportDir, "poker_bot_debug.zip")
            
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. Write Logs.txt
                val logsContent = buildString {
                    appendLine("=== L1 LOG ===")
                    appendLine(BotLogSharedState.logL1.value)
                    appendLine("=== L2 LOG ===")
                    appendLine(BotLogSharedState.logL2.value)
                    appendLine("=== L3 LOG ===")
                    appendLine(BotLogSharedState.logL3.value)
                    appendLine("=== L4 LOG ===")
                    appendLine(BotLogSharedState.logL4.value)
                    appendLine("=== M1-M5 LOGS ===")
                    appendLine(BotLogSharedState.logM1.value)
                    appendLine(BotLogSharedState.logM2.value)
                    appendLine(BotLogSharedState.logM3.value)
                    appendLine(BotLogSharedState.logM4.value)
                    appendLine(BotLogSharedState.logM5.value)
                    appendLine("=== BOT L5 LOG ===")
                    appendLine(BotLogSharedState.logBot.value)
                }
                zos.putNextEntry(ZipEntry("logs.txt"))
                zos.write(logsContent.toByteArray())
                zos.closeEntry()

                // 2. Write screenshots
                val ssDir = File(context.cacheDir, "bot_screenshots")
                ssDir.listFiles()?.forEach { file ->
                    zos.putNextEntry(ZipEntry("screenshots/${file.name}"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Error creating zip", e)
            null
        }
    }
}
