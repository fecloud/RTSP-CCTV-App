package com.zektopic.cctvapp.service

import android.content.Context
import android.os.StatFs
import com.zektopic.cctvapp.log.AppLog as Log
import com.pedro.library.base.recording.RecordController
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingManager(
    private val context: Context,
    private val camera: () -> SharedCameraStream?
) {
    companion object {
        private const val TAG = "RecordingManager"
    }
    private val recordingEnabled get() = ServiceStateRepository.settings.recordToGalleryEnabled

    private val recordSegmentMinutes get() = ServiceStateRepository.settings.recordSegmentMinutes

    private val recordStorageThresholdPercent get() = ServiceStateRepository.settings.recordStorageThresholdPercent

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var controller: SeamlessMp4RecordController? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.d(TAG, "Record onStatusChange status:$status")
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Recording error", e)
        }
    }

    /**
     * Installs [controller] on [cam], creating it the first time this is called. Must be
     * called before [cam]'s first `startStream()`/`prepareAudio()` -- RootEncoder's shared
     * audio encoder starts with the stream, not with recording, and fires its one-time
     * `setAudioFormat` callback almost immediately once it does (fast enough that calling this
     * lazily from [startIfNeeded], even moments after `startStream()` returns, was still
     * consistently too late and left every recording's audio track empty despite RTSP
     * streaming, which bypasses `RecordController` entirely, having audio the whole time).
     * A no-op after the first call, so it's safe for [CctvServerService] to call this
     * unconditionally right after constructing its `SharedCameraStream`.
     */
    fun attachTo(cam: SharedCameraStream) {
        controller?.let { return }
        if (cam.isStreaming) {
            // The one call site this ordering actually depends on (CctvServerService
            // constructing SharedCameraStream) always calls this first -- if that's ever no
            // longer true, this is the only signal a future caller gets, since the race it
            // misses (RootEncoder's one-time setAudioFormat callback) fails silently otherwise.
            Log.w(TAG, "attachTo() called after the camera was already streaming; a recording started before this may be missing its audio track")
        }
        val created = SeamlessMp4RecordController(
            segmentDurationMinutes = { recordSegmentMinutes },
            nextSegmentFile = { createSegmentFile() },
        ) {
            managerScope.launch(ioDispatcher) { enforceRetention() }
        }
        controller = created
        cam.setRecordController(created)
    }

    fun startIfNeeded() {
        val cam = camera() ?: return
        if (!recordingEnabled) return
        if (!cam.isStreaming) return
        if (cam.isRecording) return

        attachTo(cam)
        managerScope.launch(ioDispatcher) {
            val firstFile = try {
                createSegmentFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recording segment file", e)
                return@launch
            }
            try {
                cam.startRecord(firstFile.absolutePath, listener = recordListener)
            } catch (e: Exception) {
                Log.e(TAG, "startRecord failed", e)
                return@launch
            }
            ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = true) }
        }
    }

    suspend fun stop() {
        val cam = camera()
        if (cam?.isRecording == true) {
            withContext(Dispatchers.IO) { cam.stopRecord() }
        }
        ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }
    }

    fun shutdown() {
        val cam = camera()
        if (cam?.isRecording == true) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { cam.stopRecord() } }
        }
        managerScope.cancel()
    }

    private fun createSegmentFile(): File {
        val fileName = "CCTV_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())}.mp4"
        return File(RecordingsStore.recordingsDir(context), fileName)
    }

    /**
     * Deletes the oldest segments while the recordings directory's own total size exceeds
     * [recordStorageThresholdPercent] of its partition's total capacity. Measured against the
     * directory, not the partition's overall usage, so space taken by the system and other
     * apps doesn't count against the recording budget. The newest file is never deleted:
     * during a segment rotation it's the segment currently being written.
     */
    private fun enforceRetention() {
        try {
            val dir = RecordingsStore.recordingsDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()
            if (files.size <= 1) return

            val totalBytes = StatFs(dir.path).totalBytes
            if (totalBytes <= 0L) return
            val threshold = recordStorageThresholdPercent
            val budgetBytes = totalBytes / 100 * threshold
            var dirBytes = files.sumOf { it.length() }

            var deletedCount = 0
            for (file in files.dropLast(1)) {
                if (dirBytes <= budgetBytes) break
                val size = file.length()
                val deleted = file.delete()
                Log.d(
                    TAG,
                    "Retention: dirBytes=$dirBytes > budget=$budgetBytes ($threshold% of $totalBytes), " +
                        "deleted=$deleted file=${file.name} size=$size"
                )
                if (deleted) {
                    deletedCount++
                    dirBytes -= size
                }
            }
            if (deletedCount > 0) {
                Log.d(TAG, "Retention: removed $deletedCount old segment(s); recordings now $dirBytes bytes, budget $budgetBytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce recording retention", e)
        }
    }
}
