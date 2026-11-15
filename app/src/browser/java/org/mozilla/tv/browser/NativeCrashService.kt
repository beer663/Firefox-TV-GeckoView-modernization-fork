package org.mozilla.tv.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.AtomicFile
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/** Runs outside Gecko's process. Saves a small local report; uploads nothing. */
class NativeCrashService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Local crash diagnostics",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        startForeground(
            4101,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Saving browser crash details")
                .setContentText("Stored locally on this TV")
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != GeckoRuntime.ACTION_CRASHED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        worker.execute {
            try {
                val report = buildString {
                    append("Recorded: ${Date()}\n")
                    append(
                        "Process: ${
                            intent.getStringExtra(
                                GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE
                            )
                        }\n"
                    )
                    append(
                        "Visibility: ${
                            intent.getStringExtra(
                                GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY
                            )
                        }\n"
                    )
                    append(
                        "Remote type: ${
                            intent.getStringExtra(
                                GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE
                            )
                        }\n"
                    )

                    val dump = intent.getStringExtra(
                        GeckoRuntime.EXTRA_MINIDUMP_PATH
                    )
                    append(
                        "Native dump present: ${
                            dump?.let { File(it).isFile } == true
                        }\n"
                    )

                    val path = intent.getStringExtra(
                        GeckoRuntime.EXTRA_EXTRAS_PATH
                    )

                    if (path != null) {
                        try {
                            // Bound reads and copy only diagnostic fields.
                            val raw = File(path).reader().use {
                                val buffer = CharArray(131072)
                                val count = it.read(buffer)
                                if (count > 0) String(buffer, 0, count) else "{}"
                            }

                            val extra = JSONObject(raw)
                            for (key in listOf(
                                "MozCrashReason",
                                "CrashReason",
                                "AbortMessage",
                                "OOMAllocationSize",
                                "StartupCrash",
                                "ProcessType",
                                "Version",
                                "BuildID",
                                "AdapterVendorID",
                                "AdapterDeviceID",
                                "GraphicsCriticalError"
                            )) {
                                if (extra.has(key)) {
                                    append(
                                        "$key: ${
                                            extra.optString(key).take(4000)
                                        }\n"
                                    )
                                }
                            }
                        } catch (error: Exception) {
                            append(
                                "Crash metadata unavailable: " +
                                    "${error.javaClass.simpleName}\n"
                            )
                        }
                    }
                }

                val target = AtomicFile(
                    File(filesDir, "last-native-crash.txt")
                )
                val stream = target.startWrite()

                try {
                    stream.write(
                        report.take(24000).toByteArray(Charsets.UTF_8)
                    )
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
            } finally {
                // Keep running if another report was queued.
                if (stopSelfResult(startId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local-native-crashes"
    }
}
