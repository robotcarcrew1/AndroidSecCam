package com.securitycam.app.web

import com.securitycam.app.storage.EventRecord
import com.securitycam.app.storage.EventStore
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal LAN-only status page: shows armed state, recent events with snapshot
 * thumbnails, and lets you download the recorded clip or the latest live frame.
 * No auth — intended for a trusted home WiFi only.
 */
class WebServer(
    port: Int,
    private val eventStore: EventStore,
    private val isArmedProvider: () -> Boolean,
    private val latestFrameProvider: () -> ByteArray?,
) : NanoHTTPD(port) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", renderIndex())
                uri == "/live.jpg" -> {
                    val bytes = latestFrameProvider()
                    if (bytes != null) newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
                    else newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No frame yet")
                }
                uri.startsWith("/snapshot/") -> serveEventFile(uri.removePrefix("/snapshot/"), snapshot = true)
                uri.startsWith("/clip/") -> serveEventFile(uri.removePrefix("/clip/"), snapshot = false)
                uri.startsWith("/event/") -> serveEventDetail(uri.removePrefix("/event/"))
                uri.startsWith("/frame/") -> serveFrame(uri.removePrefix("/frame/"))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun serveEventFile(id: String, snapshot: Boolean): Response {
        val event = eventStore.listEvents().firstOrNull { it.id == id }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown event")
        val file = if (snapshot) event.snapshotFile else event.clipFile
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File missing")
        val mime = if (snapshot) "image/jpeg" else "video/mp4"
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
    }

    private fun serveFrame(path: String): Response {
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Bad path")
        val (id, name) = parts
        if (!name.matches(Regex("frame_\\d+\\.jpg"))) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid frame name")
        }
        val event = eventStore.listEvents().firstOrNull { it.id == id }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown event")
        val file = File(event.dir, name)
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File missing")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(file), file.length())
    }

    private fun serveEventDetail(id: String): Response {
        val event = eventStore.listEvents().firstOrNull { it.id == id }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown event")
        return newFixedLengthResponse(Response.Status.OK, "text/html", renderEventDetail(event))
    }

    private fun renderEventDetail(event: EventRecord): String {
        val groups = event.groups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        val time = timeFormat.format(Date(event.timestampMs))
        val body = if (event.mode == "burst") {
            val frames = event.dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
                ?.sortedBy { it.name } ?: emptyList()
            val thumbs = frames.joinToString("\n") { f ->
                "<a href=\"/frame/${event.id}/${f.name}\"><img src=\"/frame/${event.id}/${f.name}\" loading=\"lazy\"/></a>"
            }
            """
            <p>No video clip (this phone's camera hardware can't record video while detecting at the same
            time) — ${frames.size} still frames captured instead, about 1 per second:</p>
            <div class="frames">$thumbs</div>
            """.trimIndent()
        } else if (event.clipFile.exists()) {
            """<p><video controls style="max-width:100%" src="/clip/${event.id}"></video></p>
               <p><a href="/clip/${event.id}">download clip</a></p>"""
        } else {
            "<p>No clip file found.</p>"
        }
        return """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:16px}
          h1{font-size:20px}
          .frames{display:flex;flex-wrap:wrap;gap:6px}
          .frames img{width:100px;height:100px;object-fit:cover;border-radius:4px;background:#222}
          a{color:#40C4FF}
        </style>
        </head><body>
        <p><a href="/">&larr; back to all events</a></p>
        <h1>$groups — $time</h1>
        <p><img src="/snapshot/${event.id}" style="max-width:100%;border-radius:6px"/></p>
        $body
        </body></html>
        """.trimIndent()
    }

    private fun renderIndex(): String {
        val armed = isArmedProvider()
        val events = eventStore.listEvents().take(50)
        val rows = events.joinToString("\n") { e ->
            val groups = e.groups.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            val time = timeFormat.format(Date(e.timestampMs))
            val clipLink = if (e.mode == "burst") {
                "no video (camera limitation) — still frames captured"
            } else {
                "video clip"
            }
            """
            <div class="event">
              <a href="/event/${e.id}"><img src="/snapshot/${e.id}" loading="lazy"/></a>
              <div><b>$groups</b><br/>$time<br/><a href="/event/${e.id}">view event</a> ($clipLink)</div>
            </div>
            """.trimIndent()
        }
        return """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:16px}
          h1{font-size:20px} .status{padding:6px 10px;border-radius:6px;display:inline-block}
          .armed{background:#1b5e20} .idle{background:#555}
          .event{display:flex;gap:12px;align-items:center;border-bottom:1px solid #333;padding:8px 0}
          .event img{width:120px;height:90px;object-fit:cover;border-radius:4px;background:#222}
          a{color:#40C4FF}
        </style>
        <meta http-equiv="refresh" content="15">
        </head><body>
        <h1>SecurityCam</h1>
        <p class="status ${if (armed) "armed" else "idle"}">${if (armed) "MONITORING" else "IDLE"}</p>
        <p><img src="/live.jpg" style="max-width:100%;border-radius:6px" onerror="this.style.display='none'"/></p>
        <h2>Recent events</h2>
        $rows
        </body></html>
        """.trimIndent()
    }
}
