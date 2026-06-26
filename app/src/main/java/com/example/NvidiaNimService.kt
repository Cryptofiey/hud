package com.example

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object NvidiaNimService {
    private const val TAG = "NvidiaNimService"
    private const val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"

    val AVAILABLE_MODELS = listOf(
        "meta/llama-3.2-11b-vision-instruct",
        "meta/llama-3.2-90b-vision-instruct",
        "nvidia/neva-22b",
        "google/paligemma-3b-pt-896"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Preferences Keys
    private const val PREFS_NAME = "nvidia_nim_prefs"
    private const val KEY_MODEL = "selected_model"
    private const val KEY_CUSTOM_KEY = "custom_api_key"

    fun getSelectedModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL, AVAILABLE_MODELS.first()) ?: AVAILABLE_MODELS.first()
    }

    fun setSelectedModel(context: Context, model: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun getCustomApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_KEY, "") ?: ""
    }

    fun setCustomApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_KEY, key).apply()
    }

    fun getActiveApiKey(context: Context): String {
        val custom = getCustomApiKey(context)
        if (custom.isNotEmpty()) return custom
        // Fallback to BuildConfig field generated from .env file
<<<<<<< HEAD
        val buildConfigKey = try {
=======
        return try {
            val field = BuildConfig::class.java.getField("N_VID_I_A_N_I_M_A_P_I_K_E_Y") // Or direct
>>>>>>> origin/main
            BuildConfig.NVIDIA_NIM_API_KEY
        } catch (e: Exception) {
            ""
        }
<<<<<<< HEAD
        if (buildConfigKey.isNotEmpty() && buildConfigKey != "YOUR_NVIDIA_NIM_API_KEY_HERE") {
            return buildConfigKey
        }
        // Default developer API key
        return "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"
=======
>>>>>>> origin/main
    }

    // JSON Input/Output Schemas as strict definitions for the AI model
    const val SCHEMA_ACTIVE_TABLE = """
    {
      "pageType": "ACTIVE_TABLE",
      "heroCards": ["Ah", "Kd"], // array of 2 cards (null if unknown) or empty
      "board": ["Th", "Qs", "Jh"], // array of up to 5 community cards on board
      "potSize": 1250.0, // float or null
      "dealerPosition": "BTN", // position of dealer button (e.g. SB, BB, UTG, MP, CO, BTN)
      "heroTurn": true, // is it hero's turn to act?
      "heroStack": 5000.0, // float or null
      "heroBet": 150.0, // float or null
      "smallBlind": 10.0, // float or null
      "bigBlind": 20.0, // float or null
      "tournamentStage": "EARLY", // "EARLY", "MIDDLE", "LATE" or null
      "isBbDisplay": false, // true if balances shown as BB instead of chips
      "availableActions": ["Fold", "Check", "Call", "Raise", "All-in"], // active button options
      "opponents": [
        {
          "nickname": "Player1",
          "stackSize": 3200.0,
          "betSize": 200.0,
          "isActive": true,
          "isDealer": false,
          "positionName": "CO"
        }
      ]
    }
    """

    const val SCHEMA_PLAYER_PROFILE = """
    {
      "pageType": "PLAYER_PROFILE",
      "nickname": "JohnDoe",
      "stats": {
        "histVpip": 24.5,
        "histPfr": 18.2,
        "hist3Bet": 8.1,
        "histFoldTo3Bet": 55.0,
        "histCBet": 62.0,
        "histFoldToCBet": 45.0,
        "histSteal": 32.0,
        "histCheckRaise": 12.5,
        "histWtsd": 28.0,
        "histWsd": 52.0
      }
    }
    """

    const val SCHEMA_UNKNOWN_SCREEN = """
    {
      "pageType": "UNKNOWN_SCREEN",
      "description": "Short description of what is seen on screen",
      "suggestedAction": "LOBBY_CLOSE", // e.g. "LOBBY_CLOSE", "CLICK_OK", "WAIT"
      "clickTargetXPercent": 45.5, // screen percentage coordinate to click if needed
      "clickTargetYPercent": 72.3
    }
    """

    /**
     * Sends a bitmap frame to Nvidia NIM API and returns the parsed structured JSON response.
     */
    suspend fun analyzeScreen(context: Context, bitmap: Bitmap, forceModel: String? = null): NimResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = getActiveApiKey(context)
        if (apiKey.isEmpty() || apiKey == "YOUR_NVIDIA_NIM_API_KEY_HERE") {
            return@withContext NimResult.Error("API Key is missing or default. Please enter your NVIDIA NIM API Key.", 0)
        }

        val model = forceModel ?: getSelectedModel(context)

        // Compress and encode the bitmap to Base64
        val compressedBase64 = try {
            val outputStream = ByteArrayOutputStream()
            // Compress heavily to ensure super fast upload speed and stay within limits
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return@withContext NimResult.Error("Failed to encode image: ${e.message}", 0)
        }

        val systemPrompt = """
        You are an expert high-speed Poker Table Vision API. 
        Analyze the full-screen screenshot of an Android poker app (e.g. CoinPoker) and extract all relevant data.
        You must output a single JSON object matching one of the following schemas based on the detected screen:
        
        1. If it is an active poker table, output:
        $SCHEMA_ACTIVE_TABLE
        
        2. If it is an opponent's statistical profile overlay screen, output:
        $SCHEMA_PLAYER_PROFILE
        
        3. If it is a lobby, menu, error popup, password/pin entry, or any unknown transition screen, output:
        $SCHEMA_UNKNOWN_SCREEN
        
        Make sure you detect cards, suits (h=hearts, d=diamonds, c=clubs, s=spades), and values perfectly.
        Always return valid JSON. Do not include markdown code block formatting. Only output raw JSON.
        """.trimIndent()

        val jsonPayload = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.1)
            addProperty("max_tokens", 800)
            
            // Build OpenAI compatible message structure
            val messages = com.google.gson.JsonArray()
            val userMsg = JsonObject()
            userMsg.addProperty("role", "user")
            
            val contentArray = com.google.gson.JsonArray()
            
            val textContent = JsonObject()
            textContent.addProperty("type", "text")
            textContent.addProperty("text", systemPrompt)
            contentArray.add(textContent)
            
            val imageContent = JsonObject()
            imageContent.addProperty("type", "image_url")
            val imageUrlObj = JsonObject()
            imageUrlObj.addProperty("url", "data:image/jpeg;base64,$compressedBase64")
            imageContent.add("image_url", imageUrlObj)
            contentArray.add(imageContent)
            
            userMsg.add("content", contentArray)
            messages.add(userMsg)
            
            add("messages", messages)
            
            // Add JSON response format constraint
            val respFormat = JsonObject()
            respFormat.addProperty("type", "json_object")
            add("response_format", respFormat)
        }

        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonPayload.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed with code ${response.code}: $errBody")
                    return@withContext NimResult.Error("API Error ${response.code}: ${response.message}\n$errBody", duration)
                }

                val bodyStr = response.body?.string() ?: ""
                val responseJson = gson.fromJson(bodyStr, JsonObject::class.java)
                val choices = responseJson.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    return@withContext NimResult.Error("Empty choices returned from NIM API.", duration)
                }

                val textResponse = choices[0].asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString.trim()

                Log.d(TAG, "NIM Response: $textResponse")
                return@withContext NimResult.Success(textResponse, duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Nvidia NIM call", e)
            val duration = System.currentTimeMillis() - startTime
            return@withContext NimResult.Error("Connection Error: ${e.message}", duration)
        }
    }

    /**
     * Runs a speed and accuracy test of a specific model on a mock table layout.
     */
    suspend fun testModelSpeed(context: Context, model: String): SpeedTestResult {
        // Create a mock bitmap to test with
        val width = 1080
        val height = 1920
        val testBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(testBitmap)
        val paint = android.graphics.Paint()
        
        // Draw some visual poker indicators to test model's recognition
        paint.color = android.graphics.Color.DKGRAY
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        paint.color = android.graphics.Color.RED
        paint.textSize = 60f
        canvas.drawText("NLH - 50/100 (LEVEL 5)", 200f, 200f, paint)
        canvas.drawText("POT: 1500", 200f, 400f, paint)
        canvas.drawText("Hero: Ah Kd", 200f, 1500f, paint)
        canvas.drawText("Board: Th Qs Jh", 200f, 800f, paint)

        val result = analyzeScreen(context, testBitmap, forceModel = model)
        testBitmap.recycle()

        return when (result) {
            is NimResult.Success -> {
                SpeedTestResult(
                    success = true,
                    latencyMs = result.latencyMs,
                    response = result.jsonResponse,
                    error = null
                )
            }
            is NimResult.Error -> {
                SpeedTestResult(
                    success = false,
                    latencyMs = result.latencyMs,
                    response = null,
                    error = result.message
                )
            }
        }
    }
}

sealed class NimResult {
    data class Success(val jsonResponse: String, val latencyMs: Long) : NimResult()
    data class Error(val message: String, val latencyMs: Long) : NimResult()
}

data class SpeedTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val response: String?,
    val error: String?
)
