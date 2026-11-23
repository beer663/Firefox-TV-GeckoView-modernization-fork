/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.library

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Serial writes and a worker-owned cache; UI screens use entriesAsync(). */
class HistoryStore(context: Context) {
    data class Entry(val url: String, val title: String, val visitedAt: Long)
    private val app = context.applicationContext
    private val preferences by lazy {
        app.getSharedPreferences("browsing-history", Context.MODE_PRIVATE)
    }
    private var cached: List<Entry>? = null
    private var cacheRevision = -1L
    private val main = Handler(Looper.getMainLooper())

    fun entriesAsync(reply: (List<Entry>) -> Unit) {
        WORKER.execute {
            val items = try { read().toList() }
            catch (error: RuntimeException) {
                Log.e("BrowserHistory", "Could not read history", error)
                emptyList()
            }
            main.post { reply(items) }
        }
    }

    /** Compatibility for non-UI callers. Browser screens never wait on this. */
    fun entries(): List<Entry> = WORKER.submit(Callable { read().toList() }).get()

    fun record(url: String, title: String, visitedAt: Long = System.currentTimeMillis()) {
        if (Uri.parse(url).scheme !in listOf("http", "https")) return
        enqueue {
            val rows = read().toMutableList()
            val previous = rows.firstOrNull { it.url == url }
            rows.removeAll { it.url == url }
            rows.add(0, Entry(url, title.ifBlank { previous?.title ?: url }, visitedAt))
            write(rows.take(MAX_ENTRIES))
        }
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        enqueue {
            val rows = read()
            if (rows.any { it.url == url && it.title != title })
                write(rows.map { if (it.url == url) it.copy(title = title) else it })
        }
    }

    fun remove(url: String) = enqueue { write(read().filterNot { it.url == url }) }
    fun clear() = enqueue { write(emptyList()) }

    private fun enqueue(action: () -> Unit) {
        WORKER.execute {
            try { action() }
            catch (error: RuntimeException) {
                Log.e("BrowserHistory", "Could not save history", error)
            }
        }
    }

    private fun read(): List<Entry> {
        if (cacheRevision == revision) cached?.let { return it }
        val array = try { JSONArray(preferences.getString("entries", "[]")) }
        catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { item ->
                item.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                    Entry(url, item.optString("title", url), item.optLong("time"))
                }
            }
        }.take(MAX_ENTRIES).also { cached = it; cacheRevision = revision }
    }

    private fun write(rows: List<Entry>) {
        val copy = rows.toList()
        val array = JSONArray()
        copy.forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title)
                .put("time", it.visitedAt))
        }
        preferences.edit().putString("entries", array.toString()).apply()
        cached = copy
        revision++
        cacheRevision = revision
    }

    companion object {
        private const val MAX_ENTRIES = 500
        private var revision = 0L
        private val WORKER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "browser-history").apply { isDaemon = true }
        }
    }
}
