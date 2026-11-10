/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.library

import android.app.DownloadManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.mozilla.focus.utils.OnUrlEnteredListener
import org.mozilla.tv.firefox.R
import java.text.DateFormat
import java.util.Date

class LibraryFragment : Fragment() {
    private val history
        get() = requireArguments().getBoolean("history")

    private var rows: LinearLayout? = null
    private val progressLabels = mutableMapOf<Long, TextView>()
    private var downloadSignature: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            if (isResumed && rows != null && !history) {
                showDownloads()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        val context = requireContext()

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(24, 25, 32))
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(32))
        }

        scroll.addView(body)

        label(
            body,
            getString(
                if (history) R.string.tv_history
                else R.string.tv_downloads
            ),
            28
        )

        val actions = LinearLayout(context)
        body.addView(actions)

        action(actions, "Back") {
            parentFragmentManager.popBackStack()
        }

        if (history) {
            action(actions, "Clear history") {
                AlertDialog.Builder(context)
                    .setTitle("Clear browsing history?")
                    .setMessage(
                        "Saved bookmarks and downloaded files will be kept."
                    )
                    .setNegativeButton(
                        android.R.string.cancel,
                        null
                    )
                    .setPositiveButton("Clear") { _, _ ->
                        HistoryStore(context).clear()
                        showHistory()
                    }
                    .show()
            }
        } else {
            action(actions, "Refresh") {
                showDownloads()
            }
        }

        rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        body.addView(rows)
        return scroll
    }

    override fun onResume() {
        super.onResume()

        if (history) {
            showHistory()
        } else {
            handler.post(refresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refresh)
        rows = null
        downloadSignature = null
        progressLabels.clear()
        super.onDestroyView()
    }

    private fun showHistory() {
        val holder = rows ?: return
        holder.removeAllViews()

        val store = HistoryStore(requireContext())
        val entries = store.entries()

        if (entries.isEmpty()) {
            label(
                holder,
                "Pages you visit will appear here.",
                18
            )
        }

        val format = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        )

        entries.forEach { entry ->
            action(
                holder,
                "${entry.title}\n${entry.url}\n" +
                    format.format(Date(entry.visitedAt))
            ) {
                (activity as? OnUrlEnteredListener)
                    ?.onNonTextInputUrlEntered(entry.url)
            }.setOnLongClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Remove this history entry?")
                    .setMessage(entry.title)
                    .setNegativeButton(
                        android.R.string.cancel,
                        null
                    )
                    .setPositiveButton("Remove") { _, _ ->
                        store.remove(entry.url)
                        showHistory()
                    }
                    .show()

                true
            }
        }
    }

    private fun showDownloads() {
        val holder = rows ?: return
        val store = DownloadsStore(requireContext())

        val entries = try {
            store.entries()
        } catch (_: RuntimeException) {
            holder.removeAllViews()

            label(
                holder,
                "Downloads are unavailable. Check that Android Download Manager is enabled.",
                18
            )

            downloadSignature = null
            return
        }

        val signature = entries.joinToString {
            "${it.id}:${it.status}"
        }

        if (signature != downloadSignature) {
            val focusTag = holder.findFocus()?.tag

            holder.removeAllViews()
            progressLabels.clear()
            downloadSignature = signature

            if (entries.isEmpty()) {
                label(
                    holder,
                    "Files you download from websites will appear here.",
                    18
                )
            }

            entries.forEach { entry ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(14), 0, dp(14))
                }

                holder.addView(row)
                label(row, entry.title, 20)

                progressLabels[entry.id] = label(row, "", 16)

                val buttons = LinearLayout(requireContext())
                row.addView(buttons)

                if (
                    entry.status ==
                    DownloadManager.STATUS_SUCCESSFUL
                ) {
                    action(buttons, "Open") {
                        store.open(entry)
                    }.tag = "open-${entry.id}"
                }

                val unfinished =
                    entry.status == DownloadManager.STATUS_RUNNING ||
                    entry.status == DownloadManager.STATUS_PENDING ||
                    entry.status == DownloadManager.STATUS_PAUSED

                action(
                    buttons,
                    if (unfinished) "Cancel" else "Delete"
                ) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(
                            if (unfinished) {
                                "Cancel this download?"
                            } else {
                                "Delete this download?"
                            }
                        )
                        .setMessage(entry.title)
                        .setNegativeButton(
                            android.R.string.cancel,
                            null
                        )
                        .setPositiveButton(
                            if (unfinished) {
                                "Cancel download"
                            } else {
                                "Delete"
                            }
                        ) { _, _ ->
                            store.remove(entry.id)
                            showDownloads()
                        }
                        .show()
                }.tag = "remove-${entry.id}"
            }

            if (focusTag != null) {
                holder.findViewWithTag<View>(focusTag)
                    ?.requestFocus()
            }
        }

        entries.forEach { entry ->
            val amount = Formatter.formatFileSize(
                requireContext(),
                entry.downloaded.coerceAtLeast(0)
            )

            val total = if (entry.total > 0) {
                " / ${
                    Formatter.formatFileSize(
                        requireContext(),
                        entry.total
                    )
                }"
            } else {
                ""
            }

            val state = when (entry.status) {
                DownloadManager.STATUS_PENDING -> "Waiting"
                DownloadManager.STATUS_RUNNING -> "Downloading"
                DownloadManager.STATUS_PAUSED ->
                    "Paused — waiting for a connection"
                DownloadManager.STATUS_SUCCESSFUL -> "Complete"
                else ->
                    "Failed — try downloading the file again"
            }

            progressLabels[entry.id]?.text =
                "$state · $amount$total"
        }
    }

    private fun label(
        parent: LinearLayout,
        value: String,
        size: Int
    ) = TextView(requireContext()).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        parent.addView(this)
    }

    private fun action(
        parent: LinearLayout,
        value: String,
        clicked: () -> Unit
    ) = Button(requireContext()).apply {
        text = value
        isAllCaps = false

        gravity =
            android.view.Gravity.START or
            android.view.Gravity.CENTER_VERTICAL

        setOnClickListener { clicked() }
        parent.addView(this)
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        fun create(history: Boolean) =
            LibraryFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("history", history)
                }
            }
    }
}
