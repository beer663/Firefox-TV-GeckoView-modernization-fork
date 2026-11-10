/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.library

import android.content.Context
import android.net.Uri
import android.os.StrictMode
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    data class Entry(
        val url: String,
        val title: String,
        val visitedAt: Long
    )

    private val preferences = allowPreferenceRead {
        context.applicationContext.getSharedPreferences(
            "browsing-history",
            Context.MODE_PRIVATE
        )
    }

    fun entries(): List<Entry> = synchronized(LOCK) { read() }

    fun record(
        url: String,
        title: String,
        visitedAt: Long = System.currentTimeMillis()
    ) {
        val scheme = Uri.parse(url).scheme
        if (scheme != "https" && scheme != "http") return

        synchronized(LOCK) {
            val entries = read().toMutableList()
            val previous = entries.firstOrNull { it.url == url }
            entries.removeAll { it.url == url }

            entries.add(
                0,
                Entry(
                    url,
                    title.ifBlank { previous?.title ?: url },
                    visitedAt
                )
            )

            write(entries.take(MAX_ENTRIES))
        }
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return

        synchronized(LOCK) {
            write(
                read().map {
                    if (it.url == url) it.copy(title = title) else it
                }
            )
        }
    }

    fun remove(url: String) = synchronized(LOCK) {
        write(read().filterNot { it.url == url })
    }

    fun clear() = synchronized(LOCK) {
        preferences.edit().remove("entries").apply()
    }

    private fun read(): List<Entry> {
        val array = try {
            JSONArray(
                allowPreferenceRead {
                    preferences.getString("entries", "[]")
                }
            )
        } catch (_: Exception) {
            JSONArray()
        }

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index)
                ?: return@mapNotNull null

            val url = item.optString("url")

            if (url.isBlank()) {
                null
            } else {
                Entry(
                    url,
                    item.optString("title", url),
                    item.optLong("time")
                )
            }
        }
    }

    private fun write(entries: List<Entry>) {
        val array = JSONArray()

        entries.forEach {
            array.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("time", it.visitedAt)
            )
        }

        preferences.edit()
            .putString("entries", array.toString())
            .apply()
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_ENTRIES = 500

        private inline fun <T> allowPreferenceRead(
            read: () -> T
        ): T {
            val policy = StrictMode.allowThreadDiskReads()

            return try {
                read()
            } finally {
                StrictMode.setThreadPolicy(policy)
            }
        }
    }
}
