package com.zektopic.cctvapp.service

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.web.RecordingEntry
import java.io.File
import java.io.InputStream

/**
 * Read-only access to already-saved recordings under [RECORDING_RELATIVE_PATH] -- pure
 * MediaStore queries, no camera or [GalleryRecordingManager] instance needed, so
 * `WebServer`'s `/recordings` and `/recording.mp4` routes work regardless of whether
 * `CctvServerService` is running. [GalleryRecordingManager] delegates to [selection]
 * for its own retention enforcement rather than duplicating the query.
 */
object RecordingsGallery {
    private const val TAG = "RecordingsGallery"

    /** Where gallery recording segments are saved, relative to the shared Movies collection. */
    const val RECORDING_RELATIVE_PATH = "Movies/CCTVApp/"

    /** Lists finished recordings under [RECORDING_RELATIVE_PATH], newest first. */
    fun listRecordings(context: Context): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            val (selection, selectionArgs) = selection(context)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    entries.add(
                        RecordingEntry(
                            id = cursor.getLong(idIdx),
                            displayName = cursor.getString(nameIdx) ?: "recording.mp4",
                            sizeBytes = cursor.getLong(sizeIdx),
                            dateAddedMillis = cursor.getLong(dateIdx) * 1000L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list recordings", e)
        }
        return entries
    }

    /**
     * Opens a recording for serving over HTTP. Re-validates [id] against [selection]
     * rather than trusting the caller, so this can't be used to read arbitrary gallery
     * content the app didn't itself record.
     */
    fun openRecordingStream(context: Context, id: Long): InputStream? {
        return try {
            val (baseSelection, baseArgs) = selection(context)
            val sel = "$baseSelection AND ${MediaStore.Video.Media._ID} = ?"
            val selectionArgs = baseArgs + id.toString()
            var found = false
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                sel, selectionArgs, null
            )?.use { cursor -> found = cursor.moveToFirst() }
            if (!found) return null
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open recording $id for serving", e)
            null
        }
    }

    /** Selects rows under [RECORDING_RELATIVE_PATH] -- shared by retention and listing. */
    @Suppress("DEPRECATION") // MediaStore.Video.Media.DATA / Environment.getExternalStoragePublicDirectory: pre-Q only path
    fun selection(context: Context): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0" to
                arrayOf(RECORDING_RELATIVE_PATH)
        } else {
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CCTVApp")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("${moviesDir.absolutePath}/%")
        }
    }
}
