package com.securitycam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.securitycam.app.MainActivity
import com.securitycam.app.R
import com.securitycam.app.alert.EmailAlerter
import com.securitycam.app.alert.GeminiDescriber
import com.securitycam.app.alert.NtfyAlerter
import com.securitycam.app.detect.Detection
import com.securitycam.app.detect.DetectionGroup
import com.securitycam.app.detect.Detector
import com.securitycam.app.detect.TriggerLogic
import com.securitycam.app.settings.Prefs
import com.securitycam.app.storage.EventRecord
import com.securitycam.app.storage.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class DetectionFrame(
    val detections: List<Detection>,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * Foreground service owning the camera pipeline: preview (optional, only while an
 * activity is attached), detection analysis, event-triggered video recording and
 * alert dispatch. Kept alive independent of any UI so it can run with the screen off.
 */
class MonitorService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    private val binder = LocalBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: Prefs
    private lateinit var detector: Detector
    private lateinit var eventStore: EventStore
    private lateinit var triggerLogic: TriggerLogic

    private lateinit var emailAlerter: EmailAlerter
    private lateinit var ntfyAlerter: NtfyAlerter
    private lateinit var geminiDescriber: GeminiDescriber

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null

    private var currentEvent: EventRecord? = null
    private val currentEventGroups = mutableSetOf<DetectionGroup>()
    private val alertedGroupsThisEvent = mutableSetOf<DetectionGroup>()
    private var recordingStartMs = 0L
    private var stopJob: Job? = null
    private var webServer: com.securitycam.app.web.WebServer? = null

    /** Some cheap/old camera HALs report LEGACY level and can't reliably feed a video
     *  encoder while ImageAnalysis is also streaming; in that case we fall back to
     *  periodic still-frame "burst" capture instead of a continuous video clip. */
    private var isLegacyCamera = false
    private var burstMode = false
    private var burstFrameIndex = 0
    private var burstJob: Job? = null

    @Volatile private var latestFrameJpeg: ByteArray? = null

    private val lastAnalyzedAt = AtomicLong(0L)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _detectionFrame = MutableStateFlow(DetectionFrame(emptyList(), 1, 1))
    val detectionFrame: StateFlow<DetectionFrame> = _detectionFrame.asStateFlow()

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<EventRecord>>(emptyList())
    val recentEvents: StateFlow<List<EventRecord>> = _recentEvents.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = Prefs(this)
            detector = Detector(this)
            eventStore = EventStore(this)
            cameraExecutor = Executors.newSingleThreadExecutor()
            emailAlerter = EmailAlerter(this)
            ntfyAlerter = NtfyAlerter(this)
            geminiDescriber = GeminiDescriber(this)
            createNotificationChannels()
            refreshRecentEvents()
            isLegacyCamera = detectLegacyHardware()
            Log.i(TAG, "Camera hardware level LEGACY=$isLegacyCamera")
            if (prefs.webServerEnabled) startWebServer()
        } catch (e: Exception) {
            Log.e(TAG, "MonitorService initialization failed", e)
            android.widget.Toast.makeText(
                this, "SecurityCam init error: ${e.javaClass.simpleName}: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun detectLegacyHardware(): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
            val level = backId?.let {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            }
            level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine camera hardware level", e)
            false
        }
    }

    private fun startWebServer() {
        try {
            webServer?.stop()
            webServer = com.securitycam.app.web.WebServer(
                prefs.webServerPort,
                eventStore,
                isArmedProvider = { _isArmed.value },
                latestFrameProvider = { latestFrameJpeg },
            ).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "Web server started on port ${prefs.webServerPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            android.widget.Toast.makeText(
                this, "Web server failed: ${e.javaClass.simpleName}: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun startMonitoring() {
        if (_isArmed.value) return
        triggerLogic = TriggerLogic(prefs.consecutiveFrames, prefs.cooldownMs)
        val notification = buildNotification(getString(R.string.status_monitoring))
        ServiceCompat.startForeground(
            this, NOTIF_ID_MONITOR, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        _isArmed.value = true
        bindCamera()
    }

    private fun stopMonitoring() {
        _isArmed.value = false
        stopJob?.cancel()
        if (currentEvent != null) {
            finishRecording()
        } else {
            activeRecording?.stop()
            activeRecording = null
        }
        cameraProvider?.unbindAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Called by an attached Activity to route the live preview into its PreviewView. */
    fun attachPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        bindCamera()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            provider.unbindAll()

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                // Fixed target rotation: for a stationary security camera, CameraX's
                // default (tied to the current display rotation at bind time) produces
                // inconsistent results across restarts since nothing is "looking at" a
                // display. Freezing this makes imageInfo.rotationDegrees depend only on
                // the camera sensor's fixed hardware mounting, so it's reproducible.
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }
            imageAnalysis = analysis

            val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
            if (isLegacyCamera) {
                // LEGACY-level camera HALs can't reliably feed a video encoder while
                // ImageAnalysis is also streaming (confirmed: encoder times out and the
                // clip aborts after ~1s) — skip VideoCapture and fall back to periodic
                // still-frame bursts instead (see startBurstCapture).
                videoCapture = null
            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .build()
                val video = VideoCapture.withOutput(recorder)
                videoCapture = video
                useCases.add(video)
            }

            val currentSurfaceProvider = previewSurfaceProvider
            if (currentSurfaceProvider != null) {
                val p = Preview.Builder().build().also { it.setSurfaceProvider(currentSurfaceProvider) }
                preview = p
                useCases.add(0, p)
            } else {
                preview = null
            }

            try {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val last = lastAnalyzedAt.get()
            if (now - last < ANALYSIS_INTERVAL_MS) {
                return
            }
            lastAnalyzedAt.set(now)

            val rawBitmap = imageProxy.toBitmap()
            val rotation = (imageProxy.imageInfo.rotationDegrees + prefs.cameraMountRotation) % 360
            val bitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }
            val raw = detector.detect(bitmap)
            val filtered = raw.filter { d ->
                prefs.isGroupEnabled(d.group) && d.score >= prefs.confidenceFor(d.group)
            }
            _detectionFrame.value = DetectionFrame(filtered, bitmap.width, bitmap.height)
            latestFrameJpeg = java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                out.toByteArray()
            }

            if (!_isArmed.value) return
            val present = filtered.map { it.group }.toSet()
            val triggered = triggerLogic.onFrame(present, now)
            if (triggered.isNotEmpty() || (currentEvent != null && present.isNotEmpty())) {
                onDetectionActivity(triggered, filtered, bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun onDetectionActivity(
        triggered: Set<DetectionGroup>,
        filtered: List<Detection>,
        frame: Bitmap,
    ) {
        val event = currentEvent
        if (event == null) {
            if (triggered.isEmpty()) return
            val newEvent = eventStore.createEvent(triggered.toList())
            currentEvent = newEvent
            currentEventGroups.clear()
            currentEventGroups.addAll(triggered)
            alertedGroupsThisEvent.clear()
            eventStore.saveSnapshot(newEvent, drawBoxes(frame, filtered))
            startRecording(newEvent)
            dispatchAlerts(newEvent, triggered, filtered)
        } else {
            currentEventGroups.addAll(triggered)
            val newGroups = triggered - alertedGroupsThisEvent
            if (newGroups.isNotEmpty()) {
                dispatchAlerts(event, newGroups, filtered)
            }
            extendRecording()
        }
    }

    private fun startRecording(event: EventRecord) {
        recordingStartMs = System.currentTimeMillis()
        burstMode = isLegacyCamera || videoCapture == null
        if (burstMode) {
            burstFrameIndex = 0
            startBurstCapture(event)
        } else {
            val recorder = videoCapture?.output ?: return
            val outputOptions = FileOutputOptions.Builder(event.clipFile).build()
            activeRecording = recorder.prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event2 ->
                    if (event2 is VideoRecordEvent.Finalize && event2.hasError()) {
                        Log.e(TAG, "Video recording error: ${event2.error}")
                    }
                }
        }
        scheduleStop(prefs.clipSeconds.toLong())
    }

    private fun startBurstCapture(event: EventRecord) {
        burstJob?.cancel()
        burstJob = serviceScope.launch {
            while (true) {
                val jpeg = latestFrameJpeg
                if (jpeg != null) {
                    runCatching {
                        File(event.dir, "frame_%03d.jpg".format(burstFrameIndex++)).writeBytes(jpeg)
                    }
                }
                delay(BURST_INTERVAL_MS)
            }
        }
    }

    private fun extendRecording() {
        if (activeRecording == null && burstJob == null) return
        val elapsedS = (System.currentTimeMillis() - recordingStartMs) / 1000
        val remaining = (prefs.maxClipSeconds - elapsedS).coerceAtLeast(0)
        val extension = prefs.clipSeconds.toLong().coerceAtMost(remaining)
        if (extension <= 0) return
        stopJob?.cancel()
        stopJob = serviceScope.launch {
            delay(extension * 1000)
            finishRecording()
        }
    }

    private fun scheduleStop(delaySeconds: Long) {
        stopJob?.cancel()
        stopJob = serviceScope.launch {
            delay(delaySeconds * 1000)
            finishRecording()
        }
    }

    private fun finishRecording() {
        if (burstMode) {
            burstJob?.cancel()
            burstJob = null
        } else {
            activeRecording?.stop()
            activeRecording = null
        }
        val event = currentEvent ?: return
        val existingDescription = eventStore.readDescription(event)
        eventStore.saveMeta(
            event,
            groups = currentEventGroups.toList(),
            description = existingDescription,
            mode = if (burstMode) "burst" else "video",
        )
        eventStore.enforceRetention()
        refreshRecentEvents()
        currentEvent = null
        currentEventGroups.clear()
        alertedGroupsThisEvent.clear()
    }

    /**
     * Builds a link to this event's page on the built-in web server, using the
     * user-configured remote base URL (e.g. a Tailscale address) if set, otherwise
     * falling back to this device's current LAN IP (only reachable on the home WiFi).
     * Returns null if the web server is disabled, since there'd be nothing to link to.
     */
    private fun eventLink(event: EventRecord): String? {
        if (!prefs.webServerEnabled) return null
        val base = prefs.remoteBaseUrl.ifBlank {
            val ip = com.securitycam.app.util.findLanIpAddress() ?: return null
            "http://$ip:${prefs.webServerPort}"
        }
        return "$base/event/${event.id}"
    }

    private fun dispatchAlerts(event: EventRecord, groups: Set<DetectionGroup>, filtered: List<Detection>) {
        alertedGroupsThisEvent.addAll(groups)
        val timeStr = timeFormat.format(Date(event.timestampMs))
        val groupNames = groups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        val labelsStr = filtered.filter { it.group in groups }
            .joinToString(", ") { "${it.label} (${(it.score * 100).toInt()}%)" }

        serviceScope.launch {
            var description: String? = null
            if (prefs.geminiEnabled) {
                geminiDescriber.describe(
                    event.snapshotFile,
                    "In one short sentence, describe what a security camera detected in this image (e.g. person, vehicle, animal, activity)."
                ).onSuccess { description = it }
                    .onFailure { Log.w(TAG, "Gemini description failed: ${it.message}") }
            }

            val subject = "SecurityCam: $groupNames detected"
            val bodyBuilder = StringBuilder()
                .append("Detected: $groupNames\n")
                .append("Time: $timeStr\n")
                .append("Objects: $labelsStr\n")
            if (description != null) bodyBuilder.append("\nAI description: $description\n")
            eventLink(event)?.let { bodyBuilder.append("\nView event: $it\n") }
            val body = bodyBuilder.toString()

            if (prefs.emailEnabled) {
                emailAlerter.sendDetection(subject, body, event.snapshotFile)
                    .onFailure { Log.w(TAG, "Email alert failed: ${it.message}") }
            }
            if (prefs.ntfyEnabled) {
                ntfyAlerter.sendDetection(subject, body, event.snapshotFile)
                    .onFailure { Log.w(TAG, "ntfy alert failed: ${it.message}") }
            }

            val finalDescription = description
            if (finalDescription != null) {
                eventStore.updateDescription(event, finalDescription)
            }
        }

        refreshRecentEvents()
        val notification = buildNotification(getString(R.string.status_monitoring))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_MONITOR, notification)
    }

    private fun drawBoxes(source: Bitmap, detections: List<Detection>): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 32f; isFakeBoldText = true }
        for (d in detections) {
            val color = when (d.group) {
                DetectionGroup.HUMAN -> Color.RED
                DetectionGroup.VEHICLE -> Color.CYAN
                DetectionGroup.ANIMAL -> Color.YELLOW
            }
            boxPaint.color = color
            canvas.drawRect(d.left, d.top, d.right, d.bottom, boxPaint)
            canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", d.left + 4f, (d.top - 8f).coerceAtLeast(20f), textPaint)
        }
        return bitmap
    }

    fun refreshRecentEvents() {
        _recentEvents.value = eventStore.listEvents().take(50)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.notif_channel_monitor), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.notif_channel_events), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cam)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        stopJob?.cancel()
        if (currentEvent != null) {
            finishRecording()
        } else {
            activeRecording?.stop()
        }
        // Stop new frames from being queued before touching the executor/detector below —
        // otherwise a frame already in flight on cameraExecutor can call into the native
        // TFLite detector just as it's being closed, causing a native segfault (observed
        // during testing: SIGSEGV inside ObjectDetector_detectNative).
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
            try {
                cameraExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (::detector.isInitialized) detector.close()
        webServer?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_STOP = "com.securitycam.app.STOP"
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_EVENTS = "events"
        const val NOTIF_ID_MONITOR = 1
        private const val ANALYSIS_INTERVAL_MS = 350L
        private const val BURST_INTERVAL_MS = 1000L

        fun startIntent(context: Context) = Intent(context, MonitorService::class.java)
        fun stopIntent(context: Context) = Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
    }
}
