package com.securitycam.app.web

import com.securitycam.app.storage.EventRecord
import com.securitycam.app.storage.EventStore
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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
    private val latestFrameSeqProvider: () -> Long,
    private val cameraLabelProvider: () -> String,
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
                uri == "/live" -> newFixedLengthResponse(Response.Status.OK, "text/html", renderLivePage())
                uri == "/stream" -> {
                    val stream = MjpegStream(latestFrameProvider, latestFrameSeqProvider)
                    newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", stream)
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
        <h1>${cameraLabelProvider()}</h1>
        <p class="status ${if (armed) "armed" else "idle"}">${if (armed) "MONITORING" else "IDLE"}</p>
        <p><a href="/live"><img src="/live.jpg" style="max-width:100%;border-radius:6px" onerror="this.style.display='none'"/></a></p>
        <p><a href="/live">&#9654; Watch live</a></p>
        <h2>Recent events</h2>
        $rows
        </body></html>
        """.trimIndent()
    }

    private fun renderLivePage(): String {
        return """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:16px}
          h1{font-size:20px}
          a{color:#40C4FF}
        </style>
        </head><body>
        <p><a href="/">&larr; back</a></p>
        <h1>${cameraLabelProvider()} — live</h1>
        <p><img src="/stream" style="max-width:100%;border-radius:6px"/></p>
        </body></html>
        """.trimIndent()
    }

    companion object {
        private const val MJPEG_BOUNDARY = "securitycamframe"
    }

    /**
     * An MJPEG (multipart/x-mixed-replace) stream: blocks in [read] until a genuinely new
     * frame is available (tracked via [seqProvider], since [frameProvider] can return the
     * same array reference between polls), then emits it wrapped in the required
     * multipart framing. The connection stays open as long as the client keeps reading —
     * NanoHTTPD closes it (which surfaces here as an IOException from the client socket)
     * when the browser navigates away or the tab is closed.
     */
    private class MjpegStream(
        private val frameProvider: () -> ByteArray?,
        private val seqProvider: () -> Long,
    ) : InputStream() {
        private var lastSeq = -1L
        private var chunk: ByteArray = ByteArray(0)
        private var pos = 0
        private var framesWithNoData = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= chunk.size) {
                if (!fillNextChunk()) return -1
            }
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        /** Waits (polling) for a new frame and builds the next multipart chunk. Returns
         *  false to end the stream if no frame ever arrives (e.g. monitoring isn't armed
         *  and nothing is producing frames). */
        private fun fillNextChunk(): Boolean {
            while (true) {
                val seq = seqProvider()
                val bytes = frameProvider()
                if (bytes != null && seq != lastSeq) {
                    lastSeq = seq
                    framesWithNoData = 0
                    val header = "--$MJPEG_BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${bytes.size}\r\n\r\n"
                    chunk = header.toByteArray(Charsets.US_ASCII) + bytes + "\r\n".toByteArray(Charsets.US_ASCII)
                    pos = 0
                    return true
                }
                framesWithNoData++
                if (framesWithNoData > MAX_POLLS_WITHOUT_FRAME) return false
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }

        companion object {
            private const val POLL_INTERVAL_MS = 100L
            private const val MAX_POLLS_WITHOUT_FRAME = 300 // ~30s with no frame at all
        }
    }
}
