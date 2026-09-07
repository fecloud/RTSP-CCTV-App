package com.zektopic.cctvapp.log

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Drop-in replacement for `android.util.Log` that also persists every line to a file
 * under `context.filesDir/logs/`, since this app runs as a background service on
 * someone else's phone where logcat isn't retrievable after the fact.
 *
 * Every call site keeps using `Log.d(TAG, ...)` etc. unchanged -- files just swap
 * `import android.util.Log` for `import com.zektopic.cctvapp.log.AppLog as Log`.
 *
 * [init] must run before any `d`/`e`/`w` call, which [com.zektopic.cctvapp.CctvApplication]
 * guarantees by calling it from `onCreate` -- Android always runs `Application.onCreate`
 * before any other app component, so there's no need to guard against a missing [init]
 * the way [com.zektopic.cctvapp.settings.SettingsRepository.ensureLoaded] has to (that one
 * is called from multiple entry points, not exactly once at the very start).
 */
object AppLog {
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private lateinit var logDir: File
    private lateinit var currentFile: File
    private var forwardToLogcat = false
    private val pid = Process.myPid()

    /** Every write runs here, one at a time -- callers (main thread, NanoHTTPD worker
     *  threads, service coroutines) never block on disk I/O, and since this is the only
     *  thread that ever touches [currentFile], no lock is needed either. */
    private val writerThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLog-writer").apply { isDaemon = true }
    }

    /** [debuggable]: also forward every line to real logcat, in addition to the file. */
    fun init(context: Context, debuggable: Boolean) {
        // App-specific external storage: no permission needed at this minSdk, and unlike
        // internal storage it's pullable straight off the device (adb pull / a file
        // manager) without needing a debuggable build + run-as. Falls back to internal
        // storage on the rare device where external storage isn't currently available
        // (e.g. removable media ejected).
        val externalDir = context.getExternalFilesDir(null)
        logDir = File(externalDir ?: context.filesDir, "logs").apply { mkdirs() }
        forwardToLogcat = debuggable
        currentFile = newFile()
    }

    // `tr` is nullable, unlike android.util.Log's own (platform-typed, so effectively
    // nullable-permissive) 3-arg overloads -- some call sites pass a caught `Exception?`
    // straight through (e.g. RecordController.Listener.onError(e: Exception?)).
    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun d(tag: String, msg: String, tr: Throwable?) = log('D', tag, msg, tr)
    fun e(tag: String, msg: String) = log('E', tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable?) = log('E', tag, msg, tr)
    fun w(tag: String, msg: String) = log('W', tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable?) = log('W', tag, msg, tr)

    private fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        if (forwardToLogcat) logToLogcat(level, tag, msg, tr)
        // pid/tid are captured here, on the calling thread, not on writerThread.
        val line = LogLineFormatter.formatLine(
            System.currentTimeMillis(), pid, Process.myTid(), level, tag, msg, tr
        )
        writerThread.execute { writeToFile(line) }
    }

    private fun logToLogcat(level: Char, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            'D' -> if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
            'E' -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            'W' -> if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        }
    }

    /** Runs only on [writerThread]. Rolls over to a new timestamped file once the
     *  current one exceeds [MAX_FILE_BYTES]; old files are left in place, never deleted. */
    private fun writeToFile(line: String) {
        if (currentFile.length() > MAX_FILE_BYTES) {
            currentFile = newFile()
        }
        try {
            currentFile.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e("AppLog", "Failed to write log line", e)
        }
    }

    private fun newFile(): File = File(logDir, LogLineFormatter.fileName(System.currentTimeMillis()))
}
