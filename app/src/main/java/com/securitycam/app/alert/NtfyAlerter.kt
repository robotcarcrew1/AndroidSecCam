package com.securitycam.app.alert

import android.content.Context
import com.securitycam.app.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Sends push notifications through ntfy.sh (or a self-hosted ntfy server). */
class NtfyAlerter(context: Context) {
    private val prefs = Prefs(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendTest(): Result<String> =
        send(title = "SecurityCam test", message = "This is a test notification.", imageFile = null)

    suspend fun sendDetection(title: String, message: String, imageFile: File?): Result<String> =
        send(title, message, imageFile)

    private suspend fun send(title: String, message: String, imageFile: File?): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.ntfyTopic.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("ntfy topic not set"))
                }
                val url = prefs.ntfyServer.trimEnd('/') + "/" + prefs.ntfyTopic

                val request = if (imageFile != null && imageFile.exists()) {
                    val body = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    // HTTP header values can't contain raw newlines; ntfy's documented
                    // convention is to escape them as literal "\n" and it un-escapes them
                    // back into line breaks when rendering the notification.
                    val headerSafeMessage = message.trim().replace("\n", "\\n")
                    Request.Builder()
                        .url(url)
                        .header("Title", title)
                        .header("Filename", imageFile.name)
                        .header("Message", headerSafeMessage)
                        .put(body)
                        .build()
                } else {
                    Request.Builder()
                        .url(url)
                        .header("Title", title)
                        .post(message.toRequestBody())
                        .build()
                }
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) Result.success("Notification sent")
                    else Result.failure(IllegalStateException("ntfy returned ${resp.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
