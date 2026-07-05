package com.securitycam.app.alert

import android.content.Context
import com.securitycam.app.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/** Sends detection alert emails via SMTP (e.g. Gmail + app password), with a JPEG attached. */
class EmailAlerter(context: Context) {
    private val prefs = Prefs(context)

    suspend fun sendTest(): Result<String> =
        send(subject = "SecurityCam test alert", body = "This is a test email from SecurityCam.", imageFile = null)

    suspend fun sendDetection(subject: String, body: String, imageFile: File?): Result<String> =
        send(subject, body, imageFile)

    private suspend fun send(subject: String, body: String, imageFile: File?): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.smtpUser.isBlank() || prefs.smtpPassword.isBlank() || prefs.emailTo.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Email settings incomplete"))
                }
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", prefs.smtpHost)
                    put("mail.smtp.port", prefs.smtpPort.toString())
                }
                val session = Session.getInstance(props, object : javax.mail.Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(prefs.smtpUser, prefs.smtpPassword)
                })
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(prefs.smtpUser))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(prefs.emailTo))
                    setSubject(subject)
                }
                val textPart = MimeBodyPart().apply { setText(body) }
                val multipart = MimeMultipart().apply { addBodyPart(textPart) }
                if (imageFile != null && imageFile.exists()) {
                    val imagePart = MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(imageFile.readBytes(), "image/jpeg"))
                        fileName = imageFile.name
                    }
                    multipart.addBodyPart(imagePart)
                }
                message.setContent(multipart)
                Transport.send(message)
                Result.success("Email sent")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
