package com.securitycam.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.securitycam.app.R
import com.securitycam.app.storage.EventRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsAdapter(
    private val onClick: (EventRecord) -> Unit,
    private val onDelete: (EventRecord) -> Unit,
) : RecyclerView.Adapter<EventsAdapter.ViewHolder>() {
    private var items: List<EventRecord> = emptyList()
    private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<EventRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val groups: TextView = view.findViewById(R.id.event_groups)
        val time: TextView = view.findViewById(R.id.event_time)
        val deleteButton: ImageButton = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = items[position]
        holder.groups.text = event.groups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        holder.time.text = timeFormat.format(Date(event.timestampMs))
        if (event.snapshotFile.exists()) {
            val bmp = BitmapFactory.decodeFile(event.snapshotFile.absolutePath)
            holder.thumbnail.setImageBitmap(bmp)
        } else {
            holder.thumbnail.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onClick(event) }
        holder.deleteButton.setOnClickListener { onDelete(event) }
    }

    override fun getItemCount(): Int = items.size
}
