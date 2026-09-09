package com.zektopic.cctvapp.service

import android.content.Context
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.web.RecordingEntry
import java.io.File
import java.io.InputStream

/**
 * Read-only access to already-saved recordings under [recordingsDir] -- plain file I/O, no
 * camera or [RecordingManager] instance needed, so `WebServer`'s `/recordings` and
 * `/recording.mp4` routes work regardless of whether `CctvServerService` is running.
 * [RecordingManager] writes into the same directory directly rather than going through here.
 */
object RecordingsStore {
    private const val TAG = "RecordingsStore"

    /** App-private, no permission needed on any API level -- same pattern as [com.zektopic.cctvapp.log.AppLog]. */
    fun recordingsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

    /**
     * Lists finished recordings, newest first. In-progress segments are still suffixed
     * `.mp4.part` at this point (see [RecordingManager]) so they never match `.mp4` and
     * never show up here mid-write.
     */
    fun listRecordings(context: Context): List<RecordingEntry> {
        return try {
            recordingsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    RecordingEntry(
                        id = f.name,
                        displayName = f.name,
                        sizeBytes = f.length(),
                        dateAddedMillis = f.lastModified()
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list recordings", e)
            emptyList()
        }
    }

    /**
     * Opens a recording for serving over HTTP. Re-validates [id] against an actual
     * directory listing rather than trusting the caller -- the file that's opened is always
     * one [listRecordings] itself just returned, never a path built directly from [id], so
     * this can't be used to read arbitrary files via a crafted id.
     */
    fun openRecordingStream(context: Context, id: String): InputStream? {
        val entry = listRecordings(context).find { it.id == id } ?: return null
        return try {
            File(recordingsDir(context), entry.id).inputStream()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open recording $id for serving", e)
            null
        }
    }
}
