package com.example

import kotlinx.coroutines.flow.MutableStateFlow
<<<<<<< HEAD
=======
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
>>>>>>> origin/main

data class LogEntry(val timestamp: Long, val tag: String, val message: String)

object BotLogSharedState {
<<<<<<< HEAD
    val isBotLogWidgetRunning = MutableStateFlow(false)
    val isLogServerRunning = MutableStateFlow(false)
    val widgetRect = MutableStateFlow<android.graphics.Rect?>(null)
    
=======
    val isLogServerRunning = MutableStateFlow(false)
    val widgetRect = MutableStateFlow<android.graphics.Rect?>(null)
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val firebaseDbUrl = BuildConfig.FIREBASE_DB_URL

>>>>>>> origin/main
    // Timed log history for diagnostics
    val logsHistory = java.util.Collections.synchronizedList(mutableListOf<LogEntry>())
    
    // Log content for different slots
    val logL1 = MutableStateFlow("")
    val logL2 = MutableStateFlow("")
    val logL3 = MutableStateFlow("")
    
    val logM1 = MutableStateFlow("")
    val logM2 = MutableStateFlow("")
    val logM3 = MutableStateFlow("")
    val logM4 = MutableStateFlow("")
    val logM5 = MutableStateFlow("")
    
    val logL4 = MutableStateFlow("")
    val logBot = MutableStateFlow("") // L5
<<<<<<< HEAD
=======

    private fun pushToFirebase(tag: String, msg: String, timestamp: Long) {
        if (firebaseDbUrl.isEmpty() || firebaseDbUrl.contains("your-project-id")) return
        scope.launch {
            try {
                val url = URL("$firebaseDbUrl/logs.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val json = JSONObject()
                json.put("timestamp", timestamp)
                json.put("tag", tag)
                json.put("message", msg)
                
                conn.outputStream.use { os ->
                    val input = json.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                
                if (conn.responseCode !in 200..299) {
                    Log.e("BotLogSharedState", "Firebase push failed: ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("BotLogSharedState", "Error pushing log: ${e.message}")
            }
        }
    }
>>>>>>> origin/main
    
    private fun addHistory(tag: String, msg: String) {
        val now = System.currentTimeMillis()
        logsHistory.add(LogEntry(now, tag, msg))
<<<<<<< HEAD
        // Cleanup logs older than 15 minutes to prevent memory leaks
        val cutoff = now - 15 * 60 * 1000
        try {
            logsHistory.removeAll { it.timestamp < cutoff }
        } catch (ignored: Exception) {}
        
        if (logsHistory.size > 5000) {
            try {
                logsHistory.subList(0, logsHistory.size - 4500).clear()
=======
        pushToFirebase(tag, msg, now)
        
        // Keep full session logs in memory up to 500000 lines to satisfy user request for complete export
        if (logsHistory.size > 500000) {
            try {
                logsHistory.subList(0, logsHistory.size - 450000).clear()
>>>>>>> origin/main
            } catch (ignored: Exception) {}
        }
    }
    
    // Logs appending helper
    fun appendLogL(level: Int, msg: String) {
        addHistory("L$level", msg)
        when (level) {
            1 -> logL1.value = "${logL1.value}\n$msg"
            2 -> logL2.value = "${logL2.value}\n$msg"
            3 -> logL3.value = "${logL3.value}\n$msg"
        }
    }
    
    fun appendLogM(level: Int, msg: String) {
        addHistory("M$level", msg)
        when (level) {
            1 -> logM1.value = "${logM1.value}\n$msg"
            2 -> logM2.value = "${logM2.value}\n$msg"
            3 -> logM3.value = "${logM3.value}\n$msg"
            4 -> logM4.value = "${logM4.value}\n$msg"
            5 -> logM5.value = "${logM5.value}\n$msg"
        }
    }
    
    fun appendLogL4(msg: String) {
        addHistory("L4", msg)
        logL4.value = "${logL4.value}\n$msg"
    }
    
    fun appendLogBot(msg: String) {
        addHistory("L5", msg)
        logBot.value = "${logBot.value}\n$msg"
    }
}
