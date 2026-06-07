package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugLogManager {
    private var lastSaveTime = 0L

    // Unified Instant Diagnostic Packing variables (10 second merging rule)
    private var lastCaptureTime = 0L
    private var activeSessionId = ""
    private var sessionStartTime = 0L
    private var captureIndex = 0
    private var lastSavedUri: Uri? = null

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

    suspend fun triggerDiagnosticCapture(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val timeDelta = now - lastCaptureTime
        lastCaptureTime = now

        val isMerged = timeDelta < 10000 && activeSessionId.isNotEmpty()

        if (isMerged) {
            captureIndex++
        } else {
            activeSessionId = now.toString()
            sessionStartTime = now
            captureIndex = 1
            lastSavedUri = null

            // Cleanup old debug_sessions to keep storage light
            val rootSessionsDir = File(context.cacheDir, "debug_sessions")
            if (rootSessionsDir.exists()) {
                val sessions = rootSessionsDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList<File>()
                if (sessions.size >= 5) {
                    sessions.take(sessions.size - 4).forEach { it.deleteRecursively() }
                }
            }
        }

        val sessionDir = File(context.cacheDir, "debug_sessions/$activeSessionId").apply { mkdirs() }

        // 1. Get the current play screenshot bitmap safely
        var bitmapCopy = PokerHudService.instance?.getLatestScannerBitmap()
        
        if (bitmapCopy == null) {
            // Framebuffer placeholder in case screen scanning isn't running yet
            bitmapCopy = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                canvas.drawColor(Color.parseColor("#121A24"))
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 32f
                    isAntiAlias = true
                }
                canvas.drawText("Poker Bot Screenshot (Buffer Empty)", 50f, 300f, paint)
                val paintSub = Paint().apply {
                    color = Color.parseColor("#FF00FFCC")
                    textSize = 20f
                    isAntiAlias = true
                }
                canvas.drawText("Start projection scanning to capture real screens.", 50f, 354f, paintSub)
            }
        }

        // Save current screen frame
        val screenFile = File(sessionDir, "screenshot_${captureIndex}.jpg")
        try {
            FileOutputStream(screenFile).use { out ->
                bitmapCopy?.compress(Bitmap.CompressFormat.JPEG, 75, out)
            }
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Failed to save screenshot for diagnostic", e)
        } finally {
            bitmapCopy?.recycle()
        }

        // 2. Write last-minute screenshot-specific logs
        val lastMinuteLogs = getLogsForLastMinute()
        val logFile = File(sessionDir, "logs_${captureIndex}.txt")
        try {
            logFile.writeText(lastMinuteLogs)
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Failed to write diagnostic segment logs", e)
        }

        // 3. Write overall cumulative session logs
        val cumulativeLogs = getSessionLogs(sessionStartTime)
        val cumulativeLogFile = File(sessionDir, "session_logs.txt")
        try {
            cumulativeLogFile.writeText(cumulativeLogs)
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Failed to write cumulative session logs", e)
        }

        // 4. Compress session directory into a temporary ZIP file
        val tempZipFile = File(context.cacheDir, "temp_debug_${activeSessionId}.zip")
        try {
            ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
                val filesToZip = sessionDir.listFiles() ?: emptyArray()
                for (file in filesToZip) {
                    if (file.isFile) {
                        try {
                            zos.putNextEntry(ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } catch (e: Exception) {
                            Log.e("DebugLogManager", "Error zipping file ${file.name}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DebugLogManager", "Error packing active session ZIP", e)
        }

        // 5. Transfer to standard device "Downloads/PokerBotDebug" directory
        if (tempZipFile.exists() && tempZipFile.length() > 0) {
            val resolver = context.contentResolver
            
            // On modern Android, if a file uri was previously created for this same session, 
            // delete the old file to perform a clean overwrite inside "Downloads/PokerBotDebug".
            lastSavedUri?.let { oldUri ->
                try {
                    resolver.delete(oldUri, null, null)
                } catch (ignored: Exception) {}
            }

            val filePattern = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartTime))
            val zipDisplayName = "poker_bot_debug_session_${filePattern}.zip"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zipDisplayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PokerBotDebug")
                }
            }

            val externalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                @Suppress("DEPRECATION")
                Uri.fromFile(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PokerBotDebug"))
            }

            var savedUri: Uri? = null
            try {
                savedUri = resolver.insert(externalUri, contentValues)
                if (savedUri != null) {
                    resolver.openOutputStream(savedUri)?.use { outStream ->
                        FileInputStream(tempZipFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    lastSavedUri = savedUri
                    Log.d("DebugLogManager", "Saved zip to Downloads/PokerBotDebug under: $zipDisplayName")
                }
            } catch (e: Exception) {
                Log.e("DebugLogManager", "Error writing ZIP to device Downloads directory", e)
            } finally {
                tempZipFile.delete()
            }

            // Show responsive feedback Toast to user on main thread
            val toastMsg = if (isMerged) {
                "Снимок №$captureIndex добавлен в пачку!\n(Сохранено в Downloads/PokerBotDebug)"
            } else {
                "Создана новая пачка Снимок №1!\n(Сохранено в Downloads/PokerBotDebug)"
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getLogsForLastMinute(): String {
        val oneMinuteAgo = System.currentTimeMillis() - 60000
        val filtered = try {
            BotLogSharedState.logsHistory.filter { it.timestamp >= oneMinuteAgo }
        } catch (e: Exception) {
            emptyList()
        }
        return buildString {
            appendLine("=== POKER BOT DIAGNOSTIC LOGS (LAST MINUTE) ===")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            for (entry in filtered) {
                val dateStr = sdf.format(Date(entry.timestamp))
                appendLine("[$dateStr] [${entry.tag}] ${entry.message}")
            }
            if (filtered.isEmpty()) {
                appendLine("(No logs recorded in the last minute)")
            }
        }
    }

    private fun getSessionLogs(sessionStart: Long): String {
        val filtered = try {
            BotLogSharedState.logsHistory.filter { it.timestamp >= sessionStart }
        } catch (e: Exception) {
            emptyList()
        }
        return buildString {
            appendLine("=== POKER BOT CUMULATIVE SESSION LOGS ===")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            for (entry in filtered) {
                val dateStr = sdf.format(Date(entry.timestamp))
                appendLine("[$dateStr] [${entry.tag}] ${entry.message}")
            }
            if (filtered.isEmpty()) {
                appendLine("(No cumulative logs recorded in current session)")
            }
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
