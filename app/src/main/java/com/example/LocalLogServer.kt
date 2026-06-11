package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object LocalLogServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private const val PORT = 8080

    private var context: android.content.Context? = null

    fun start(ctx: android.content.Context) {
        if (isRunning) return
        this.context = ctx.applicationContext
        isRunning = true
        thread(start = true, name = "LocalLogServerThread") {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d("LocalLogServer", "Server started on port $PORT")
                while (isRunning && !serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("LocalLogServer", "Server error", e)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("LocalLogServer", "Error closing server", e)
        }
    }

    private fun handleClient(client: Socket) {
        thread(start = true) {
            try {
                val input = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = input.readLine() ?: return@thread
                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0]
                    val path = parts[1]

                    if (method == "GET") {
                        if (path == "/") {
                            serveHtml(output)
                        } else if (path == "/logs") {
                            serveJson(output)
                        } else if (path == "/export") {
                            serveExport(output)
                        } else {
                            serveNotFound(output)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore client handling errors
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {}
            }
        }
    }

    private fun serveHtml(output: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Poker Bot Dashboard</title>
                <meta charset="utf-8">
                <style>
                    body { background-color: #0f172a; color: #f8fafc; font-family: monospace; margin: 0; padding: 20px; }
                    h1 { color: #38bdf8; text-align: center; margin-bottom: 20px; }
                    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }
                    .card { background-color: #1e293b; padding: 15px; border-radius: 8px; border: 1px solid #334155; }
                    h2 { margin-top: 0; color: #818cf8; font-size: 16px; border-bottom: 1px solid #334155; padding-bottom: 5px; }
                    pre { margin: 0; white-space: pre-wrap; font-size: 12px; color: #cbd5e1; height: 200px; overflow-y: auto; }
                    .single-card { background-color: #1e293b; padding: 15px; border-radius: 8px; border: 1px solid #334155; margin-bottom: 20px; }
                    
                    /* Custom Scrollbar */
                    ::-webkit-scrollbar { width: 8px; }
                    ::-webkit-scrollbar-track { background: #0f172a; }
                    ::-webkit-scrollbar-thumb { background: #475569; border-radius: 4px; }
                    ::-webkit-scrollbar-thumb:hover { background: #64748b; }
                </style>
            </head>
            <body>
                <h1>🤖 Poker Bot Live Dashboard</h1>
                
                <div class="grid">
                    <div class="card">
                        <select id="lSelect" style="background:#0f172a; color:#f8fafc; border:1px solid #334155; padding:5px; margin-bottom:10px; border-radius:4px;">
                            <option value="l1">L1: Bare Math</option>
                            <option value="l2">L2: Adjust Math</option>
                            <option value="l3">L3: Persona Match</option>
                        </select>
                        <pre id="lBox">Loading...</pre>
                    </div>
                    <div class="card">
                        <select id="mSelect" style="background:#0f172a; color:#f8fafc; border:1px solid #334155; padding:5px; margin-bottom:10px; border-radius:4px;">
                            <option value="m1">M1: Pattern 1</option>
                            <option value="m2">M2: Pattern 2</option>
                            <option value="m3">M3: Pattern 3</option>
                            <option value="m4">M4: Pattern 4</option>
                            <option value="m5">M5: Pattern 5</option>
                        </select>
                        <pre id="mBox">Loading...</pre>
                    </div>
                </div>
                
                <div class="single-card" style="border-color: #f472b6;">
                    <h2 style="color: #f472b6;">L4: Strategy Synthesizer & Bot Log</h2>
                    <pre id="l4Box" style="height: 150px; color: #e2e8f0; font-weight: bold;">Loading...</pre>
                </div>
                
                <div class="single-card" style="border-color: #34d399;">
                    <h2 style="color: #34d399;">BOT (L5): Execution Engine</h2>
                    <pre id="botBox" style="height: 150px; color: #a7f3d0;">Loading...</pre>
                </div>

                <div style="text-align: center; margin-top: 20px;">
                    <a href="/export" style="background:#3b82f6; color:#fff; padding:10px 20px; text-decoration:none; border-radius:4px; font-weight:bold; border:none; cursor:pointer;">
                        📥 Download ZIP (Session Logs, Logcat & Screenshots)
                    </a>
                    <p style="color: #94a3b8; font-size: 11px; margin-top: 10px;">
                        В ZIP-архив входят полные логи со старта программы (session_logs.txt) и системный лог (logcat.txt).<br>
                        Не закрывайте программу полностью перед скачиванием!
                    </p>
                </div>

                <script>
                    let lastData = {};
                    
                    async function fetchLogs() {
                        try {
                            const res = await fetch('/logs');
                            const data = await res.json();
                            lastData = data;
                            updateUI();
                        } catch (e) {
                            console.error('Fetch error', e);
                        }
                    }
                    
                    function updateUI() {
                        const lKey = document.getElementById('lSelect').value;
                        const lBox = document.getElementById('lBox');
                        if (lBox.textContent !== lastData[lKey]) {
                            lBox.textContent = lastData[lKey] || '';
                            lBox.scrollTop = lBox.scrollHeight;
                        }
                        
                        const mKey = document.getElementById('mSelect').value;
                        const mBox = document.getElementById('mBox');
                        if (mBox.textContent !== lastData[mKey]) {
                            mBox.textContent = lastData[mKey] || '';
                            mBox.scrollTop = mBox.scrollHeight;
                        }
                        
                        const l4Box = document.getElementById('l4Box');
                        if (l4Box.textContent !== lastData.l4) {
                            l4Box.textContent = lastData.l4 || '';
                            l4Box.scrollTop = l4Box.scrollHeight;
                        }
                        
                        const botBox = document.getElementById('botBox');
                        if (botBox.textContent !== lastData.bot) {
                            botBox.textContent = lastData.bot || '';
                            botBox.scrollTop = botBox.scrollHeight;
                        }
                    }

                    document.getElementById('lSelect').addEventListener('change', updateUI);
                    document.getElementById('mSelect').addEventListener('change', updateUI);

                    setInterval(fetchLogs, 1000);
                    fetchLogs();
                </script>
            </body>
            </html>
        """.trimIndent()
        
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${html.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                html
        output.write(response.toByteArray())
        output.flush()
    }

    private fun serveExport(output: OutputStream) {
        val ctx = context ?: return serveNotFound(output)
        kotlinx.coroutines.runBlocking {
            val file = DebugLogManager.createExportZip(ctx)
            if (file != null && file.exists()) {
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Content-Disposition: attachment; filename=\"poker_bot_debug.zip\"\r\n" +
                        "Content-Length: ${file.length()}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(responseHeaders.toByteArray())
                file.inputStream().use { it.copyTo(output) }
                output.flush()
            } else {
                serveNotFound(output)
            }
        }
    }

    private fun serveJson(output: OutputStream) {
        val json = JSONObject().apply {
            put("l1", BotLogSharedState.logL1.value)
            put("l2", BotLogSharedState.logL2.value)
            put("l3", BotLogSharedState.logL3.value)
            put("m1", BotLogSharedState.logM1.value)
            put("m2", BotLogSharedState.logM2.value)
            put("m3", BotLogSharedState.logM3.value)
            put("m4", BotLogSharedState.logM4.value)
            put("m5", BotLogSharedState.logM5.value)
            put("l4", BotLogSharedState.logL4.value)
            put("bot", BotLogSharedState.logBot.value)
        }
        
        val jsonString = json.toString()
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${jsonString.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                jsonString
        output.write(response.toByteArray())
        output.flush()
    }

    private fun serveNotFound(output: OutputStream) {
        val msg = "404 Not Found"
        val response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: ${msg.length}\r\n" +
                "Connection: close\r\n\r\n" +
                msg
        output.write(response.toByteArray())
        output.flush()
    }
}
