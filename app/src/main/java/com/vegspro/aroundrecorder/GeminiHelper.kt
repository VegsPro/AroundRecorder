package com.vegspro.aroundrecorder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import android.util.Base64

object GeminiHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe / summarize an audio file using Google Gemini API.
     * Uses gemini-1.5-flash which supports audio input.
     * User must provide a valid Gemini API key from https://aistudio.google.com/apikey
     */
    fun analyzeAudio(apiKey: String, audioFile: File, prompt: String = "Расшифруй эту аудиозапись подробно. Если есть речь — напиши текст. Если только звуки окружения — опиши, что происходит."): Result<String> {
        return try {
            if (!audioFile.exists() || audioFile.length() == 0L) {
                return Result.failure(Exception("Файл пустой или не найден"))
            }

            val bytes = audioFile.readBytes()
            // Gemini has limits; for long files we take first ~8 MB (roughly 8–10 min of AAC)
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
                put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject()
                            .put("inline_data", JSONObject()
                                .put("mime_type", mime)
                                .put("data", base64)
                            )
                        )
                    )
                ))
                put("generationConfig", JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 4096)
                )
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(Exception("API ошибка ${response.code}: $body"))
                }
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return Result.failure(Exception("Нет ответа от модели: $body"))
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
