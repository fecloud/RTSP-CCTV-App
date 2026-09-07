package com.zektopic.cctvapp.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting/naming logic for [AppLog], kept Android-API-free (no `android.util.Log`,
 * no `Context`) so it has JVM unit test coverage under `app/src/test`, per this repo's
 * testability boundary convention (see `WebAuth`, `parseResolution`).
 */
object LogLineFormatter {
    private const val LINE_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val FILE_NAME_PATTERN = "yyyyMMdd_HHmmss"

    /**
     * One line for the log file: `timestamp pid-tid LEVEL/tag: message`, plus a stack
     * trace if given. [pid]/[tid] are plain `Int`s (not read via `android.os.Process`
     * here) so this stays Android-API-free -- [AppLog] supplies them.
     */
    fun formatLine(
        epochMillis: Long,
        pid: Int,
        tid: Int,
        level: Char,
        tag: String,
        message: String,
        throwable: Throwable?
    ): String {
        val timestamp = SimpleDateFormat(LINE_TIMESTAMP_PATTERN, Locale.US).format(Date(epochMillis))
        val line = "$timestamp $pid-$tid $level/$tag: $message"
        return if (throwable != null) line + "\n" + throwable.stackTraceToString() else line
    }

    /** Name for a new rollover file, e.g. `20260907_143012.log`. */
    fun fileName(epochMillis: Long): String =
        SimpleDateFormat(FILE_NAME_PATTERN, Locale.US).format(Date(epochMillis)) + ".log"
}
