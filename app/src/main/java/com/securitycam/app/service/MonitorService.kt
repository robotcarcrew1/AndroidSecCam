package com.securitycam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    /** Tracks which provider [preview] was last bound for, so [rebindPreview] can skip
     *  redundant rebinds when nothing actually changed. */
    private var boundPreviewSurfaceProvider: Preview.SurfaceProvider? = null

    private var currentEvent: EventRecord? = null
    private val currentEventGroups = mutableSetOf<DetectionGroup>()
    private val alertedGroupsThisEvent = mutableSetOf<DetectionGroup>()
    /** Remembers where (and when) the last alert-worthy detection was seen per group —
     *  tracked independently of events/recordings (which reset far more often), so a
     *  stationary object (e.g. a parked car) doesn't re-alert every time one clip ends
     *  and the next begins, while a genuinely new object in a different spot still
     *  alerts immediately. See [dispatchAlerts]. */
    private data class AlertMemory(val timestampMs: Long, val box: Detection)
    private val lastAlert = mutableMapOf<DetectionGroup, AlertMemory>()
    /** Highest single-detection confidence seen so far this event — the snapshot is
     *  replaced whenever a frame beats it, so the thumbnail/snapshot shows the clearest
     *  moment of the event rather than just whichever frame first triggered it. */
    private var bestSnapshotScore = 0f
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

    /** Held for as long as monitoring is armed, so the CPU keeps running the camera/
     *  detection pipeline with the screen off — foreground services generally survive
     *  screen-off on their own, but some OEM power management (Samsung/Xiaomi) is more
     *  aggressive, and this is the standard extra guarantee for a 24/7 background task. */
    private var wakeLock: PowerManager.WakeLock? = null

    private var batteryReceiver: BroadcastReceiver? = null
    /** Whether we've already sent a low-battery alert for the current below-threshold
     *  stretch — one alert per stretch is enough; resets once it's charging or back above
     *  the threshold, so the next time it drops below alerts again. (Previously this
     *  re-alerted on every single percentage-point drop — e.g. 20%, 19%, 18%, ... down to
     *  3% — 18 alerts in about an hour, live-observed on the Mi A1 via ntfy history.) */
    private var lowBatteryAlertSent = false

    @Volatile private var latestFrameJpeg: ByteArray? = null
    /** Bumped every time [latestFrameJpeg] changes, so the MJPEG live-stream endpoint can
     *  tell a genuinely new frame apart from re-reading the same one. */
    @Volatile private var latestFrameSeq: Long = 0L

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
            registerBatteryReceiver()
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
                latestFrameSeqProvider = { latestFrameSeq },
                cameraLabelProvider = { cameraLabel() },
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
        acquireWakeLock()
        _isArmed.value = true
        bindCamera()
        sendStartAlert()
    }

    private fun stopMonitoring() {
        sendStopAlert()
        _isArmed.value = false
        stopJob?.cancel()
        if (currentEvent != null) {
            finishRecording()
        } else {
            activeRecording?.stop()
            activeRecording = null
        }
        runCameraOp { cameraProvider?.unbindAll() }
        imageAnalysis = null
        videoCapture = null
        preview = null
        boundPreviewSurfaceProvider = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SecurityCam:MonitorWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun sendStartAlert() {
        if (!prefs.statusAlertsEnabled) return
        serviceScope.launch {
            var waitedMs = 0
            while (latestFrameJpeg == null && waitedMs < 5000) {
                delay(250)
                waitedMs += 250
            }
            val imageFile = latestFrameJpeg?.let { bytes ->
                runCatching {
                    File(cacheDir, "status_snapshot.jpg").apply { writeBytes(bytes) }
                }.getOrNull()
            }
            val subject = "${cameraLabel()}: monitoring started"
            val bodyBuilder = StringBuilder("Monitoring started at ${timeFormat.format(Date())}.\n")
            webServerBaseUrl()?.let { bodyBuilder.append("\nView live: $it/\n") }
            val body = bodyBuilder.toString()
            if (prefs.emailEnabled) {
                emailAlerter.sendDetection(subject, body, imageFile)
                    .onFailure { Log.w(TAG, "Start alert email failed: ${it.message}") }
            }
            if (prefs.ntfyEnabled) {
                ntfyAlerter.sendDetection(subject, body, imageFile)
                    .onFailure { Log.w(TAG, "Start alert ntfy failed: ${it.message}") }
            }
        }
    }

    private fun sendStopAlert() {
        if (!prefs.statusAlertsEnabled) return
        serviceScope.launch {
            val subject = "${cameraLabel()}: monitoring stopped"
            val body = "Monitoring stopped at ${timeFormat.format(Date())}."
            if (prefs.emailEnabled) {
                emailAlerter.sendDetection(subject, body, null)
                    .onFailure { Log.w(TAG, "Stop alert email failed: ${it.message}") }
            }
            if (prefs.ntfyEnabled) {
                ntfyAlerter.sendDetection(subject, body, null)
                    .onFailure { Log.w(TAG, "Stop alert ntfy failed: ${it.message}") }
            }
        }
    }

    /** Called by an attached Activity to route the live preview into its PreviewView. */
    fun attachPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        bindCamera()
    }

    /**
     * Runs [op] on the main thread, spaced at least [CAMERA_OP_SPACING_MS] after the previous
     * camera bind/unbind operation (tracked process-wide via [nextCameraOpAtMs]).
     *
     * Back-to-back reconfigurations of the same [ProcessCameraProvider] — e.g. one
     * MonitorService instance's unbindAll() (stopping monitoring) racing the very next
     * instance's bindCamera() (a quick re-arm), or a core-pipeline bind immediately followed
     * by adding Preview — were observed live to leave the capture session half-configured:
     * Camera2CameraImpl logs "Unable to configure camera cancelled", and afterward either the
     * live preview sits black (despite CameraX itself reporting the Preview stream as
     * STREAMING) or a triggered recording never receives encoder data. Camera2's async
     * hardware reconfiguration doesn't necessarily finish by the time our next call runs on
     * the main thread, so simply calling these sequentially isn't enough; spacing them out
     * gives the previous reconfiguration time to actually settle.
     */
    private fun runCameraOp(op: () -> Unit) {
        val now = android.os.SystemClock.elapsedRealtime()
        val runAt = maxOf(now, nextCameraOpAtMs)
        nextCameraOpAtMs = runAt + CAMERA_OP_SPACING_MS
        val delay = runAt - now
        if (delay <= 0) op() else mainHandler.postDelayed(op, delay)
    }

    /** Marks a camera op as having just happened without deferring the caller — for
     *  onDestroy(), whose unbindAll() must run synchronously right before cameraExecutor
     *  shuts down (see the SIGSEGV comment there). Still pushes out [nextCameraOpAtMs] so
     *  the *next* MonitorService instance's bindCamera() is properly spaced from this one. */
    private fun markCameraOpNow() {
        nextCameraOpAtMs = android.os.SystemClock.elapsedRealtime() + CAMERA_OP_SPACING_MS
    }

    /**
     * Binds the "core" pipeline — [ImageAnalysis] and (if supported) [VideoCapture] — once,
     * and keeps those same use case instances bound for as long as monitoring is armed.
     * The [Preview] use case is handled separately by [rebindPreview], which only adds or
     * removes *that* use case.
     *
     * This split matters: this used to rebuild every use case (including VideoCapture) and
     * call unbindAll() + rebindToLifecycle on every single preview attach/detach — which
     * happens on every app foreground/background transition (see MainActivity's onStart/
     * onStop calling attachPreviewSurfaceProvider). If that ran while a clip was actively
     * recording (screen sleeps or app is backgrounded mid-event), it tore down the
     * Recorder's surface out from under the in-progress Recording, producing an event with
     * no clip.mp4 at all, and occasionally left the camera unbound afterward (black preview)
     * when the follow-up rebind failed. The actual bind/unbind calls are further wrapped in
     * [runCameraOp] — see its doc for the remaining race this alone didn't cover.
     */
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            runCameraOp {
                if (imageAnalysis == null) {
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

                    try {
                        provider.bindToLifecycle(
                            this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind camera use cases", e)
                    }
                }

                rebindPreview(provider)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Adds or removes only the [Preview] use case, leaving analysis/recording untouched.
     *
     * No-ops if [previewSurfaceProvider] already matches what's currently bound: bindCamera()
     * runs from two independent triggers that can land back-to-back at startup — startMonitoring()
     * and attachPreviewSurfaceProvider() (called when the Activity's service connection completes)
     * — and re-running this unconditionally rebinds Preview twice within the same second. That
     * double reconfiguration was observed (live, on the Tab A) to leave VideoCapture's capture
     * session half-configured (Camera2CameraImpl logs "Unable to configure camera cancelled"
     * right after), so the *next* recording never received any encoder data and finalized with
     * ERROR_NO_VALID_DATA after sitting in PENDING_RECORDING for the whole clip duration.
     */
    private fun rebindPreview(provider: ProcessCameraProvider) {
        val currentSurfaceProvider = previewSurfaceProvider
        if (currentSurfaceProvider === boundPreviewSurfaceProvider) return
        preview?.let { provider.unbind(it) }
        if (currentSurfaceProvider != null) {
            val p = Preview.Builder().build().also { it.setSurfaceProvider(currentSurfaceProvider) }
            preview = p
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind preview", e)
            }
        } else {
            preview = null
        }
        boundPreviewSurfaceProvider = currentSurfaceProvider
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
            latestFrameSeq++

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
        val frameMaxScore = filtered.maxOfOrNull { it.score } ?: 0f
        if (event == null) {
            if (triggered.isEmpty()) return
            val newEvent = eventStore.createEvent(triggered.toList())
            currentEvent = newEvent
            currentEventGroups.clear()
            currentEventGroups.addAll(triggered)
            alertedGroupsThisEvent.clear()
            bestSnapshotScore = frameMaxScore
            eventStore.saveSnapshot(newEvent, drawBoxes(frame, filtered))
            startRecording(newEvent)
            dispatchAlerts(newEvent, triggered, filtered)
        } else {
            currentEventGroups.addAll(triggered)
            val newGroups = triggered - alertedGroupsThisEvent
            if (newGroups.isNotEmpty()) {
                dispatchAlerts(event, newGroups, filtered)
            }
            if (frameMaxScore > bestSnapshotScore) {
                bestSnapshotScore = frameMaxScore
                eventStore.saveSnapshot(event, drawBoxes(frame, filtered))
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
        bestSnapshotScore = 0f
    }

    /** User-configured camera name for alert subjects/titles, falling back to the app name
     *  — useful to tell cameras apart when running more than one. */
    private fun cameraLabel(): String = prefs.cameraName.ifBlank { getString(R.string.app_name) }

    /**
     * Builds a link to this event's page on the built-in web server, using the
     * user-configured remote base URL (e.g. a Tailscale address) if set, otherwise
     * falling back to this device's current LAN IP (only reachable on the home WiFi).
     * Returns null if the web server is disabled, since there'd be nothing to link to.
     */
    private fun eventLink(event: EventRecord): String? = webServerBaseUrl()?.let { "$it/event/${event.id}" }

    /** Base URL for the built-in web server: the configured remote base URL (e.g. a
     *  Tailscale address) if set, otherwise this device's current LAN IP. Null if the
     *  web server is disabled or no address could be determined.
     *
     *  Normalizes a bare "host:port" remote base URL by prepending "http://" and
     *  trimming a trailing slash — SETUP.md asks for the scheme to be included, but a
     *  value like "100.100.147.121:8080" (missing it) was live-observed in the Mi A1's
     *  prefs, producing un-clickable "View event"/"View live" links in alerts (ntfy and
     *  most notification apps only auto-link recognized URL schemes). */
    private fun webServerBaseUrl(): String? {
        if (!prefs.webServerEnabled) return null
        val configured = prefs.remoteBaseUrl.trim()
        if (configured.isBlank()) {
            val ip = com.securitycam.app.util.findLanIpAddress() ?: return null
            return "http://$ip:${prefs.webServerPort}"
        }
        val withScheme = if (configured.startsWith("http://") || configured.startsWith("https://")) {
            configured
        } else {
            "http://$configured"
        }
        return withScheme.removeSuffix("/")
    }

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!prefs.batteryAlertsEnabled) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                if (level < 0 || scale <= 0) return
                val percent = level * 100 / scale
                val charging = plugged != 0
                if (charging || percent > prefs.batteryThresholdPercent) {
                    lowBatteryAlertSent = false
                } else if (!lowBatteryAlertSent) {
                    lowBatteryAlertSent = true
                    sendBatteryAlert(percent)
                }
            }
        }
        batteryReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun sendBatteryAlert(percent: Int) {
        Log.w(TAG, "Battery low: $percent% and not charging")
        serviceScope.launch {
            val subject = "${cameraLabel()}: low battery ($percent%)"
            val body = "The camera phone's battery is at $percent% and not charging. " +
                "It may stop recording/alerting soon if it isn't plugged back in."
            if (prefs.emailEnabled) {
                emailAlerter.sendDetection(subject, body, null)
                    .onFailure { Log.w(TAG, "Battery alert email failed: ${it.message}") }
            }
            if (prefs.ntfyEnabled) {
                ntfyAlerter.sendDetection(subject, body, null)
                    .onFailure { Log.w(TAG, "Battery alert ntfy failed: ${it.message}") }
            }
        }
    }

    private fun dispatchAlerts(event: EventRecord, groups: Set<DetectionGroup>, filtered: List<Detection>) {
        alertedGroupsThisEvent.addAll(groups)
        refreshRecentEvents()
        val notification = buildNotification(getString(R.string.status_monitoring))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_MONITOR, notification)

        // Recordings/snapshots always happen regardless (handled by the caller) — this
        // gate only throttles the email/ntfy alert itself, independently of the
        // recording cooldown. A group only gets suppressed if the *same physical object*
        // (same position, via bounding-box overlap) is still the one being seen — a
        // different object of the same category (e.g. a different car pulling in) always
        // alerts immediately regardless of the repeat window.
        val now = System.currentTimeMillis()
        val repeatMs = prefs.alertRepeatMs
        val dueGroups = groups.filter { g ->
            val candidate = filtered.filter { it.group == g }.maxByOrNull { it.score }
            val memory = lastAlert[g]
            val sameObjectStillThere = candidate != null && memory != null &&
                candidate.boxOverlap(memory.box) >= SAME_OBJECT_IOU_THRESHOLD
            if (!sameObjectStillThere) {
                true
            } else {
                repeatMs <= 0 || now - memory!!.timestampMs >= repeatMs
            }
        }.toSet()
        if (dueGroups.isEmpty()) {
            Log.i(TAG, "Suppressing repeat alert for $groups (same object, within alert-repeat window)")
            return
        }
        dueGroups.forEach { g ->
            filtered.filter { it.group == g }.maxByOrNull { it.score }?.let { best ->
                lastAlert[g] = AlertMemory(now, best)
            }
        }

        val timeStr = timeFormat.format(Date(event.timestampMs))
        val groupNames = dueGroups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        val labelsStr = filtered.filter { it.group in dueGroups }
            .joinToString(", ") { "${it.label} (${(it.score * 100).toInt()}%)" }

        serviceScope.launch {
            // Send a private copy of the snapshot, not the live snapshot.jpg: the analyzer
            // rewrites that file mid-event whenever a better frame arrives, and a rewrite
            // during the upload aborts the send (observed as OkHttp "expected N bytes but
            // received M" — the Vehicle/Animal alerts were silently lost that way).
            val attachment = runCatching {
                File(cacheDir, "alert_${event.id}_${System.nanoTime()}.jpg")
                    .apply { writeBytes(event.snapshotFile.readBytes()) }
            }.getOrElse { event.snapshotFile }
            try {
                var description: String? = null
                if (prefs.geminiEnabled) {
                    geminiDescriber.describe(
                        attachment,
                        "In one short sentence, describe what a security camera detected in this image (e.g. person, vehicle, animal, activity)."
                    ).onSuccess { description = it }
                        .onFailure { Log.w(TAG, "Gemini description failed: ${it.message}") }
                }

                val subject = "${cameraLabel()}: $groupNames detected"
                val bodyBuilder = StringBuilder()
                    .append("Detected: $groupNames\n")
                    .append("Time: $timeStr\n")
                    .append("Objects: $labelsStr\n")
                if (description != null) bodyBuilder.append("\nAI description: $description\n")
                eventLink(event)?.let { bodyBuilder.append("\nView event: $it\n") }
                val body = bodyBuilder.toString()

                if (prefs.emailEnabled) {
                    emailAlerter.sendDetection(subject, body, attachment)
                        .onFailure { Log.w(TAG, "Email alert failed: ${it.message}") }
                }
                if (prefs.ntfyEnabled) {
                    ntfyAlerter.sendDetection(subject, body, attachment)
                        .onFailure { Log.w(TAG, "ntfy alert failed: ${it.message}") }
                }

                val finalDescription = description
                if (finalDescription != null) {
                    eventStore.updateDescription(event, finalDescription)
                }
            } finally {
                if (attachment != event.snapshotFile) attachment.delete()
            }
        }
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
            .setContentTitle(cameraLabel())
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
        // during testing: SIGSEGV inside ObjectDetector_detectNative). This must stay
        // synchronous (not routed through runCameraOp's delay) for that reason.
        markCameraOpNow()
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
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        releaseWakeLock()
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
        private const val SAME_OBJECT_IOU_THRESHOLD = 0.5f

        /** Minimum spacing enforced between camera bind/unbind operations by [runCameraOp],
         *  shared across service instances (a rapid stop+start creates a new MonitorService
         *  instance, but ProcessCameraProvider itself is a process-wide singleton). */
        private const val CAMERA_OP_SPACING_MS = 400L
        @Volatile private var nextCameraOpAtMs = 0L

        fun startIntent(context: Context) = Intent(context, MonitorService::class.java)
        fun stopIntent(context: Context) = Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
    }
}
