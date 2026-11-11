package dev.uphotosync.app.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Saves the details of a crash so the next launch can offer to share them. There's no
 * crash-reporting service; nothing leaves the phone unless the user chooses to share it.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    /** Keeps the app's normal crash behavior; just writes the report first. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        file(context).writeText(
            buildString {
                appendLine("uPhotoSync ${version ?: "?"} crash report")
                appendLine("Time: ${LocalDateTime.now()}")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(trace.toString())
            }
        )
    }

    /** The report from the last crash, if there's one the user hasn't seen yet. */
    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
