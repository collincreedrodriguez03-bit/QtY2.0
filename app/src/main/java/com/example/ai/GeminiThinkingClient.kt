package com.example.ai

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiThinkingClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun auditTradingEngine(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            val field = Class.forName("com.example.BuildConfig").getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Throwable) {
            ""
        }
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Gemini API Key is missing or not configured in AI Studio Secrets. Please configure GEMINI_API_KEY to enable High Thinking quantitative audit."
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro-preview:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "You are QtY High Thinking Quantitative Auditor. Perform rigorous OOS validation, feature provenance check, calibration analysis, and mathematical verification for BTC trading strategies."))
                })
            })
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().put("thinkingLevel", "HIGH"))
                put("temperature", 0.7)
            })
        }

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "API Error: ${response.code} - ${response.message}"
                }
                val responseString = response.body?.string() ?: return@withContext "Empty response"
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text", "No text found in response")
                    }
                }
                return@withContext "No candidates found in Gemini response."
            }
        } catch (e: Exception) {
            return@withContext "Error executing High Thinking audit: ${e.localizedMessage}"
        }
    }
}
