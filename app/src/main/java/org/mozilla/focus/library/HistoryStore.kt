/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.library

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class HistoryStore(context: Context) {
    data class Entry(val url: String, val title: String, val visitedAt: Long)

    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences("browsing-history", Context.MODE_PRIVATE)
    }

    // Only opening or editing the history screen needs to wait for queued work.
    fun entries(): List<Entry> = ordered { read() }

    fun record(url: String, title: String, visitedAt: Long = System.currentTimeMillis()) {
        val scheme = Uri.parse(url).scheme
        if (scheme != "https" && scheme != "http") return
        enqueue {
            val entries = read().toMutableList()
            val previous = entries.firstOrNull { it.url == url }
            entries.removeAll { it.url == url }
            entries.add(0, Entry(url, title.ifBlank { previous?.title ?: url }, visitedAt))
            write(entries.take(MAX_ENTRIES))
        }
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        enqueue {
            val entries = read()
            if (entries.any { it.url == url && it.title != title }) {
                write(entries.map { if (it.url == url) it.copy(title = title) else it })
            }
        }
    }

    fun remove(url: String) {
        ordered { write(read().filterNot { it.url == url }) }
    }

    fun clear() {
        // Queue behind outstanding visits so an older write cannot undo Clear.
        ordered { preferences.edit().remove("entries").apply() }
    }

    private fun enqueue(action: () -> Unit) {
        WORKER.execute {
            try {
                action()
            } catch (error: RuntimeException) {
                Log.e("BrowserHistory", "Could not save browsing history", error)
            }
        }
    }

    private fun <T> ordered(action: () -> T): T {
        try {
            return WORKER.submit(Callable { action() }).get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("History operation interrupted", error)
        } catch (error: ExecutionException) {
            throw IllegalStateException("History operation failed", error.cause)
        }
    }

    private fun read(): List<Entry> {
        val array = try {
            JSONArray(preferences.getString("entries", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (url.isBlank()) null
            else Entry(url, item.optString("title", url), item.optLong("time"))
        }
    }

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.visitedAt))
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 500
        private val WORKER = Executors.newSingleThreadExecutor { action ->
            Thread(action, "browser-history").apply { isDaemon = true }
        }
    }
}
