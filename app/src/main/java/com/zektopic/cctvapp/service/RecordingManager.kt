package com.zektopic.cctvapp.service

import android.content.Context
import android.os.StatFs
import com.zektopic.cctvapp.log.AppLog as Log
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
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the local-recording subsystem for [CctvServerService]: writing looping MP4 segments
 * into app-private storage (see [RecordingsStore]), rotating them on a timer, and enforcing
 * local-storage retention. Pulled out of the service because -- unlike the stateless
 * [CameraResolutionUtil]/[DeviceStatsUtil]/[ServiceNotificationUtil] helpers -- this owns
 * real session state (the in-flight segment's `File`, the rotation timer, and a dedicated
 * dispatcher), so it's a class the service constructs and owns rather than an `object`.
 *
 * [camera] returns null when the service's `rtspServerCamera` hasn't been created yet
 * (mirrors the `::rtspServerCamera.isInitialized` guards the service used to check
 * itself), and the three settings lambdas read live off the service's `AppPreferences`-
 * backed fields -- the same "wire callbacks in from the owner" pattern [WebServer] uses.
 */
class RecordingManager(
    private val context: Context,
    private val onMain: (() -> Unit) -> Unit,
    private val camera: () -> RtspServerCamera2?,
    private val recordingEnabled: () -> Boolean,
    private val recordSegmentMinutes: () -> Int,
    private val recordStorageThresholdPercent: () -> Int,
) {
    companion object {
        private const val TAG = "RecordingManager"

        /**
         * Suffix a segment's filename carries while RootEncoder is still writing it, so it
         * never matches [RecordingsStore.listRecordings]'s `.mp4` filter mid-write. Stripped
         * off ([finalizeSegmentFile]) once the segment is confirmed stopped. Replaces
         * MediaStore's `IS_PENDING` flag with the same semantics on plain files -- a
         * `.part` file left behind at startup ([cleanupOrphanedSegments]) means the previous
         * process died before it could rename it.
         */
        private const val IN_PROGRESS_SUFFIX = ".part"
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Coroutines-idiomatic equivalent of a single-thread executor: file bookkeeping never
    // runs concurrently with itself (a discard racing a finalize on the same segment would
    // be a real bug), but still runs off the IO thread pool rather than pinning a dedicated
    // thread.
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Schedules the one-shot "stop the current segment" tick; the *next* segment is only
    // started from recordListener once RecordController confirms the previous one actually
    // stopped (see beginNewSegment/finalizeCurrentSegment).
    private var rotationJob: Job? = null

    // Main-thread-only bookkeeping for the segment currently being written -- never touched
    // from ioDispatcher or a NanoHTTPD thread, so this doesn't need to be @Volatile.
    private var currentRecordingFile: File? = null

    // Set by [stop] right before it calls `cam.stopRecord()`, main-thread-only like the
    // field above. RootEncoder surfaces that self-inflicted stop as a `JobCancellationException`
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
            Log.e(TAG, "Recording error", e)
            onMain { finalizeCurrentSegment(rotateNext = shouldRotateNext()) }
        }
    }

    /**
     * Whether the segment that just ended should be immediately followed by a new one.
     * False whenever [stop] -- not a natural segment-timer rotation -- is why recording
     * ended, even if [recordingEnabled] is still on: `stop()` is called right before
     * `stopStreamAndRecording()` tears down and restarts the stream, and racing a fresh
     * segment into that teardown window only fights the restart, which calls
     * [startIfNeeded] itself once the new stream is up.
     */
    private fun shouldRotateNext(): Boolean {
        if (stoppingDeliberately) {
            stoppingDeliberately = false
            return false
        }
        return recordingEnabled()
    }

    /**
     * Begins a new segment if recording is turned on, the stream is actually up, and we
     * aren't already recording. Called both right after the stream starts and whenever a
     * finished segment's [RecordController.Listener] confirms it's safe to start the next
     * one -- see the class-level note on [rotationJob].
     */
    fun startIfNeeded() {
        val cam = camera() ?: return
        if (!recordingEnabled()) return
        if (!cam.isStreaming) return
        if (cam.isRecording) return
        beginNewSegment(cam)
    }

    /**
     * Cancels any pending rotation and stops the in-flight segment without starting another.
     *
     * `suspend` because [RecordController.stopRecord] (RootEncoder's `AsyncBaseRecordController`)
     * runs a `runBlocking { muxerJob.join() }` internally -- calling it straight from a
     * `Dispatchers.Main.immediate` coroutine (as this used to) blocks the real Android main
     * thread until the muxer job finishes, which silently wedges every other main-thread
     * coroutine in the service (the timestamp ticker included) if that join ever takes a
     * while -- e.g. the camera HAL having gotten into a bad state, observed for real on
     * device: `stopRecord()` never returned, and nothing downstream of the main thread ever
     * ran again for the rest of that process's life. `withContext(Dispatchers.IO)` moves the
     * block onto a background thread instead, same fix as [CctvServerService]'s timestamp
     * ticker already applies to its own CPU-temperature read.
     */
    suspend fun stop() {
        rotationJob?.cancel()
        val cam = camera() ?: return
        if (cam.isRecording) {
            stoppingDeliberately = true
            withContext(Dispatchers.IO) { cam.stopRecord() }
        }
    }

    /**
     * Call from the service's onDestroy to release the rotation timer and executor.
     *
     * Deliberately fire-and-forget rather than `suspend`: `onDestroy` isn't a coroutine and
     * must return promptly, and by this point nothing downstream depends on the segment
     * having actually finished stopping. Runs on its own scope, not [managerScope], so the
     * `managerScope.cancel()` right after doesn't cancel the stop it just launched.
     */
    fun shutdown() {
        rotationJob?.cancel()
        val cam = camera()
        if (cam?.isRecording == true) {
            stoppingDeliberately = true
            CoroutineScope(Dispatchers.IO).launch { runCatching { cam.stopRecord() } }
        }
        managerScope.cancel()
    }

    /**
     * Call once from the service's onCreate, before any segment can start. Deletes any
     * leftover [IN_PROGRESS_SUFFIX] files -- segments from a previous process that got
     * killed (crash, OOM-kill, force-stop) before [finalizeCurrentSegment] ran to rename
     * them. Without this they'd linger forever as a stray, unplayable partial file.
     */
    fun cleanupOrphanedSegments() {
        managerScope.launch(ioDispatcher) {
            try {
                val orphans = RecordingsStore.recordingsDir(context)
                    .listFiles { f -> f.isFile && f.name.endsWith(IN_PROGRESS_SUFFIX) }
                    ?: emptyArray()
                orphans.forEach { it.delete() }
                if (orphans.isNotEmpty()) Log.d(TAG, "Cleaned up ${orphans.size} orphaned recording segment(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up orphaned recording segments", e)
            }
        }
    }

    private fun beginNewSegment(liveCam: RtspServerCamera2) {
        managerScope.launch(ioDispatcher) {
            val file = try {
                createSegmentFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recording segment file", e)
                null
            }
            if (file == null) return@launch

            onMain {
                if (!liveCam.isStreaming || !recordingEnabled()) {
                    // Setting was turned off or the stream stopped while the file was
                    // being created -- discard it instead of leaving a stuck `.part` file.
                    managerScope.launch(ioDispatcher) { file.delete() }
                    return@onMain
                }
                try {
                    liveCam.startRecord(file.absolutePath, recordListener)
                    currentRecordingFile = file
                    ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = true) }
                    rotationJob = managerScope.launch {
                        delay((recordSegmentMinutes() * 60_000L).milliseconds)
                        // See stop()'s kdoc: stopRecord() blocks its caller on a
                        // runBlocking { muxerJob.join() }, so it must never run directly on
                        // this Dispatchers.Main.immediate coroutine.
                        if (liveCam.isRecording) withContext(Dispatchers.IO) { liveCam.stopRecord() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startRecord failed", e)
                    managerScope.launch(ioDispatcher) { file.delete() }
                }
            }
        }
    }

    /**
     * Runs once [RecordController] confirms the current segment actually stopped writing.
     * Finalizes the segment file on [ioDispatcher] and, if still wanted, chains straight
     * into the next segment -- the only place a new segment is started besides the initial
     * call from the service's `startStream`.
     */
    private fun finalizeCurrentSegment(rotateNext: Boolean) {
        val file = currentRecordingFile
        currentRecordingFile = null
        ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }

        if (file == null) {
            if (rotateNext) startIfNeeded()
            return
        }

        managerScope.launch(ioDispatcher) {
            finalizeSegmentFile(file)
            enforceRetention()
            if (rotateNext) onMain { startIfNeeded() }
        }
    }

    /** Runs on [ioDispatcher]. Builds the (not-yet-existing) path a new segment writes to. */
    private fun createSegmentFile(): File {
        val fileName = "CCTV_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())}.mp4$IN_PROGRESS_SUFFIX"
        return File(RecordingsStore.recordingsDir(context), fileName)
    }

    /** Runs on [ioDispatcher]. Drops [IN_PROGRESS_SUFFIX], making the segment visible in `/recordings`. */
    private fun finalizeSegmentFile(file: File) {
        val finalFile = File(file.parentFile, file.name.removeSuffix(IN_PROGRESS_SUFFIX))
        if (!file.renameTo(finalFile)) {
            Log.e(TAG, "Failed to finalize recording segment $file")
        }
    }

    /**
     * Loop recording: once local storage usage crosses [recordStorageThresholdPercent],
     * deletes the oldest finished segments one at a time -- rechecking usage after each
     * delete -- until it drops back under the threshold or there's nothing left to delete.
     * Runs on [ioDispatcher] after each segment finalizes, so a server left running
     * indefinitely doesn't fill the device's storage.
     */
    private fun enforceRetention() {
        try {
            val dir = RecordingsStore.recordingsDir(context)
            val files = ArrayDeque(
                dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                    ?.sortedBy { it.lastModified() }
                    ?: emptyList()
            )

            val statFs = StatFs(dir.path)
            while (files.isNotEmpty() && DeviceStatsUtil.usedPercent(statFs) > recordStorageThresholdPercent()) {
                files.removeFirst().delete()
                statFs.restat(dir.path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce recording retention", e)
        }
    }
}
