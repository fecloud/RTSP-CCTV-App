package com.zektopic.cctvapp.web

import android.content.Context
import android.os.SystemClock
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.service.GalleryRecordingManager
import com.zektopic.cctvapp.settings.ServiceSettings
import com.zektopic.cctvapp.settings.SettingUpdateHandler
import com.zektopic.cctvapp.settings.SettingsRepository
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** One saved recording under `Movies/CCTVApp/`, as listed on the `/recordings` page. */
data class RecordingEntry(val id: Long, val displayName: String, val sizeBytes: Long, val dateAddedMillis: Long)

class WebServer(
    private val context: Context,
    private val ipAddress: String,
    private val imageProvider: () -> ByteArray?,
    private val onSwitchCamera: () -> Unit,
    private val onStartStream: () -> Unit,
    private val onStopStream: () -> Unit,
    private val isStreaming: () -> Boolean,
    private val getZoomRange: () -> Pair<Float, Float>,
    private val galleryRecordingManager: GalleryRecordingManager,
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        const val PORT = 8081

        /** Bind whatever port the OS hands out. Only used by tests. */
        const val EPHEMERAL_PORT = 0
        private const val REALM = "CCTV Dashboard"
    }

    /** Same data source [com.zektopic.cctvapp.MainActivity] and the service read/write. */
    private val settings: ServiceSettings get() = SettingsRepository.current
    private val settingUpdateHandler by lazy { SettingUpdateHandler(context) }

    /** Set at construction, i.e. service start -- backs the `/status` `uptimeMillis` field. */
    private val startElapsedRealtimeMs = SystemClock.elapsedRealtime()

    override fun serve(session: IHTTPSession): Response {
        return try {
            val headers = session.headers ?: emptyMap()

            // Reject cross-site requests before doing any work. Without this, a page on
            // the internet can drive this server from inside the victim's LAN.
            if (!WebAuth.isOriginAllowed(headers["origin"], headers["host"])) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Cross-origin request rejected"
                )
            }

            if (!WebAuth.isAuthorized(
                    settings.webAuthEnabled, settings.authUsername, settings.authPassword, headers["authorization"]
                )
            ) {
                return unauthorized()
            }

            // Mutations should be POSTed. NanoHTTPD only populates `parameters` for a
            // form-encoded body once the body has been parsed, so do that up front and
            // GET/POST become interchangeable for every /action/* handler below.
            if (session.method == Method.POST || session.method == Method.PUT) {
                session.parseBody(HashMap())
            }

            processRequest(session)
        } catch (e: Exception) {
            Log.e("WebServer", "Request failed: ${session.uri}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
        }
    }

    private fun unauthorized(): Response {
        val response = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Authentication required"
        )
        response.addHeader("WWW-Authenticate", "Basic realm=\"$REALM\", charset=\"UTF-8\"")
        return response
    }

    private fun processRequest(session: IHTTPSession): Response {
        val uri = session.uri

        if (uri == "/shot.jpg") {
            val imageBytes = imageProvider()
            return if (imageBytes != null) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(imageBytes), imageBytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Camera not ready")
            }
        }

        if (uri == "/action/switch-camera") {
            onSwitchCamera()
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Switched")
        }
        
        if (uri == "/action/toggle-stream") {
            if (isStreaming()) {
                onStopStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Stopped")
            } else {
                onStartStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Started")
            }
        }

        if (uri == "/action/set-codec") {
            val codec = session.parameters["codec"]?.get(0) ?: "H264"
            settingUpdateHandler.handleCodec(codec)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Codec Updated")
        }

        if (uri == "/action/set-resolution") {
            val w = session.parameters["w"]?.get(0)?.toIntOrNull() ?: 640
            val h = session.parameters["h"]?.get(0)?.toIntOrNull() ?: 480
            settingUpdateHandler.handleResolution(w, h)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Resolution Updated")
        }

        // Generic setting update endpoint
        if (uri == "/action/set-setting") {
            val key = session.parameters["key"]?.get(0) ?: ""
            val value = session.parameters["value"]?.get(0) ?: ""
            if (key.isNotEmpty()) {
                settingUpdateHandler.handle(key, value)
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Setting Updated: $key")
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing key")
        }

        // Auth update endpoint
        if (uri == "/action/set-auth") {
            val enabled = session.parameters["enabled"]?.get(0)?.toBoolean() ?: false
            val username = session.parameters["username"]?.get(0) ?: ""
            val password = session.parameters["password"]?.get(0) ?: ""
            settingUpdateHandler.handleAuth(enabled, username, password)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Auth Updated")
        }

        // JSON status endpoint
        if (uri == "/status") {
            val streaming = isStreaming()
            val s = settings
            val resolutionOptions = CameraResolutionUtil.getSupportedResolutions(context)
                .joinToString(",") { "\"${it.first}x${it.second}\"" }
            val rtspUrl = buildRtspUrl()
            val json = """{
                "streaming":$streaming,
                "codec":"${s.videoCodec}",
                "activeCodec":"${s.activeCodec}",
                "resolution":"${s.videoWidth}x${s.videoHeight}",
                "resolutionOptions":[$resolutionOptions],
                "authEnabled":${s.authEnabled},
                "username":"${escapeJson(s.authUsername)}",
                "rtspUrl":"${escapeJson(rtspUrl)}",
                "showTimestamp":${s.showTimestamp},
                "timestampPosition":"${s.timestampPosition}",
                "timestampSize":"${s.timestampSize}",
                "flashlightEnabled":${s.flashlightEnabled},
                "nightModeEnabled":${s.nightModeEnabled},
                "verticalFlipEnabled":${s.verticalFlipEnabled},
                "zoomLevel":${s.zoomLevel},
                "zoomMin":${getZoomRange().first},
                "zoomMax":${getZoomRange().second},
                "bitrateKbps":${s.bitrateKbps},
                "batteryLevel":${DeviceStatsUtil.getBatteryLevel(context)},
                "isCharging":${DeviceStatsUtil.isCharging(context)},
                "wifiStrength":${DeviceStatsUtil.getWifiStrength(context)},
                "cpuTempCelsius":${DeviceStatsUtil.getCpuTemperatureCelsius()?.let { "%.1f".format(it) } ?: "null"},
                "batteryTempCelsius":${DeviceStatsUtil.getBatteryTemperatureCelsius(context)?.let { "%.1f".format(it) } ?: "null"},
                "uptimeMillis":${SystemClock.elapsedRealtime() - startElapsedRealtimeMs},
                "webAuthEnabled":${s.webAuthEnabled},
                "recordToGalleryEnabled":${s.recordToGalleryEnabled},
                "recordSegmentMinutes":${s.recordSegmentMinutes},
                "isRecordingToGallery":${galleryRecordingManager.isRecordingToGallery}
            }""".trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        if (uri == "/" || uri == "/greet.html") {
            return newFixedLengthResponse(buildDashboardHtml())
        }

        if (uri == "/recordings") {
            return newFixedLengthResponse(buildRecordingsHtml(galleryRecordingManager.listRecordings()))
        }

        // Streams/downloads one recording. Honors a single-range `Range` header (what
        // browsers send when the user drags a <video>'s seek bar) so playback can jump
        // around instead of only playing straight through from the start.
        if (uri == "/recording.mp4") {
            val id = session.parameters["id"]?.get(0)?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
            val entry = galleryRecordingManager.listRecordings().find { it.id == id }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Recording not found")
            val stream = galleryRecordingManager.openRecordingStream(id)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Recording not found")
            val download = session.parameters["download"]?.get(0) == "1"
            val disposition = if (download) "attachment" else "inline"
            val totalLength = entry.sizeBytes

            val rangeHeader = session.headers?.get("range")
            val range = if (rangeHeader != null && totalLength > 0) parseRange(rangeHeader, totalLength) else null
            val response = if (rangeHeader != null && range == null) {
                stream.close()
                newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range").also {
                    it.addHeader("Content-Range", "bytes */$totalLength")
                }
            } else if (range != null) {
                val (start, end) = range
                skipFully(stream, start)
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", stream, end - start + 1).also {
                    it.addHeader("Content-Range", "bytes $start-$end/$totalLength")
                }
            } else {
                newFixedLengthResponse(Response.Status.OK, "video/mp4", stream, totalLength)
            }
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Disposition", "$disposition; filename=\"${entry.displayName}\"")
            return response
        }

        // Legacy redirect
        if (uri.startsWith("/server/")) {
             if (uri.endsWith("on")) {
                 onStartStream()
             } else {
                 onStopStream()
             }
             val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
             response.addHeader("Location", "/")
             return response
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Parses a single-range `Range: bytes=...` header value (`start-end`, `start-`, or
     * `-suffixLength`) into an inclusive `(start, end)` pair clamped to [totalLength].
     * Multi-range requests aren't supported -- browsers don't send them for video
     * seeking -- so only the first range is honored. Returns null for anything
     * malformed or unsatisfiable.
     */
    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(",")
        val parts = spec.split("-", limit = 2)
        if (parts.size != 2) return null
        val startPart = parts[0].trim()
        val endPart = parts[1].trim()
        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            val suffixLength = endPart.toLongOrNull() ?: return null
            if (suffixLength <= 0) return null
            start = (totalLength - suffixLength).coerceAtLeast(0)
            end = totalLength - 1
        } else {
            start = startPart.toLongOrNull() ?: return null
            end = if (endPart.isEmpty()) totalLength - 1 else (endPart.toLongOrNull() ?: return null)
        }
        if (start !in 0..end || start >= totalLength) return null
        return start to end.coerceAtMost(totalLength - 1)
    }

    /** [InputStream.skip] isn't guaranteed to skip the full amount in one call. */
    private fun skipFully(stream: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun buildRtspUrl(): String {
        val s = settings
        return if (s.authEnabled && s.authUsername.isNotEmpty() && s.authPassword.isNotEmpty()) {
            "rtsp://${s.authUsername}:${s.authPassword}@$ipAddress:8554/stream"
        } else {
            "rtsp://$ipAddress:8554/stream"
        }
    }

    private fun buildDashboardHtml(): String {
        // Everything interpolated below is escaped: the username and password are
        // settable over the network and end up inside buildRtspUrl().
        val rtspUrl = WebAuth.escapeHtml(buildRtspUrl())
        val safeIp = WebAuth.escapeHtml(ipAddress)
        val authBadgeDisplay = if (settings.authEnabled && settings.authUsername.isNotEmpty()) "inline" else "none"
        return dashboardTemplate
            .replace("{{IP}}", safeIp)
            .replace("{{RTSP_URL}}", rtspUrl)
            .replace("{{AUTH_BADGE_DISPLAY}}", authBadgeDisplay)
            .replace("{{PORT}}", PORT.toString())
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(Locale.US, value, units[unitIndex])
    }

    private fun buildRecordingsHtml(recordings: List<RecordingEntry>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val rows = if (recordings.isEmpty()) {
            """<div class="empty-state">No recordings saved yet.</div>"""
        } else {
            recordings.joinToString("\n") { entry ->
                val safeName = WebAuth.escapeHtml(entry.displayName)
                val safeDate = WebAuth.escapeHtml(dateFormat.format(java.util.Date(entry.dateAddedMillis)))
                """
                <a class="setting-row" href="/recording.mp4?id=${entry.id}" target="_blank">
                    <div>
                        <span class="setting-label">$safeName</span>
                        <div class="setting-sublabel">${formatBytes(entry.sizeBytes)} &middot; $safeDate</div>
                    </div>
                </a>
                """.trimIndent()
            }
        }
        return recordingsTemplate
            .replace("{{IP}}", WebAuth.escapeHtml(ipAddress))
            .replace("{{ROWS}}", rows)
    }

    /**
     * Raw HTML/CSS/JS templates, read once from `assets/web/` and reused across
     * requests -- everything in them is static except a handful of `{{TOKEN}}`
     * placeholders, substituted per-request in [buildDashboardHtml] /
     * [buildRecordingsHtml]. Kept as page-shaped files in `assets/` rather than Kotlin
     * string literals so the markup/CSS/JS can be edited (and diffed) as what it is.
     */
    private val dashboardTemplate: String by lazy { loadAsset("web/dashboard.html") }
    private val recordingsTemplate: String by lazy { loadAsset("web/recordings.html") }

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}