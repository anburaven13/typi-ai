package com.typiai.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.typiai.domain.GeminiResult
import com.typiai.domain.TriggerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiHelper(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiHelper"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        // Model priority list - tries from newest to fallback
        private val MODEL_PRIORITY = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-preview-05-20",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest"
        )
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_INPUT_LENGTH = 4000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            // Never log API key
            Log.d(TAG, "Request to: ${request.url.encodedPath}")
            chain.proceed(request)
        }
        .build()

    private val gson = Gson()
    private var currentModel: String = MODEL_PRIORITY.first()

    suspend fun processText(text: String, command: TriggerCommand): GeminiResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext GeminiResult.Error(
                    "No API key configured. Please add your Gemini API key in Settings.",
                    isRetryable = false
                )
            }

            val inputText = text.take(MAX_INPUT_LENGTH)
            val startTime = System.currentTimeMillis()

            var lastError: GeminiResult.Error? = null
            for (attempt in 0 until MAX_RETRIES) {
                val result = tryRequest(inputText, attempt, command)
                when (result) {
                    is GeminiResult.Success -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        return@withContext GeminiResult.Success(result.text, elapsed)
                    }
                    is GeminiResult.Error -> {
                        lastError = result
                        if (!result.isRetryable) return@withContext result
                        if (attempt < MAX_RETRIES - 1) {
                            val backoffDelay = BASE_DELAY_MS * (1L shl attempt)
                            Log.w(TAG, "Retry attempt ${attempt + 1}, waiting ${backoffDelay}ms")
                            delay(backoffDelay)
                        }
                    }
                    else -> {}
                }
            }
            lastError ?: GeminiResult.Error("Unknown error after retries")
        }

    private suspend fun tryRequest(prompt: String, attempt: Int, command: TriggerCommand): GeminiResult {
        return try {
            val requestBody = buildRequestBody(prompt, command)
            val url = "$BASE_URL/models/$currentModel:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parseSuccessResponse(response.body?.string() ?: "")
                    400 -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.e(TAG, "Bad request: $errorBody")
                        GeminiResult.Error("Invalid request. Check your API key.", isRetryable = false)
                    }
                    401, 403 -> {
                        GeminiResult.Error(
                            "Invalid API key. Please update your Gemini API key.",
                            isRetryable = false
                        )
                    }
                    404 -> {
                        // Model not found - try fallback
                        val fallbackModel = getFallbackModel()
                        if (fallbackModel != null) {
                            Log.w(TAG, "Model $currentModel not found, trying $fallbackModel")
                            currentModel = fallbackModel
                            GeminiResult.Error("Switching to fallback model, please retry", isRetryable = true)
                        } else {
                            GeminiResult.Error("No available Gemini model found.", isRetryable = false)
                        }
                    }
                    429 -> {
                        Log.w(TAG, "Rate limited (429), will retry with backoff")
                        GeminiResult.Error("Rate limit exceeded. Retrying...", isRetryable = true)
                    }
                    500, 503 -> {
                        Log.w(TAG, "Server error ${response.code}, will retry")
                        GeminiResult.Error("Gemini service temporarily unavailable.", isRetryable = true)
                    }
                    else -> {
                        GeminiResult.Error(
                            "Request failed with code ${response.code}",
                            isRetryable = response.code >= 500
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            GeminiResult.Error("Network error: ${e.message ?: "Connection failed"}", isRetryable = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            GeminiResult.Error("Unexpected error occurred", isRetryable = false)
        }
    }

    private fun buildRequestBody(userText: String, command: TriggerCommand): String {
        // System instruction sent separately; user content = command prompt + user text
        val systemInstruction = command.systemInstruction
        val userContent = "${command.prompt}$userText"

        val contents = if (systemInstruction.isNotBlank()) {
            listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to userContent))
                )
            )
        } else {
            listOf(
                mapOf(
                    "parts" to listOf(mapOf("text" to userContent))
                )
            )
        }

        val body = mutableMapOf<String, Any>(
            "contents" to contents,
            "generationConfig" to mapOf(
                "temperature" to command.temperature,
                "topK" to 40,
                "topP" to 0.95,
                "maxOutputTokens" to command.maxTokens,
                "candidateCount" to 1
            ),
            "safetySettings" to listOf(
                mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_ONLY_HIGH"),
                mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_ONLY_HIGH"),
                mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_ONLY_HIGH"),
                mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_ONLY_HIGH")
            )
        )

        if (systemInstruction.isNotBlank()) {
            body["systemInstruction"] = mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            )
        }

        return gson.toJson(body)
    }

    private fun parseSuccessResponse(responseBody: String): GeminiResult {
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    val text = parts[0].asJsonObject.get("text")?.asString ?: ""
                    GeminiResult.Success(text.trim())
                } else {
                    GeminiResult.Error("Empty response from Gemini")
                }
            } else {
                // Check for safety blocks
                val promptFeedback = json.getAsJsonObject("promptFeedback")
                val blockReason = promptFeedback?.get("blockReason")?.asString
                if (blockReason != null) {
                    GeminiResult.Error("Content blocked: $blockReason", isRetryable = false)
                } else {
                    GeminiResult.Error("No content generated")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            GeminiResult.Error("Failed to parse AI response")
        }
    }

    private fun getFallbackModel(): String? {
        val currentIndex = MODEL_PRIORITY.indexOf(currentModel)
        return if (currentIndex < MODEL_PRIORITY.size - 1) {
            MODEL_PRIORITY[currentIndex + 1]
        } else null
    }

    fun getCurrentModel(): String = currentModel

    suspend fun testConnection(): GeminiResult {
        return processText("Hello", TriggerCommand.FIX)
    }
}
