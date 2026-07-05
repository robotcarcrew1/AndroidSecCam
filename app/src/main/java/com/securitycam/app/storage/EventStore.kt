package com.securitycam.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.securitycam.app.detect.DetectionGroup
import com.securitycam.app.settings.Prefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EventRecord(
    val id: String,
    val dir: File,
    val timestampMs: Long,
    val groups: List<DetectionGroup>,
    val mode: String = "video",
) {
    val snapshotFile: File get() = File(dir, "snapshot.jpg")
    val clipFile: File get() = File(dir, "clip.mp4")
    val metaFile: File get() = File(dir, "meta.json")
}

/**
 * Owns the on-disk layout for detection events: one directory per event under
 * `<root>/events/<timestamp>_<id>/` containing snapshot.jpg, clip.mp4 and meta.json.
 * Applies a total-size retention cap by deleting the oldest event directories first.
 */
class EventStore(private val context: Context) {
    private val prefs = Prefs(context)
    private val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun root(): File {
        val dirs = context.getExternalFilesDirs(null)
        val base = if (prefs.useSdCard && dirs.size > 1 && dirs[1] != null) dirs[1] else dirs[0]
        return File(base, "events").apply { mkdirs() }
    }

    fun createEvent(groups: List<DetectionGroup>, timestampMs: Long = System.currentTimeMillis()): EventRecord {
        val id = idFormat.format(Date(timestampMs))
        val dir = File(root(), id).apply { mkdirs() }
        return EventRecord(id, dir, timestampMs, groups)
    }

    fun saveSnapshot(event: EventRecord, bitmap: Bitmap) {
        // Write to a temp file and rename into place: the snapshot is re-saved mid-event
        // whenever a better frame arrives, and concurrent readers (alert attachment
        // upload, web server) must never see a partially written file.
        val tmp = File(event.dir, "snapshot.jpg.tmp")
        FileOutputStream(tmp).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (!tmp.renameTo(event.snapshotFile)) tmp.delete()
    }

    fun saveMeta(
        event: EventRecord,
        groups: List<DetectionGroup> = event.groups,
        description: String? = null,
        mode: String = event.mode,
    ) {
        val json = JSONObject().apply {
            put("id", event.id)
            put("timestampMs", event.timestampMs)
            put("groups", groups.joinToString(",") { it.name })
            put("mode", mode)
            if (description != null) put("description", description)
        }
        event.metaFile.writeText(json.toString())
    }

    /**
     * Adds a description to an event's meta.json without disturbing whatever groups/
     * timestamp were already recorded. Used when an async description (e.g. from an LLM)
     * arrives after the event's base meta.json has already been written.
     */
    fun updateDescription(event: EventRecord, description: String) {
        val existing = if (event.metaFile.exists()) {
            runCatching { JSONObject(event.metaFile.readText()) }.getOrNull()
        } else null
        val json = existing ?: JSONObject().apply {
            put("id", event.id)
            put("timestampMs", event.timestampMs)
            put("groups", event.groups.joinToString(",") { it.name })
            put("mode", event.mode)
        }
        json.put("description", description)
        event.metaFile.writeText(json.toString())
    }

    /** Reads back whatever description (if any) is already persisted for this event. */
    fun readDescription(event: EventRecord): String? {
        if (!event.metaFile.exists()) return null
        val json = runCatching { JSONObject(event.metaFile.readText()) }.getOrNull() ?: return null
        return if (json.has("description")) json.optString("description") else null
    }

    fun listEvents(): List<EventRecord> {
        val dirs = root().listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            val json = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@mapNotNull null
            val groups = json.optString("groups", "")
                .split(",").filter { it.isNotBlank() }
                .mapNotNull { name -> runCatching { DetectionGroup.valueOf(name) }.getOrNull() }
            EventRecord(
                json.optString("id", dir.name),
                dir,
                json.optLong("timestampMs", dir.lastModified()),
                groups,
                json.optString("mode", "video"),
            )
        }
    }

    /**
     * Deletes events older than the configured age limit, then deletes the oldest
     * remaining events until total size is under the configured storage cap.
     */
    fun enforceRetention() {
        val retentionDays = prefs.retentionDays
        if (retentionDays > 0) {
            val cutoffMs = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
            for (event in listEvents()) {
                if (event.timestampMs < cutoffMs && event.dir.deleteRecursively()) {
                    Log.i("EventStore", "Deleted event ${event.id} (older than $retentionDays days)")
                }
            }
        }

        val capBytes = prefs.retentionMb.toLong() * 1024 * 1024
        val events = listEvents() // newest first
        var total = events.sumOf { dirSize(it.dir) }
        if (total <= capBytes) return
        for (event in events.asReversed()) { // oldest first
            if (total <= capBytes) break
            val size = dirSize(event.dir)
            if (event.dir.deleteRecursively()) {
                total -= size
                Log.i("EventStore", "Deleted old event ${event.id} to enforce retention cap")
            }
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
