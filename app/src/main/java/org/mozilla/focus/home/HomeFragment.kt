/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
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

class HomeFragment : Fragment() {
    var onUrlEnteredListener =
        object : OnUrlEnteredListener {}

    var onSettingsPressed: (() -> Unit)? = null

    private var bookmarks: LinearLayout? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        onUrlEnteredListener =
            context as? OnUrlEnteredListener
                ?: object : OnUrlEnteredListener {}
    }

    override fun onDetach() {
        onUrlEnteredListener =
            object : OnUrlEnteredListener {}

        super.onDetach()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        val context = requireContext()

        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.rgb(228, 226, 221))
            isFillViewport = true
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(32))
        }

        scroll.addView(
            body,
            ViewGroup.LayoutParams(-1, -2)
        )

        body.addView(
            label(
                context,
                getString(R.string.tv_home_title),
                25
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        body.addView(
            label(
                context,
                getString(R.string.tv_saved_sites),
                20
            )
        )

        body.addView(
            label(
                context,
                getString(R.string.tv_saved_sites_hint),
                14
            )
        )

        bookmarks = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        body.addView(bookmarks)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refreshBookmarks()
    }

    private fun refreshBookmarks() {
        val holder = bookmarks ?: return
        val context = requireContext()

        holder.removeAllViews()

        val tiles = CustomTilesManager
            .getInstance(context)
            .getCustomHomeTilesList()

        if (tiles.isEmpty()) {
            holder.addView(
                label(
                    context,
                    getString(R.string.tv_no_bookmarks),
                    17
                ).apply {
                    setPadding(0, dp(24), 0, 0)
                }
            )

            return
        }

        val columns = (
            (
                resources.displayMetrics.widthPixels /
                    resources.displayMetrics.density - 64
            ) / 180
        ).toInt().coerceIn(2, 6)

        for (group in tiles.chunked(columns)) {
            val row = LinearLayout(context)

            for (tile in group) {
                val name = tile.title.takeIf {
                    it.isNotBlank() && it != "custom"
                } ?: Uri.parse(tile.url).host ?: tile.url

                val button = Button(context).apply {
                    text =
                        "${name.take(1).uppercase()}\n\n$name"

                    contentDescription = name
                    isAllCaps = false
                    textSize = 17f

                    setTextColor(
                        Color.rgb(32, 33, 36)
                    )

                    maxLines = 4
                    background = background()

                    setOnClickListener {
                        onUrlEnteredListener
                            .onNonTextInputUrlEntered(
                                tile.url
                            )
                    }

                    setOnLongClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(name)
                            .setMessage(
                                getString(
                                    R.string.tv_remove_bookmark_question
                                )
                            )
                            .setPositiveButton(
                                R.string.tv_remove
                            ) { _, _ ->
                                CustomTilesManager
                                    .getInstance(context)
                                    .unpinSite(
                                        context,
                                        tile.url
                                    )

                                refreshBookmarks()
                            }
                            .setNegativeButton(
                                android.R.string.cancel,
                                null
                            )
                            .show()

                        true
                    }
                }

                row.addView(
                    button,
                    LinearLayout.LayoutParams(
                        0,
                        dp(132),
                        1f
                    ).apply {
                        setMargins(
                            dp(6),
                            dp(10),
                            dp(6),
                            dp(4)
                        )
                    }
                )
            }

            repeat(columns - group.size) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(
                        0,
                        dp(132),
                        1f
                    ).apply {
                        setMargins(
                            dp(6),
                            dp(10),
                            dp(6),
                            dp(4)
                        )
                    }
                )
            }

            holder.addView(row)
        }
    }

    private fun label(
        context: Context,
        value: String,
        size: Int
    ) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(32, 33, 36))
    }

    private fun background(): StateListDrawable {
        fun shape(
            color: Int,
            border: Int
        ) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), border)
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(
                    android.R.attr.state_pressed
                ),
                shape(
                    0xFFD8E7FC.toInt(),
                    0xFF4285F4.toInt()
                )
            )

            addState(
                intArrayOf(
                    android.R.attr.state_focused
                ),
                shape(
                    0xFFE8EFF8.toInt(),
                    0xFF4285F4.toInt()
                )
            )

            addState(
                intArrayOf(),
                shape(
                    0xFFEFEDE8.toInt(),
                    0xFFC9C7C1.toInt()
                )
            )
        }
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    fun dispatchKeyEvent(event: KeyEvent): Boolean = false

    override fun onDestroyView() {
        bookmarks = null
        super.onDestroyView()
    }

    companion object {
        const val FRAGMENT_TAG = "home"

        @JvmStatic
        fun create() = HomeFragment()
    }
}
