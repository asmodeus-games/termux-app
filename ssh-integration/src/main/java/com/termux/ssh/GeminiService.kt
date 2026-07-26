package com.termux.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

suspend fun getAiResponse(
        prompt: String,
        systemInstruction: String? = null,
        apiKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "API_KEY_MISSING"
        }

        try {
            val requestJson = JSONObject()
            
            // Build contents array
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", prompt)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            requestJson.put("contents", contentsArray)

            // Build system instruction if provided
            if (systemInstruction != null) {
                val sysInstructionObj = JSONObject()
                val sysPartsArray = JSONArray()
                val sysPartObj = JSONObject()
                sysPartObj.put("text", systemInstruction)
                sysPartsArray.put(sysPartObj)
                sysInstructionObj.put("parts", sysPartsArray)
                requestJson.put("systemInstruction", sysInstructionObj)
            }

            // Build generation config
            val generationConfig = JSONObject()
            generationConfig.put("temperature", 0.3) // Lower temperature for accurate technical answers
            requestJson.put("generationConfig", generationConfig)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Unsuccessful response from Gemini API: ${response.code} - $bodyStr")
                    return@withContext "Error: Gemini API returned status code ${response.code}"
                }

                if (bodyStr.isNullOrBlank()) {
                    return@withContext "Error: Gemini API returned an empty response."
                }

                val responseJson = JSONObject(bodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text", "No text generated.")
                    }
                }
                return@withContext "Error: Unable to parse response candidates."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini API call", e)
            return@withContext "Error: ${e.localizedMessage ?: "Unknown network error"}"
        }
    }

    /**
     * Extracts commands enclosed in markdown code blocks from the generated AI response text.
     */
    fun extractCommands(text: String): List<String> {
        val commands = mutableListOf<String>()
        val lines = text.lines()
        var inCodeBlock = false
        val currentBlock = StringBuilder()
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("```")) {
                if (inCodeBlock) {
                    val code = currentBlock.toString().trim()
                    if (code.isNotEmpty()) {
                        commands.add(code)
                    }
                    currentBlock.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
            } else if (inCodeBlock) {
                currentBlock.append(line).append("\n")
            }
        }
        // Fallback: If no code block found, and the response is very short/looks like a command, use it directly
        if (commands.isEmpty() && text.trim().isNotEmpty() && text.length < 120 && !text.contains("\n")) {
            commands.add(text.trim())
        }
        return commands
    }
}