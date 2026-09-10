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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingManager(
    private val context: Context,
    private val camera: () -> RtspServerCamera2?
) {
    companion object {
        private const val TAG = "RecordingManager"
    }
    private val recordingEnabled get() = ServiceStateRepository.settings.recordToGalleryEnabled

    private val recordSegmentMinutes get() = ServiceStateRepository.settings.recordSegmentMinutes

    private val recordStorageThresholdPercent get() = ServiceStateRepository.settings.recordStorageThresholdPercent

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Non-null exactly while a recording session (one SeamlessMp4RecordController installed
    // and started) is active. Written from both the main-thread-ish call sites below and the
    // ioDispatcher setup coroutine in startIfNeeded(), so this needs @Volatile.
    @Volatile
    private var activeController: SeamlessMp4RecordController? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.d(TAG, "Record onStatusChange status:$status")
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Recording error", e)
        }
    }

    fun startIfNeeded() {
        val cam = camera() ?: return
        if (!recordingEnabled) return
        if (!cam.isStreaming) return
        if (activeController != null) return

        val controller = SeamlessMp4RecordController(
            segmentDurationMinutes = { recordSegmentMinutes },
            nextSegmentFile = { createSegmentFile() },
        ) {
            managerScope.launch(ioDispatcher) { enforceRetention() }
        }
        activeController = controller
        cam.setRecordController(controller)

        managerScope.launch(ioDispatcher) {
            val firstFile = try {
                createSegmentFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recording segment file", e)
                activeController = null
                return@launch
            }
            try {
                cam.startRecord(firstFile.absolutePath, recordListener)
            } catch (e: Exception) {
                Log.e(TAG, "startRecord failed", e)
                activeController = null
                return@launch
            }
            ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = true) }
        }
    }

    suspend fun stop() {
        activeController = null
        val cam = camera()
        if (cam?.isRecording == true) {
            withContext(Dispatchers.IO) { cam.stopRecord() }
        }
        ServiceStateRepository.updateRuntime { it.copy(isRecordingToGallery = false) }
    }

    fun shutdown() {
        activeController = null
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
