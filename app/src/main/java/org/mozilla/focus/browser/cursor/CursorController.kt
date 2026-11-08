/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser.cursor

import android.graphics.PointF
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.ext.getAccessibilityManager
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.isVoiceViewEnabled
import kotlin.properties.Delegates

private const val MAX_SCROLL_VELOCITY = 13

class CursorController(
    private val browserFragment: BrowserFragment,
    cursorParent: View,
    private val view: CursorView
) : AccessibilityManager.TouchExplorationStateChangeListener,
    LifecycleObserver {

    private var isEnabled: Boolean by Delegates.observable(true) {
        _, _, newValue ->

        keyDispatcher.isEnabled = newValue
        view.visibility = if (newValue) View.VISIBLE else View.GONE
    }

    private val viewModel = CursorViewModel(
        onUpdate = { x, y, percentMaxScrollVel, framesPassed ->
            view.updatePosition(x, y)
            scrollWebView(percentMaxScrollVel, framesPassed)
        },
        simulateTouchEvent = {
            browserFragment.requireActivity().dispatchTouchEvent(it)
        }
    )

    val keyDispatcher = CursorKeyDispatcher(
        isEnabled,
        onDirectionKey = { direction, action ->
            when (action) {
                KeyEvent.ACTION_DOWN ->
                    viewModel.onDirectionKeyDown(direction)

                KeyEvent.ACTION_UP ->
                    viewModel.onDirectionKeyUp(direction)

                else -> Unit
            }
        },
        onSelectKey = { event ->
            viewModel.onSelectKeyEvent(event.action)
            view.updateCursorPressedState(event)
        }
    )

    private val isLoadingObserver = CursorIsLoadingObserver()

    init {
        cursorParent.addOnLayoutChangeListener {
            _, _, _, right, bottom, _, _, _, _ ->

            viewModel.maxBounds = PointF(
                right.toFloat(),
                bottom.toFloat()
            )
        }
    }

    fun setEnabledForCurrentState() {
        isEnabled =
            browserFragment.webView != null &&
            !browserFragment.isInFullscreen &&
            browserFragment.webView?.isYoutubeTV != true &&
            !browserFragment.requireContext().isVoiceViewEnabled() &&
            !browserFragment.browserOverlay.isVisible

        if (!isEnabled) {
            onPause()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onStart() {
        browserFragment.requireContext()
            .getAccessibilityManager()
            .addTouchExplorationStateChangeListener(this)

        setEnabledForCurrentState()

        browserFragment.session.loading.observe(
            browserFragment,
            isLoadingObserver
        )
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop() {
        browserFragment.requireContext()
            .getAccessibilityManager()
            .removeTouchExplorationStateChangeListener(this)

        browserFragment.session.loading.removeObserver(
            isLoadingObserver
        )
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        view.cancelUpdates()
        viewModel.cancelUpdates()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume() {
        setEnabledForCurrentState()

        if (isEnabled) {
            view.startUpdates()
        }
    }

    override fun onTouchExplorationStateChanged(isEnabled: Boolean) {
        setEnabledForCurrentState()
    }

    private fun scrollWebView(
        percentScrollVel: PointF,
        framesPassed: Float
    ) {
        fun deltaScroll(percentVelocity: Float) =
            Math.round(
                percentVelocity *
                    MAX_SCROLL_VELOCITY *
                    framesPassed
            )

        browserFragment.webView?.scrollByClamped(
            deltaScroll(percentScrollVel.x),
            deltaScroll(percentScrollVel.y)
        )
    }

    private inner class CursorIsLoadingObserver :
        NonNullObserver<Boolean>() {

        override fun onValueChanged(isLoading: Boolean) {
            setEnabledForCurrentState()
        }
    }
}
