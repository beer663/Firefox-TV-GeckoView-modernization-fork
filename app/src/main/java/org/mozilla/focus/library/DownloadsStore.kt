/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.library

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File

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
        val result = mutableListOf<Entry>()

        checkNotNull(manager) {
            "Android Download Manager is unavailable."
        }.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                fun number(column: String) =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(column)
                    )

                result += Entry(
                    number(DownloadManager.COLUMN_ID),
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TITLE
                        )
                    ) ?: "Download",
                    number(
                        DownloadManager.COLUMN_STATUS
                    ).toInt(),
                    number(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    ),
                    number(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                    ),
                    number(
                        DownloadManager.COLUMN_REASON
                    ).toInt()
                )
            }
        }

        return result.sortedByDescending { it.id }
    }

    fun open(entry: Entry) {
        val manager = manager ?: return
        val uri = manager.getUriForDownloadedFile(entry.id)

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
                manager.getMimeTypeForDownloadedFile(entry.id)
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
        manager?.remove(id)
    }

    companion object {
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

            require(
                uri.scheme == "https" || uri.scheme == "http"
            ) {
                "This download requires an HTTP or HTTPS address."
            }

            val title = File(
                URLUtil.guessFileName(
                    url,
                    disposition,
                    mime
                )
            ).name.ifBlank { "download" }

            val request = DownloadManager.Request(uri)
                .setTitle(title)
                .setNotificationVisibility(
                    DownloadManager.Request
                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
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
                request.addRequestHeader(
                    "User-Agent",
                    userAgent
                )
            }

            if (!referer.isNullOrBlank() &&
                (
                    referer.startsWith("https://") ||
                    referer.startsWith("http://")
                )
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
