package com.securitycam.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.securitycam.app.service.MonitorService
import com.securitycam.app.ui.EventsAdapter
import com.securitycam.app.ui.OverlayView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var eventsAdapter: EventsAdapter

    private var monitorService: MonitorService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as MonitorService.LocalBinder).getService()
            monitorService = svc
            bound = true
            svc.attachPreviewSurfaceProvider(previewView.surfaceProvider)
            observeService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] != true) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.status_text)
        toggleButton = findViewById(R.id.toggle_button)
        eventsAdapter = EventsAdapter(
            onClick = { event ->
                startActivity(
                    Intent(this, EventDetailActivity::class.java)
                        .putExtra(EventDetailActivity.EXTRA_EVENT_ID, event.id)
                )
            },
            onDelete = { event ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.delete_event)
                    .setMessage(R.string.delete_event_confirm)
                    .setPositiveButton(R.string.delete_event) { _, _ ->
                        event.dir.deleteRecursively()
                        monitorService?.refreshRecentEvents()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.events_recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = eventsAdapter
        }

        toggleButton.setOnClickListener { toggleMonitoring() }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toggleMonitoring() {
        val svc = monitorService
        if (svc != null && svc.isArmed.value) {
            startService(MonitorService.stopIntent(this))
        } else {
            ContextCompat.startForegroundService(this, MonitorService.startIntent(this))
        }
    }

    private fun observeService(svc: MonitorService) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.isArmed.collect { armed ->
                        toggleButton.setText(if (armed) R.string.stop_monitoring else R.string.start_monitoring)
                        statusText.setText(if (armed) R.string.status_monitoring else R.string.status_idle)
                    }
                }
                launch {
                    svc.detectionFrame.collect { frame ->
                        overlayView.update(frame.detections, frame.frameWidth, frame.frameHeight)
                    }
                }
                launch {
                    svc.recentEvents.collect { events ->
                        eventsAdapter.submitList(events)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MonitorService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            monitorService?.attachPreviewSurfaceProvider(null)
            unbindService(connection)
            bound = false
        }
    }
}
