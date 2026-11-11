/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.library

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadsStore(private val context: Context) {
    data class Entry(
        val id: Long,
        val title: String,
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val reason: Int
    )

    private val manager =
        context.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE)
            as? DownloadManager

    fun entries(): List<Entry> {
        val result = synchronized(LOCK) {
            records(context).map { row ->
                val id = row.getLong("id")
                val active = ACTIVE[id]
                val storedStatus = row.getInt("status")

                Entry(
                    id,
                    row.getString("title"),
                    if (storedStatus == DownloadManager.STATUS_RUNNING && active == null) {
                        DownloadManager.STATUS_FAILED
                    } else {
                        storedStatus
                    },
                    active?.downloaded ?: row.optLong("downloaded"),
                    row.optLong("total", -1),
                    row.optInt("reason")
                )
            }.toMutableList()
        }

        try {
            manager?.query(DownloadManager.Query())?.use { cursor ->
                while (cursor.moveToNext()) {
                    fun number(column: String) =
                        cursor.getLong(cursor.getColumnIndexOrThrow(column))

                    result += Entry(
                        number(DownloadManager.COLUMN_ID),
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                        ) ?: "Download",
                        number(DownloadManager.COLUMN_STATUS).toInt(),
                        number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        number(DownloadManager.COLUMN_REASON).toInt()
                    )
                }
            }
        } catch (_: RuntimeException) {
            // Gecko downloads still work if the system manager is disabled.
        }

        return result.sortedWith(
            compareByDescending<Entry> { it.id < 0 }
                .thenByDescending { if (it.id < 0) -it.id else it.id }
        )
    }

    fun open(entry: Entry) {
        val row = if (entry.id < 0) {
            synchronized(LOCK) {
                records(context).firstOrNull { it.getLong("id") == entry.id }
            }
        } else null

        val file = row?.let { File(directory(context), it.getString("file")) }
        val uri = if (entry.id < 0) {
            if (row?.getInt("status") != DownloadManager.STATUS_SUCCESSFUL ||
                file?.isFile != true
            ) {
                null
            } else {
                FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file
                )
            }
        } else {
            manager?.getUriForDownloadedFile(entry.id)
        }

        if (uri == null) {
            Toast.makeText(
                context,
                "This downloaded file is no longer available.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                uri,
                row?.optString("mime")
                    ?: manager?.getMimeTypeForDownloadedFile(entry.id)
                    ?: "application/octet-stream"
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "No installed app can open this file type.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            Toast.makeText(
                context,
                "Android blocked opening this file. Check the app permissions in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun remove(id: Long) {
        if (id >= 0) {
            manager?.remove(id)
            return
        }

        val transfer = ACTIVE[id]
        transfer?.cancelled?.set(true)

        val removed = synchronized(LOCK) {
            val all = records(context)
            val row = all.firstOrNull { it.getLong("id") == id }
            writeRecords(context, all.filterNot { it.getLong("id") == id })
            row
        }

        if (transfer != null) {
            CLOSER.execute {
                try {
                    transfer.body.close()
                } catch (_: Exception) {}
            }
        } else {
            removed?.let {
                File(directory(context), it.getString("file")).delete()
            }
        }
    }

    companion object {
        private val LOCK = Any()
        private val IDS = AtomicLong(System.currentTimeMillis())
        private val IO = Executors.newFixedThreadPool(2)
        private val CLOSER = Executors.newCachedThreadPool()
        private val ACTIVE = ConcurrentHashMap<Long, Transfer>()

        private class Transfer(val body: InputStream, val file: File) {
            val cancelled = AtomicBoolean()
            @Volatile var downloaded = 0L
        }

        private fun directory(context: Context): File =
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")

        private fun records(context: Context): List<JSONObject> {
            val text = context.applicationContext
                .getSharedPreferences("gecko-downloads", Context.MODE_PRIVATE)
                .getString("entries", "[]")

            val array = try {
                JSONArray(text)
            } catch (_: Exception) {
                JSONArray()
            }

            return (0 until array.length()).mapNotNull {
                array.optJSONObject(it)
            }
        }

        private fun writeRecords(context: Context, rows: List<JSONObject>) {
            val array = JSONArray()
            rows.forEach { array.put(it) }

            context.applicationContext
                .getSharedPreferences("gecko-downloads", Context.MODE_PRIVATE)
                .edit()
                .putString("entries", array.toString())
                .apply()
        }

        private fun header(response: WebResponse, key: String) =
            response.headers.entries
                .firstOrNull { it.key.equals(key, true) }
                ?.value

        @JvmStatic
        fun filename(response: WebResponse): String =
            File(
                URLUtil.guessFileName(
                    response.uri,
                    header(response, "content-disposition"),
                    header(response, "content-type")
                )
            ).name
                .replace(Regex("[\\p{Cntrl}/\\\\]"), "_")
                .take(128)
                .takeUnless {
                    it.isBlank() || it == "." || it == ".."
                } ?: "download"

        @JvmStatic
        fun closeBody(response: WebResponse) {
            CLOSER.execute {
                try {
                    response.body?.close()
                } catch (_: Exception) {}
            }
        }

        @JvmStatic
        fun saveResponse(context: Context, response: WebResponse): Long {
            val app = context.applicationContext
            val body = requireNotNull(response.body) { "No download body" }
            require(response.statusCode in 200..299) { "Download response failed" }

            val folder = directory(app)
            check(folder.isDirectory || folder.mkdirs()) {
                "Download folder is unavailable"
            }

            response.setReadTimeoutMillis(60000)

            val id = -IDS.updateAndGet { previous ->
                maxOf(previous + 1, System.currentTimeMillis())
            }
            val title = filename(response)
            val target = File(folder, "gecko-${-id}-$title")

            val row = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("file", target.name)
                .put(
                    "mime",
                    header(response, "content-type")?.substringBefore(';')
                        ?: "application/octet-stream"
                )
                .put(
                    "total",
                    header(response, "content-length")?.toLongOrNull() ?: -1
                )
                .put("downloaded", 0)
                .put("status", DownloadManager.STATUS_RUNNING)
                .put("reason", 0)

            val transfer = Transfer(body, target)
            ACTIVE[id] = transfer

            fun persist() = synchronized(LOCK) {
                if (!transfer.cancelled.get()) {
                    writeRecords(
                        app,
                        records(app).filterNot { it.getLong("id") == id } + row
                    )
                }
            }

            try {
                persist()

                IO.execute {
                    try {
                        var savedAt = 0L

                        val length = DownloadStream.save(
                            body,
                            target,
                            { transfer.cancelled.get() }
                        ) { count ->
                            transfer.downloaded = count
                            val now = System.currentTimeMillis()

                            if (now - savedAt >= 1000) {
                                row.put("downloaded", count)
                                persist()
                                savedAt = now
                            }
                        }

                        row.put("downloaded", length)
                            .put("status", DownloadManager.STATUS_SUCCESSFUL)
                        persist()

                        if (!transfer.cancelled.get()) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    app,
                                    "Download complete: $title",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } catch (_: Exception) {
                        target.delete()
                        row.put("status", DownloadManager.STATUS_FAILED)
                            .put("reason", DownloadManager.ERROR_UNKNOWN)
                        persist()
                    } finally {
                        ACTIVE.remove(id)
                        if (transfer.cancelled.get()) target.delete()
                    }
                }
            } catch (error: RuntimeException) {
                ACTIVE.remove(id)

                synchronized(LOCK) {
                    writeRecords(
                        app,
                        records(app).filterNot { it.getLong("id") == id }
                    )
                }

                closeBody(response)
                throw error
            }

            return id
        }

        // Retained for compatibility with older callers.
        @JvmStatic
        fun enqueue(
            context: Context,
            url: String,
            disposition: String?,
            mime: String?,
            userAgent: String?,
            referer: String?
        ): Long {
            val uri = Uri.parse(url)

            require(uri.scheme == "https" || uri.scheme == "http") {
                "This download requires an HTTP or HTTPS address."
            }

            val title = File(
                URLUtil.guessFileName(url, disposition, mime)
            ).name.ifBlank { "download" }

            val request = DownloadManager.Request(uri)
                .setTitle(title)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "${System.currentTimeMillis()}-$title"
                )

            if (!mime.isNullOrBlank()) {
                request.setMimeType(mime.substringBefore(';'))
            }

            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            if (!referer.isNullOrBlank() &&
                (referer.startsWith("https://") || referer.startsWith("http://"))
            ) {
                request.addRequestHeader("Referer", referer)
            }

            return (
                context.applicationContext
                    .getSystemService(Context.DOWNLOAD_SERVICE)
                    as DownloadManager
                ).enqueue(request)
        }
    }
}
