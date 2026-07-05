package com.securitycam.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateFormat
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.securitycam.app.service.MonitorService
import com.securitycam.app.storage.EventStore
import com.securitycam.app.ui.FramesAdapter
import java.io.File
import java.util.Date

class EventDetailActivity : AppCompatActivity() {

    private var monitorService: MonitorService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            monitorService = (service as MonitorService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_detail)

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val eventStore = EventStore(this)
        val event = eventId?.let { id -> eventStore.listEvents().firstOrNull { it.id == id } }

        if (event == null) {
            Toast.makeText(this, "Event not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<ImageView>(R.id.snapshot_image).setImageBitmap(
            BitmapFactory.decodeFile(event.snapshotFile.absolutePath)
        )

        val groupNames = event.groups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        val timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(event.timestampMs))
        findViewById<TextView>(R.id.event_info).text = "$groupNames — $timeStr"

        val description = eventStore.readDescription(event)
        findViewById<TextView>(R.id.event_description).apply {
            if (description != null) {
                text = description
            } else {
                visibility = android.view.View.GONE
            }
        }

        val playButton = findViewById<MaterialButton>(R.id.play_clip_button)
        val noClipText = findViewById<TextView>(R.id.no_clip_text)
        val framesRecycler = findViewById<RecyclerView>(R.id.frames_recycler)

        if (event.mode == "video" && event.clipFile.exists()) {
            playButton.visibility = android.view.View.VISIBLE
            playButton.setOnClickListener {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", event.clipFile)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(viewIntent) }
                    .onFailure { Toast.makeText(this, "No video player app found", Toast.LENGTH_SHORT).show() }
            }
        } else {
            val frames = event.dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
                ?.sortedBy { it.name } ?: emptyList()
            if (frames.isNotEmpty()) {
                noClipText.visibility = android.view.View.VISIBLE
                framesRecycler.visibility = android.view.View.VISIBLE
                framesRecycler.layoutManager = GridLayoutManager(this, 3)
                framesRecycler.adapter = FramesAdapter(frames) { file ->
                    startActivity(
                        Intent(this, FrameViewerActivity::class.java)
                            .putExtra(FrameViewerActivity.EXTRA_EVENT_ID, event.id)
                            .putExtra(FrameViewerActivity.EXTRA_FRAME_INDEX, frames.indexOf(file))
                    )
                }
            }
        }

        findViewById<MaterialButton>(R.id.delete_button).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_event)
                .setMessage(R.string.delete_event_confirm)
                .setPositiveButton(R.string.delete_event) { _, _ ->
                    event.dir.deleteRecursively()
                    monitorService?.refreshRecentEvents()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MonitorService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
