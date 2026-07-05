package com.securitycam.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FramesAdapter(
    private val frames: List<File>,
    private val onClick: (File) -> Unit,
) : RecyclerView.Adapter<FramesAdapter.ViewHolder>() {

    class ViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.securitycam.app.R.layout.item_frame, parent, false) as ImageView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = frames[position]
        holder.imageView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        holder.imageView.setOnClickListener { onClick(file) }
    }

    override fun getItemCount(): Int = frames.size
}
