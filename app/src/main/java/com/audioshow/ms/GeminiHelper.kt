package com.audioshow.ms

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GeminiHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /**
     * Supports both classic AIzaSy… keys and new AQ.… authorization keys.
     * Tries:
     *  1) generativelanguage.googleapis.com + x-goog-api-key header
     *  2) same host + ?key= query (legacy)
     *  3) Vertex Express: aiplatform.googleapis.com + ?key=
     */
    fun analyzeAudio(
        apiKey: String,
        audioFile: File,
        prompt: String = "Расшифруй эту аудиозапись подробно. Если есть речь — напиши текст. Если только звуки окружения — опиши, что происходит."
    ): Result<String> {
        return try {
            if (!audioFile.exists() || audioFile.length() == 0L) {
                return Result.failure(Exception("Файл пустой или не найден"))
            }

            val key = apiKey.trim()
            val bytes = audioFile.readBytes()
            val maxBytes = 8 * 1024 * 1024
            val data = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)

            val mime = when {
                audioFile.name.endsWith(".m4a", true) || audioFile.name.endsWith(".mp4", true) -> "audio/mp4"
                audioFile.name.endsWith(".wav", true) -> "audio/wav"
                audioFile.name.endsWith(".mp3", true) -> "audio/mpeg"
                else -> "audio/mp4"
            }

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", mime)
                            .put("data", base64)
                        ))
                    )
                ))
                put("generationConfig", JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 4096)
                )
            }.toString()

            val models = listOf(
                "gemini-2.0-flash",
                "gemini-1.5-flash",
                "gemini-1.5-flash-latest",
                "gemini-2.5-flash"
            )

            val errors = mutableListOf<String>()

            // Strategy A: AI Studio + x-goog-api-key header (recommended for AQ keys)
            for (model in models) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val result = post(url, bodyJson, headerKey = key)
                if (result.isSuccess) return result
                errors.add("[$model header] ${result.exceptionOrNull()?.message?.take(120)}")
            }

            // Strategy B: AI Studio + ?key= query
            for (model in models.take(2)) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
                val result = post(url, bodyJson, headerKey = null)
                if (result.isSuccess) return result
                errors.add("[$model query] ${result.exceptionOrNull()?.message?.take(120)}")
            }

            // Strategy C: Vertex Express (AQ keys often belong here)
            for (model in listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-2.5-flash")) {
                val url = "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent?key=$key"
                val result = post(url, bodyJson, headerKey = null)
                if (result.isSuccess) return result
                errors.add("[vertex $model] ${result.exceptionOrNull()?.message?.take(120)}")
            }

            Result.failure(Exception(errors.firstOrNull() ?: "Все способы авторизации не сработали"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun post(url: String, bodyJson: String, headerKey: String?): Result<String> {
        return try {
            val builder = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json")
            if (headerKey != null) {
                builder.header("x-goog-api-key", headerKey)
            }
            val request = builder.build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val msg = try {
                        val j = JSONObject(body)
                        val err = j.optJSONObject("error")
                        val code = err?.optInt("code") ?: response.code
                        val message = err?.optString("message") ?: body.take(200)
                        val reason = err?.optJSONArray("details")
                            ?.optJSONObject(0)
                            ?.optString("reason")
                        if (reason != null) "API $code ($reason): $message" else "API $code: $message"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: ${body.take(200)}"
                    }
                    return Result.failure(Exception(msg))
                }
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val feedback = json.optJSONObject("promptFeedback")?.toString()
                    return Result.failure(Exception("Нет ответа модели${if (feedback != null) ": $feedback" else ""}"))
                }
                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
