package org.mozilla.tv.browser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import java.util.concurrent.TimeUnit

/** One runtime per app process, with Gecko's normal rendering/network defaults. */
class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, "last-browser-crash.txt").writeText(
                    Log.getStackTraceString(error).take(24000))
            } catch (_: Exception) { }
            try {
                previous?.uncaughtException(thread, error)
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }

    companion object {
        private var runtime: GeckoRuntime? = null

        @JvmStatic
        fun getRuntime(context: Context): GeckoRuntime {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            return runtime ?: GeckoRuntime.create(context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .extensionsWebAPIEnabled(true)
                    .build()).also { runtime = it }
        }

        /** Only called from the manually opened diagnostics screen, on a worker. */
        fun crashReport(context: Context): String = buildString {
            append("Saved Java crash:\n")
            val saved = File(context.filesDir, "last-browser-crash.txt")
            append(if (saved.isFile) saved.readText().take(24000) else "None recorded.\n")
            if (Build.VERSION.SDK_INT >= 30) {
                append("\nRecent Android process exits:\n")
                try {
                    val manager = context.getSystemService(ActivityManager::class.java)
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                        .forEach { append("${it.processName}: reason=${it.reason} " +
                            "status=${it.status} ${it.description}\n") }
                } catch (error: Exception) { append(error.message).append('\n') }
            }
            append("\nRecent browser logs:\n")
            var logFile: File? = null
            try {
                val file = File.createTempFile("browser-log-", ".txt", context.cacheDir)
                logFile = file
                val process = ProcessBuilder("logcat", "-d", "-t", "150", "-v", "brief",
                    "TVBrowser:I", "AndroidRuntime:E", "Gecko:E", "*:S")
                    .redirectErrorStream(true).redirectOutput(file).start()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
                append(file.readText().takeLast(16000))
            } catch (error: Exception) { append(error.message) }
            finally { logFile?.delete() }
        }
    }
}
