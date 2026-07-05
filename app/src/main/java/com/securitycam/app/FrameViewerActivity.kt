package com.securitycam.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.securitycam.app.storage.EventStore
import java.io.File

/** Full-screen viewer for a burst-mode event's still frames, with next/previous navigation. */
class FrameViewerActivity : AppCompatActivity() {

    private lateinit var frames: List<File>
    private var index: Int = 0

    private lateinit var imageView: ImageView
    private lateinit var counterText: TextView
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_viewer)

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val startIndex = intent.getIntExtra(EXTRA_FRAME_INDEX, 0)
        val eventStore = EventStore(this)
        val event = eventId?.let { id -> eventStore.listEvents().firstOrNull { it.id == id } }

        imageView = findViewById(R.id.frame_image)
        counterText = findViewById(R.id.frame_counter)
        prevButton = findViewById(R.id.prev_button)
        nextButton = findViewById(R.id.next_button)

        if (event == null) {
            finish()
            return
        }

        frames = event.dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: emptyList()

        if (frames.isEmpty()) {
            finish()
            return
        }

        index = startIndex.coerceIn(0, frames.size - 1)

        prevButton.setOnClickListener { show(index - 1) }
        nextButton.setOnClickListener { show(index + 1) }
        imageView.setOnClickListener { finish() }

        show(index)
    }

    private fun show(newIndex: Int) {
        if (newIndex < 0 || newIndex >= frames.size) return
        index = newIndex
        imageView.setImageBitmap(BitmapFactory.decodeFile(frames[index].absolutePath))
        counterText.text = "${index + 1} / ${frames.size}"
        prevButton.isEnabled = index > 0
        prevButton.alpha = if (index > 0) 1f else 0.3f
        nextButton.isEnabled = index < frames.size - 1
        nextButton.alpha = if (index < frames.size - 1) 1f else 0.3f
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_FRAME_INDEX = "frame_index"
    }
}
