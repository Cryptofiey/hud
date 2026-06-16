package com.example

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object PokerAiAutomation {

    private const val TAG = "PokerAiAutomation"

    // OkHttpClient with generous timeouts for Gemini and Firebase
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads the test verification report directly to the configured Firebase Realtime Database.
     * Uses simple REST PUT request to make it fully client-side and lightweight.
     */
    suspend fun uploadTestReportToFirebase(
        context: Context,
        totalTests: Int,
        passedCount: Int,
        failedCount: Int,
        logs: String,
        isSelfHealed: Boolean,
        explanation: String = ""
    ): String = withContext(Dispatchers.IO) {
        val dbUrl = ScannerConfig.firebaseDbUrl.trim()
        if (dbUrl.isEmpty()) {
            Log.w(TAG, "Firebase DB URL is empty. Skipping upload.")
            return@withContext "Skipped (Empty URL)"
        }

        // Clean database URL to ensure there's no trailing slash
        val cleanedUrl = if (dbUrl.endsWith("/")) dbUrl.substring(0, dbUrl.length - 1) else dbUrl
        val reportId = UUID.randomUUID().toString()
        val endpoint = "$cleanedUrl/reports/$reportId.json"

        val jsonObj = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("totalTests", totalTests)
            put("passed", passedCount)
            put("failed", failedCount)
            put("isSelfHealed", isSelfHealed)
            put("explanation", explanation)
            put("templateMseThreshold", ScannerConfig.templateMseThreshold.toDouble())
            put("ocrThreshold", ScannerConfig.ocrThreshold)
            put("logs", logs.take(50000)) // Keep it reasonable
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .put(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val successMsg = "Successfully synced logs to Firebase: /reports/$reportId.json"
                    Log.i(TAG, successMsg)
                    successMsg
                } else {
                    val errorMsg = "Firebase upload failed: HTTP ${response.code} ${response.message}"
                    Log.e(TAG, errorMsg)
                    errorMsg
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Firebase networking error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            errorMsg
        }
    }

    /**
     * Standard Gemini API REST Call
     * Uses direct POST request matching the prototyping instructions (Option B of skill guide)
     */
    suspend fun runAiHealing(context: Context, failedCasesInfo: String): HealingResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "No valid Gemini API key configured in project secrets.")
            return@withContext HealingResult(
                success = false,
                explanation = "Error: Key config missing. Set GEMINI_API_KEY in the Secrets panel."
            )
        }

        val prompt = """
            You are the automated Poker HUD AI Diagnostician.
            We ran a suite of template matching and OCR tests, and got some scanning/detection failures.
            Based on the failed test diagnostics, analyze and calculate corrected/calibrated settings:
            
            FAILURES DIAGNOSTICS:
            $failedCasesInfo
            
            CURRENT SETTINGS:
            - ScannerConfig.templateMseThreshold = ${ScannerConfig.templateMseThreshold}
            - ScannerConfig.ocrThreshold = ${ScannerConfig.ocrThreshold}
            
            Your job is to optimize:
            1. 'templateMseThreshold' (Float, usually between 1000f and 4000f. Higher value makes it more lax/sensitive, lower value makes it more strict/conservative).
            2. 'ocrThreshold' (Int, usually between 150 and 240, for binarization/contrast preprocessing).
            
            Produce optimized settings that resolve these card classification errors.
            Respond strictly and ONLY in a raw valid JSON block conforming to this schema without any markdown formatting wrappers:
            {
              "suggestedMseThreshold": 1500.0,
              "suggestedOcrThreshold": 195,
              "explanation": "Briefly describe your mathematical reasoning for the failure and correction."
            }
        """.trimIndent()

        // Endpoint for gemini-3.5-flash as specified in the skill
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", prompt)
                        }
                        put(partObj)
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errMsg = "HTTP Request to Gemini API failed with code ${response.code}: ${response.body?.string()}"
                    Log.e(TAG, errMsg)
                    return@withContext HealingResult(false, errMsg)
                }

                val bodyStr = response.body?.string() ?: return@withContext HealingResult(false, "Empty response body from Gemini API")
                val responseJson = JSONObject(bodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext HealingResult(false, "No candidates in Gemini response")
                }

                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .optString("text")

                if (text.isNullOrEmpty()) {
                    return@withContext HealingResult(false, "No text extracted from content candidate")
                }

                // Parse the inner JSON from response block
                val cleanedJsonStr = cleanJsonText(text)
                val parsedResult = JSONObject(cleanedJsonStr)

                val suggestedMse = parsedResult.getDouble("suggestedMseThreshold").toFloat()
                val suggestedOcr = parsedResult.getInt("suggestedOcrThreshold")
                val explanation = parsedResult.optString("explanation", "Adjusted dynamically by Gemini Auto-Healing.")

                // Locally apply the self-heal corrections to global configuration!
                ScannerConfig.templateMseThreshold = suggestedMse
                ScannerConfig.ocrThreshold = suggestedOcr
                ScannerConfig.selfHealedCount++

                HealingResult(
                    success = true,
                    explanation = explanation,
                    suggestedMseThreshold = suggestedMse,
                    suggestedOcrThreshold = suggestedOcr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI Auto-Healing execution failed", e)
            HealingResult(false, "Exception: ${e.message}")
        }
    }

    private fun cleanJsonText(rawText: String): String {
        var clean = rawText.trim()
        if (clean.startsWith("```json")) {
            clean = clean.substringAfter("```json")
        } else if (clean.startsWith("```")) {
            clean = clean.substringAfter("```")
        }
        if (clean.endsWith("```")) {
            clean = clean.substringBeforeLast("```")
        }
        return clean.trim()
    }

    data class HealingResult(
        val success: Boolean,
        val explanation: String,
        val suggestedMseThreshold: Float = 0f,
        val suggestedOcrThreshold: Int = 0
    )
}
