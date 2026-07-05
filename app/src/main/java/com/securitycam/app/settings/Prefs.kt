package com.securitycam.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.securitycam.app.detect.DetectionGroup
import com.securitycam.app.schedule.DaySchedule
import com.securitycam.app.schedule.Weekday

/** Typed accessor over the default SharedPreferences used by the whole app. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val KEY_DETECT_HUMAN = "detect_human"
        const val KEY_DETECT_VEHICLE = "detect_vehicle"
        const val KEY_DETECT_ANIMAL = "detect_animal"
        const val KEY_CONF_HUMAN = "conf_human"
        const val KEY_CONF_VEHICLE = "conf_vehicle"
        const val KEY_CONF_ANIMAL = "conf_animal"
        const val KEY_CONSECUTIVE_FRAMES = "consecutive_frames"
        const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"
        const val KEY_CLIP_SECONDS = "clip_seconds"
        const val KEY_MAX_CLIP_SECONDS = "max_clip_seconds"
        const val KEY_STORAGE_LOCATION = "storage_location" // "sdcard" or "internal"
        const val KEY_RETENTION_MB = "retention_mb"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_CAMERA_MOUNT_ROTATION = "camera_mount_rotation"

        const val KEY_EMAIL_ENABLED = "email_enabled"
        const val KEY_SMTP_HOST = "smtp_host"
        const val KEY_SMTP_PORT = "smtp_port"
        const val KEY_SMTP_USER = "smtp_user"
        const val KEY_SMTP_PASSWORD = "smtp_password"
        const val KEY_EMAIL_TO = "email_to"

        const val KEY_NTFY_ENABLED = "ntfy_enabled"
        const val KEY_NTFY_SERVER = "ntfy_server"
        const val KEY_NTFY_TOPIC = "ntfy_topic"

        const val KEY_WEBSERVER_ENABLED = "webserver_enabled"
        const val KEY_WEBSERVER_PORT = "webserver_port"
        const val KEY_REMOTE_BASE_URL = "remote_base_url"

        const val KEY_GEMINI_ENABLED = "gemini_enabled"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"

        const val KEY_START_ON_BOOT = "start_on_boot"

        const val KEY_STATUS_ALERTS_ENABLED = "status_alerts_enabled"
        const val KEY_BATTERY_ALERTS_ENABLED = "battery_alerts_enabled"
        const val KEY_BATTERY_THRESHOLD_PERCENT = "battery_threshold_percent"

        const val KEY_CAMERA_NAME = "camera_name"

        const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        const val SCHEDULE_KEY_PREFIX = "schedule_"
    }

    fun isGroupEnabled(group: DetectionGroup): Boolean = when (group) {
        DetectionGroup.HUMAN -> sp.getBoolean(KEY_DETECT_HUMAN, true)
        DetectionGroup.VEHICLE -> sp.getBoolean(KEY_DETECT_VEHICLE, true)
        DetectionGroup.ANIMAL -> sp.getBoolean(KEY_DETECT_ANIMAL, true)
    }

    fun confidenceFor(group: DetectionGroup): Float = when (group) {
        DetectionGroup.HUMAN -> sp.getInt(KEY_CONF_HUMAN, 60)
        DetectionGroup.VEHICLE -> sp.getInt(KEY_CONF_VEHICLE, 60)
        DetectionGroup.ANIMAL -> sp.getInt(KEY_CONF_ANIMAL, 60)
    } / 100f

    val consecutiveFrames: Int get() = sp.getInt(KEY_CONSECUTIVE_FRAMES, 2)
    val cooldownMs: Long get() = sp.getInt(KEY_COOLDOWN_SECONDS, 60) * 1000L
    val clipSeconds: Int get() = sp.getInt(KEY_CLIP_SECONDS, 15)
    val maxClipSeconds: Int get() = sp.getInt(KEY_MAX_CLIP_SECONDS, 60)
    val useSdCard: Boolean get() = sp.getString(KEY_STORAGE_LOCATION, "sdcard") == "sdcard"
    val retentionMb: Int get() = sp.getInt(KEY_RETENTION_MB, 2048)
    /** 0 means "never auto-delete by age" — only the storage cap applies. */
    val retentionDays: Int get() = sp.getInt(KEY_RETENTION_DAYS, 30)
    /** Extra clockwise rotation (0/90/180/270) to correct for how the phone is physically mounted. */
    val cameraMountRotation: Int get() = sp.getString(KEY_CAMERA_MOUNT_ROTATION, "0")?.toIntOrNull() ?: 0

    val emailEnabled: Boolean get() = sp.getBoolean(KEY_EMAIL_ENABLED, false)
    val smtpHost: String get() = sp.getString(KEY_SMTP_HOST, "smtp.gmail.com") ?: "smtp.gmail.com"
    val smtpPort: Int get() = sp.getString(KEY_SMTP_PORT, "587")?.toIntOrNull() ?: 587
    val smtpUser: String get() = sp.getString(KEY_SMTP_USER, "") ?: ""
    val smtpPassword: String get() = sp.getString(KEY_SMTP_PASSWORD, "") ?: ""
    val emailTo: String get() = sp.getString(KEY_EMAIL_TO, "") ?: ""

    val ntfyEnabled: Boolean get() = sp.getBoolean(KEY_NTFY_ENABLED, false)
    val ntfyServer: String get() = sp.getString(KEY_NTFY_SERVER, "https://ntfy.sh") ?: "https://ntfy.sh"
    val ntfyTopic: String get() = sp.getString(KEY_NTFY_TOPIC, "") ?: ""

    val webServerEnabled: Boolean get() = sp.getBoolean(KEY_WEBSERVER_ENABLED, true)
    val webServerPort: Int get() = sp.getString(KEY_WEBSERVER_PORT, "8080")?.toIntOrNull() ?: 8080
    /** e.g. a Tailscale address like "http://100.x.x.x:8080". Blank = fall back to the LAN IP. */
    val remoteBaseUrl: String get() = (sp.getString(KEY_REMOTE_BASE_URL, "") ?: "").trimEnd('/')

    val geminiEnabled: Boolean get() = sp.getBoolean(KEY_GEMINI_ENABLED, false)
    val geminiApiKey: String get() = sp.getString(KEY_GEMINI_API_KEY, "") ?: ""

    val startOnBoot: Boolean get() = sp.getBoolean(KEY_START_ON_BOOT, false)

    val statusAlertsEnabled: Boolean get() = sp.getBoolean(KEY_STATUS_ALERTS_ENABLED, true)
    val batteryAlertsEnabled: Boolean get() = sp.getBoolean(KEY_BATTERY_ALERTS_ENABLED, true)
    val batteryThresholdPercent: Int get() = sp.getInt(KEY_BATTERY_THRESHOLD_PERCENT, 20)

    /** Shown in alert subjects/titles and the web page — useful with more than one camera. */
    val cameraName: String get() = sp.getString(KEY_CAMERA_NAME, "") ?: ""

    val scheduleEnabled: Boolean get() = sp.getBoolean(KEY_SCHEDULE_ENABLED, false)

    fun daySchedule(day: Weekday): DaySchedule = DaySchedule(
        enabled = sp.getBoolean("${SCHEDULE_KEY_PREFIX}${day.keyPrefix}_enabled", false),
        startMinutes = sp.getInt("${SCHEDULE_KEY_PREFIX}${day.keyPrefix}_start", 0),
        endMinutes = sp.getInt("${SCHEDULE_KEY_PREFIX}${day.keyPrefix}_end", 24 * 60 - 1),
    )

    fun allDaySchedules(): Map<Int, DaySchedule> =
        Weekday.entries.associate { it.isoIndex to daySchedule(it) }
}
