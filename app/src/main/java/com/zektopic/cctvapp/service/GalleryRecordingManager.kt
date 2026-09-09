package com.zektopic.cctvapp.service

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import com.zektopic.cctvapp.log.AppLog as Log
import androidx.core.content.ContextCompat
import com.pedro.library.base.recording.RecordController
import com.pedro.rtspserver.RtspServerCamera2
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.settings.ServiceStateRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the "record to gallery" subsystem for [CctvServerService]: writing looping MP4
 * segments into the shared Movies collection via MediaStore, rotating them on a timer,
 * and enforcing local-storage retention. Pulled out of the service because -- unlike the
 * stateless [CameraResolutionUtil]/[DeviceStatsUtil]/[ServiceNotificationUtil] helpers --
 * this owns real session state (the in-flight segment's `Uri`/fd, the rotation timer, and
 * a dedicated executor), so it's a class the service constructs and owns rather than an
 * `object`.
 *
 * [camera] returns null when the service's `rtspServerCamera` hasn't been created yet
 * (mirrors the `::rtspServerCamera.isInitialized` guards the service used to check
 * itself), and the three settings lambdas read live off the service's `AppPreferences`-
 * backed fields -- the same "wire callbacks in from the owner" pattern [WebServer] uses.
 */
class GalleryRecordingManager(
    private val context: Context,
    private val onMain: (() -> Unit) -> Unit,
    private val camera: () -> RtspServerCamera2?,
    private val recordToGalleryEnabled: () -> Boolean,
    private val recordSegmentMinutes: () -> Int,
    private val recordStorageThresholdPercent: () -> Int,
) {
    companion object {
        private const val TAG = "GalleryRecordingManager"
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Coroutines-idiomatic equivalent of a single-thread executor: MediaStore work never
    // runs concurrently with itself (discardGalleryEntry racing finalizeGalleryVideoEntry
    // on the same row would be a real bug), but still runs off the IO thread pool rather
    // than pinning a dedicated thread.
    private val mediaStoreDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Schedules the one-shot "stop the current segment" tick; the *next* segment is only
    // started from recordListener once RecordController confirms the previous one actually
    // stopped (see beginNewSegment/finalizeCurrentSegment).
    private var rotationJob: Job? = null

    // Main-thread-only bookkeeping for the segment currently being written -- never touched
    // from mediaStoreDispatcher or a NanoHTTPD thread, so these don't need to be @Volatile.
    private var currentRecordingUri: Uri? = null
    private var currentRecordingPfd: ParcelFileDescriptor? = null

    // Set by [stop] right before it calls `cam.stopRecord()`, main-thread-only like the
    // fields above. RootEncoder surfaces that self-inflicted stop as a `JobCancellationException`
    // through `onError` (rather than only through `onStatusChange(STOPPED)`), which is
    // indistinguishable from a genuine recording failure unless we remember we asked for it.
    private var stoppingDeliberately = false

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            if (status == RecordController.Status.STOPPED) {
                onMain { finalizeCurrentSegment(rotateNext = shouldRotateNext()) }
            }
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Gallery recording error", e)
            onMain { finalizeCurrentSegment(rotateNext = shouldRotateNext()) }
        }
    }

    /**
     * Whether the segment that just ended should be immediately followed by a new one.
     * False whenever [stop] -- not a natural segment-timer rotation -- is why recording
     * ended, even if [recordToGalleryEnabled] is still on: `stop()` is called right before
     * `stopStreamAndRecording()` tears down and restarts the stream, and racing a fresh
     * MediaStore insert into that teardown window only fights the restart, which calls
     * [startIfNeeded] itself once the new stream is up.
     */
    private fun shouldRotateNext(): Boolean {
        if (stoppingDeliberately) {
            stoppingDeliberately = false
            return false
        }
        return recordToGalleryEnabled()
    }

    /**
     * Begins a new segment if recording is turned on, the stream is actually up, and we
     * aren't already recording. Called both right after the stream starts and whenever a
     * finished segment's [RecordController.Listener] confirms it's safe to start the next
     * one -- see the class-level note on [rotationJob].
     */
    fun startIfNeeded() {
        val cam = camera() ?: return
        if (!recordToGalleryEnabled()) return
        if (!cam.isStreaming) return
        if (cam.isRecording) return
        beginNewSegment(cam)
    }

    /** Cancels any pending rotation and stops the in-flight segment without starting another. */
    fun stop() {
        rotationJob?.cancel()
        val cam = camera() ?: return
        if (cam.isRecording) {
            stoppingDeliberately = true
            cam.stopRecord()
        }
    }

    /** Call from the service's onDestroy to release the rotation timer and executor. */
    fun shutdown() {
        stop()
        managerScope.cancel()
    }

    /**
     * Call once from the service's onCreate, before any segment can start. Deletes rows
     * matching [RecordingsGallery.orphanSelection] -- segments from a previous process that
     * got killed (crash, OOM-kill, force-stop) before [finalizeCurrentSegment] or
     * [discardGalleryEntry] ran, left with no recorded data. Without this they linger in
     * `/recordings` forever as a permanent "0 B" entry.
     */
    fun cleanupOrphanedSegments() {
        managerScope.launch(mediaStoreDispatcher) {
            try {
                val (selection, selectionArgs) = RecordingsGallery.orphanSelection()
                val ids = mutableListOf<Long>()
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    selection, selectionArgs, null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    while (cursor.moveToNext()) ids.add(cursor.getLong(idIndex))
                }
                for (id in ids) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    context.contentResolver.delete(uri, null, null)
                }
                if (ids.isNotEmpty()) Log.d(TAG, "Cleaned up ${ids.size} orphaned recording segment(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up orphaned recording segments", e)
            }
        }
    }

    /**
     * One finished (or about-to-be-inserted) gallery segment. Exactly one of [pfd] / [path]
     * is set: scoped storage (API 29+) has no filesystem path and must write through a
     * MediaStore-opened [ParcelFileDescriptor]; pre-29 has a real [File] path and uses
     * RootEncoder's string-path `startRecord` overload instead -- the `FileDescriptor`
     * overload calls `MediaMuxer(FileDescriptor, int)`, which requires API 26 and would
     * violate this app's minSdk 23 if it were the only path taken.
     */
    private class GalleryEntry(val uri: Uri, val pfd: ParcelFileDescriptor?, val path: String?)

    private fun beginNewSegment(liveCam: RtspServerCamera2) {
        managerScope.launch(mediaStoreDispatcher) {
            val entry = try {
                insertGalleryVideoEntry()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create gallery recording entry", e)
                null
            }
            if (entry == null) return@launch

            onMain {
                if (!liveCam.isStreaming || !recordToGalleryEnabled()) {
                    // Setting was turned off or the stream stopped while the insert was
                    // in flight -- discard the just-created (empty) MediaStore row instead
                    // of leaving a stuck IS_PENDING entry in the gallery.
                    managerScope.launch(mediaStoreDispatcher) { discardGalleryEntry(entry) }
                    return@onMain
                }
                try {
                    val pfd = entry.pfd
                    if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        liveCam.startRecord(pfd.fileDescriptor, recordListener)
                    } else if (entry.path != null) {
                        liveCam.startRecord(entry.path, recordListener)
                    } else {
                        throw IllegalStateException("Gallery entry has neither a usable fd nor a path")
                    }
                    currentRecordingUri = entry.uri
                    currentRecordingPfd = pfd
                    ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = true) }
                    rotationJob = managerScope.launch {
                        delay((recordSegmentMinutes() * 60_000L).milliseconds)
                        if (liveCam.isRecording) liveCam.stopRecord()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startRecord failed", e)
                    managerScope.launch(mediaStoreDispatcher) { discardGalleryEntry(entry) }
                }
            }
        }
    }

    /**
     * Runs once [RecordController] confirms the current segment actually stopped writing.
     * Finalizes the gallery entry on [mediaStoreDispatcher] and, if still wanted, chains
     * straight into the next segment -- the only place a new segment is started besides
     * the initial call from the service's `startStream`.
     */
    private fun finalizeCurrentSegment(rotateNext: Boolean) {
        val uri = currentRecordingUri
        val pfd = currentRecordingPfd
        currentRecordingUri = null
        currentRecordingPfd = null
        ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }

        if (uri == null) {
            if (rotateNext) startIfNeeded()
            return
        }

        managerScope.launch(mediaStoreDispatcher) {
            // Only set for the scoped-storage (API 29+) path -- the pre-29 path records
            // straight through RootEncoder's string-path overload and never opens one.
            try {
                pfd?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close recording fd", e)
            }
            finalizeGalleryVideoEntry(uri)
            enforceRetention()
            if (rotateNext) onMain { startIfNeeded() }
        }
    }

    /** Runs on [mediaStoreDispatcher]. Creates the MediaStore row and opens it for writing. */
    @Suppress("DEPRECATION") // MediaStore.Video.Media.DATA / Environment.getExternalStoragePublicDirectory: pre-Q only path
    private fun insertGalleryVideoEntry(): GalleryEntry? {
        val fileName = "CCTV_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RecordingsGallery.RECORDING_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
                Log.e(TAG, "MediaStore insert returned null")
                return null
            }
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
                resolver.delete(uri, null, null)
                Log.e(TAG, "openFileDescriptor returned null")
                return null
            }
            return GalleryEntry(uri, pfd, path = null)
        }

        // Pre-API 29: no scoped storage, no RELATIVE_PATH/IS_PENDING columns. Requires
        // WRITE_EXTERNAL_STORAGE, which MainActivity only requests on these OS versions.
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Gallery recording needs WRITE_EXTERNAL_STORAGE on this OS version")
            return null
        }
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CCTVApp")
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            Log.e(TAG, "Failed to create $moviesDir")
            return null
        }
        val file = File(moviesDir, fileName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATA, file.absolutePath)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null")
            return null
        }
        // RootEncoder's string-path startRecord overload creates and writes the file
        // itself (via MediaMuxer(String, int), available since API 18), so no
        // ParcelFileDescriptor needs to be opened on this pre-Q path.
        return GalleryEntry(uri, pfd = null, path = file.absolutePath)
    }

    /** Runs on [mediaStoreDispatcher]. Marks a finished segment visible in the gallery. */
    private fun finalizeGalleryVideoEntry(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            } else {
                val path = queryDataPath(uri)
                if (path != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize gallery entry $uri", e)
        }
    }

    /** Runs on [mediaStoreDispatcher]. Removes a row that never got any recorded data. */
    private fun discardGalleryEntry(entry: GalleryEntry) {
        try {
            entry.pfd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close discarded recording fd", e)
        }
        try {
            context.contentResolver.delete(entry.uri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discard gallery entry ${entry.uri}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryDataPath(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /**
     * Loop recording: once local storage usage crosses [recordStorageThresholdPercent],
     * deletes the oldest finished segments under [RecordingsGallery.RECORDING_RELATIVE_PATH]
     * one at a time -- rechecking usage after each delete -- until it drops back under
     * the threshold or there's nothing left to delete. Runs on [mediaStoreDispatcher]
     * after each segment finalizes, so a server left running indefinitely doesn't fill
     * the device's storage.
     */
    private fun enforceRetention() {
        try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"
            val (selection, selectionArgs) = RecordingsGallery.selection()

            val ids = ArrayDeque<Long>()
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) ids.add(cursor.getLong(idIndex))
            }

            val statFs = StatFs(Environment.getExternalStorageDirectory().path)
            while (ids.isNotEmpty() && DeviceStatsUtil.usedPercent(statFs) > recordStorageThresholdPercent()) {
                val id = ids.removeFirst()
                val itemUri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                context.contentResolver.delete(itemUri, null, null)
                statFs.restat(Environment.getExternalStorageDirectory().path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce recording retention", e)
        }
    }
}
