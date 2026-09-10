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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class RecordingManager(
    private val context: Context,
    private val camera: RtspServerCamera2?
) {
    companion object {
        private const val TAG = "RecordingManager"
    }
    private val recordingEnabled get() = ServiceStateRepository.settings.recordToGalleryEnabled

    private val recordSegmentMinutes get() = ServiceStateRepository.settings.recordSegmentMinutes

    private val recordStorageThresholdPercent get() = ServiceStateRepository.settings.recordStorageThresholdPercent

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var loopJob: Job? = null

    private var currentRecordingFile: File? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.e(TAG, "Record onStatusChange status:$status")
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Recording error", e)
        }
    }

    fun startIfNeeded() {
        val cam = camera ?: return
        if (!recordingEnabled) return
        if (!cam.isStreaming) return
        if (loopJob?.isActive == true) return
        loopJob = managerScope.launch { recordingLoop(cam) }
    }

    private suspend fun recordingLoop(liveCam: RtspServerCamera2) {
        while (recordingEnabled && liveCam.isStreaming) {
            val file = withContext(ioDispatcher) {
                try {
                    createSegmentFile()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create recording segment file", e)
                    null
                }
            } ?: break

            try {
                liveCam.startRecord(file.absolutePath, recordListener)
            } catch (e: Exception) {
                Log.e(TAG, "startRecord failed", e)
                withContext(ioDispatcher) { file.delete() }
                break
            }

            currentRecordingFile = file
            ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = true) }

            delay((recordSegmentMinutes * 60_000L).milliseconds)

            if (liveCam.isRecording) withContext(Dispatchers.IO) { liveCam.stopRecord() }

            currentRecordingFile = null
            ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }

            withContext(ioDispatcher) {
                enforceRetention()
            }
        }
    }

    suspend fun stop() {
        loopJob?.cancel()
        loopJob = null
        if (camera?.isRecording == true) {
            withContext(Dispatchers.IO) { camera.stopRecord() }
        }
        currentRecordingFile ?: return
        currentRecordingFile = null
        ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }
        withContext(ioDispatcher) {
            enforceRetention()
        }
    }

    fun shutdown() {
        loopJob?.cancel()
        if (camera?.isRecording == true) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { camera.stopRecord() } }
        }
        managerScope.cancel()
    }

    private fun createSegmentFile(): File {
        val fileName = "CCTV_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())}.mp4"
        return File(RecordingsStore.recordingsDir(context), fileName)
    }

    private fun enforceRetention() {
        try {
            val dir = RecordingsStore.recordingsDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            val statFs = StatFs(dir.path)
            for (file in files) {
                if (DeviceStatsUtil.usedPercent(statFs) <= recordStorageThresholdPercent) break
                file.delete()
                statFs.restat(dir.path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce recording retention", e)
        }
    }
}
