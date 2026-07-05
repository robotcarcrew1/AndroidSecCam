package com.securitycam.app.alert

import android.content.Context
import android.util.Base64
import com.securitycam.app.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional: asks Gemini's free-tier API to describe a detection snapshot in one short
 * sentence. Best-effort / fail-open — callers should proceed without a description if
 * this fails or times out.
 */
class GeminiDescriber(context: Context) {
    private val prefs = Prefs(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val model = "gemini-2.5-flash-lite"

    suspend fun testConnection(): Result<String> =
        describeInternal(sampleImageBytes(), "Reply with just the word 'ok'.")

    /** Returns a short natural-language description of what's in the image, if available. */
    suspend fun describe(imageFile: File, hint: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (imageFile.exists()) describeInternal(imageFile.readBytes(), hint)
            else Result.failure(IllegalStateException("No image"))
        }

    private suspend fun describeInternal(jpegBytes: ByteArray, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.geminiApiKey.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Gemini API key not set"))
                }
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("parts", JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(JSONObject().put(
                                    "inline_data",
                                    JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64)
                                    }
                                ))
                            )
                        }
                    ))
                }
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent" +
                    "?key=${prefs.geminiApiKey}"
                val request = Request.Builder()
                    .url(url)
                    .post(requestJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("Gemini returned ${resp.code}: $bodyStr"))
                    }
                    val text = JSONObject(bodyStr)
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0)
                        .getString("text")
                        .trim()
                    Result.success(text)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** A tiny 1x1 JPEG used only for the settings "test connection" button. */
    private fun sampleImageBytes(): ByteArray = Base64.decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkI" +
            "CQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQ" +
            "EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAABAAEDASIA" +
            "AhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEB" +
            "AQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k=",
        Base64.NO_WRAP
    )
}
