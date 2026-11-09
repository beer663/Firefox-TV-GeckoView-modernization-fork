/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.os.Bundle
import android.preference.PreferenceManager
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import org.mozilla.focus.MainActivity
import org.mozilla.focus.ScreenController
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.cursor.CursorController
import org.mozilla.focus.browser.cursor.CursorView
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.isVoiceViewEnabled
import org.mozilla.focus.ext.toUri
import org.mozilla.focus.home.BundledTilesManager
import org.mozilla.focus.home.CustomTilesManager
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.iwebview.IWebViewLifecycleFragment
import org.mozilla.focus.session.NullSession
import org.mozilla.focus.session.Session
import org.mozilla.focus.session.SessionCallbackProxy
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.utils.OnUrlEnteredListener
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.ViewUtils.showCenteredTopToast
import org.mozilla.focus.widget.InlineAutocompleteEditText
import org.mozilla.tv.firefox.R

private const val ARGUMENT_SESSION_UUID = "sessionUUID"
private const val TOAST_Y_OFFSET = 200

class BrowserFragment : IWebViewLifecycleFragment() {
    companion object {
        const val FRAGMENT_TAG = "browser"

        @JvmStatic
        fun createForSession(session: Session) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putString(ARGUMENT_SESSION_UUID, session.uuid)
            }
        }
    }

    override lateinit var session: Session
    override val initialUrl get() = session.url.value

    override val iWebViewCallback
        get() = SessionCallbackProxy(session, BrowserIWebViewCallback(this))

    var url: String? = null
        private set

    private val sessionManager = SessionManager.getInstance()

    internal val browserOverlay: BrowserNavigationOverlay
        get() = requireView().findViewById(R.id.browserOverlay)

    internal val browserContainer: View
        get() = requireView().findViewById(R.id.browserContainer)

    internal val videoContainer: FrameLayout
        get() = requireView().findViewById(R.id.videoContainer)

    var cursor: CursorController? = null
        @UiThread get set

    private var overlayVisibleCached: Int? = null
    private val navigationState = BrowserNavigationState()

    internal val isInFullscreen: Boolean
        get() = navigationState.isFullscreen

    private val fullscreenChrome = mutableMapOf<View, Int>()
    private var overlayBeforeFullscreen = View.GONE
    private var systemUiBeforeFullscreen: Int? = null
    private var keepScreenOnBeforeFullscreen = false
    private var showMenuAfterFullscreen = false

    private val pointerGesture = FullscreenPointerGesture()
    private var pendingSelectDown: KeyEvent? = null
    private var suppressSelectRelease = false

    internal val isFullscreenPointerEnabled: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(
            requireContext()
        ).getBoolean(
            "fullscreen_pointer_enabled",
            webView?.isYoutubeTV != true
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSession()
    }

    private fun initSession() {
        val sessionUUID = requireArguments()
            .getString(ARGUMENT_SESSION_UUID)
            ?: throw IllegalAccessError("No session exists")

        session = if (sessionManager.hasSessionWithUUID(sessionUUID)) {
            sessionManager.getSessionByUUID(sessionUUID)
        } else {
            NullSession()
        }

        webView?.setBlockingEnabled(session.isBlockingEnabled)

        session.url.observe(this, Observer { currentUrl ->
            this@BrowserFragment.url = currentUrl
        })

        session.loading.observe(this, object : NonNullObserver<Boolean>() {
            public override fun onValueChanged(loading: Boolean) {
                view?.findViewById<BrowserNavigationOverlay>(
                    R.id.browserOverlay
                )?.let { overlay ->
                    if (overlay.isVisible) {
                        overlay.updateOverlayForCurrentState()
                    }
                }
            }
        })
    }

    private val onNavigationEvent = {
        event: NavigationEvent,
        value: String?,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult? ->

        when (event) {
            NavigationEvent.BACK -> {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                }
            }

            NavigationEvent.FORWARD -> {
                if (webView?.canGoForward() == true) {
                    webView?.goForward()
                }
            }

            NavigationEvent.TURBO -> {
                val enabled =
                    Settings.getInstance(requireContext()).isBlockingEnabled

                session.isBlockingEnabled = enabled
                webView?.setBlockingEnabled(enabled)
                webView?.reload()
            }

            NavigationEvent.RELOAD -> webView?.reload()

            NavigationEvent.HOME -> {
                ScreenController.showHomeScreen(
                    parentFragmentManager,
                    requireActivity() as OnUrlEnteredListener
                )
            }

            NavigationEvent.SETTINGS -> {
                ScreenController.showSettingsScreen(parentFragmentManager)
            }

            NavigationEvent.LOAD -> {
                (activity as MainActivity).onTextInputUrlEntered(
                    value!!,
                    autocompleteResult!!,
                    UrlTextInputLocation.MENU
                )
                setOverlayVisibileByUser(false)
            }

            NavigationEvent.PIN_ACTION -> {
                this@BrowserFragment.url?.let { currentUrl ->
                    when (value) {
                        NavigationEvent.VAL_CHECKED -> {
                            CustomTilesManager.getInstance(requireContext())
                                .pinSite(
                                    requireContext(),
                                    currentUrl,
                                    webView?.takeScreenshot()
                                )

                            showCenteredTopToast(
                                requireContext(),
                                R.string.notification_pinned_site,
                                0,
                                TOAST_Y_OFFSET
                            )
                        }

                        NavigationEvent.VAL_UNCHECKED -> {
                            currentUrl.toUri()?.let { uri ->
                                val removed =
                                    BundledTilesManager.getInstance(
                                        requireContext()
                                    ).unpinSite(requireContext(), uri) ||
                                    CustomTilesManager.getInstance(
                                        requireContext()
                                    ).unpinSite(requireContext(), currentUrl)

                                if (removed) {
                                    showCenteredTopToast(
                                        requireContext(),
                                        R.string.notification_unpinned_site,
                                        0,
                                        TOAST_Y_OFFSET
                                    )
                                }
                            }
                        }

                        else -> throw IllegalArgumentException(
                            "Unexpected value for PIN_ACTION: $value"
                        )
                    }
                }
            }
        }

        Unit
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = inflater.inflate(
            R.layout.fragment_browser,
            container,
            false
        )

        cursor = CursorController(
            this,
            cursorParent = layout.findViewById(R.id.browserFragmentRoot),
            view = layout.findViewById<CursorView>(R.id.cursorView)
        )

        lifecycle.addObserver(cursor!!)

        with(
            layout.findViewById<BrowserNavigationOverlay>(R.id.browserOverlay)
        ) {
            onNavigationEvent = this@BrowserFragment.onNavigationEvent
            navigationStateProvider = NavigationStateProvider()
            visibility = overlayVisibleCached ?: View.GONE

            onPreSetVisibilityListener = { isVisible ->
                if (!isVisible) {
                    webView?.focusedDOMElement?.cache()
                }
            }
        }

        layout.findViewById<FirefoxProgressBar>(
            R.id.progressBar
        ).initialize(this)

        return layout
    }

    override fun onPause() {
        if (pointerGesture.isPressed) {
            suppressSelectRelease = true
        }

        pendingSelectDown = null
        pointerGesture.reset()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (view != null && !browserOverlay.isVisible) {
            restorePageFocus()
        }
    }

    override fun onDestroyView() {
        pendingSelectDown = null
        pointerGesture.reset()
        navigationState.requestFullscreenExit()
        finishFullscreen(restoreFocus = false)
        navigationState.reset()

        overlayVisibleCached = view
            ?.findViewById<BrowserNavigationOverlay>(R.id.browserOverlay)
            ?.visibility

        cursor?.onPause()
        cursor?.let(lifecycle::removeObserver)
        cursor = null

        super.onDestroyView()
    }

    fun onBackPressed(): Boolean {
        when {
            navigationState.requestFullscreenExit() -> Unit

            browserOverlay.isVisible -> {
                setOverlayVisibileByUser(false)
            }

            webView?.canGoBack() == true -> {
                webView?.goBack()
                TelemetryWrapper.browserBackControllerEvent()
            }

            else -> {
                parentFragmentManager.popBackStack()
                SessionManager.getInstance().removeCurrentSession()
            }
        }

        return true
    }

    fun loadUrl(url: String) {
        val page = webView

        if (page != null && !TextUtils.isEmpty(url)) {
            page.loadUrl(url)
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (view == null) return false

        return handleSpecialKeyEvent(event) ||
            (cursor?.keyDispatcher?.dispatchKeyEvent(event) ?: false)
    }

    private fun handleSpecialKeyEvent(event: KeyEvent): Boolean {
        if (handleFullscreenSelect(event)) return true

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val consumed = when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    navigationState.onBackDown(browserOverlay.isVisible) {
                        setOverlayVisibileByUser(false)
                    }
                }

                KeyEvent.ACTION_UP -> navigationState.onBackUp()
                else -> false
            }

            if (consumed) return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    navigationState.onMenuDown {
                        if (isInFullscreen) {
                            showMenuAfterFullscreen = true
                            navigationState.requestFullscreenExit()
                        } else {
                            setOverlayVisibileByUser(
                                !browserOverlay.isVisible
                            )
                        }
                    }
                }

                KeyEvent.ACTION_UP -> navigationState.onMenuUp()
            }

            return true
        }

        if (
            !browserOverlay.isVisible &&
            webView?.isYoutubeTV == true &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            val escape = KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                KeyEvent.KEYCODE_ESCAPE,
                event.repeatCount,
                event.metaState,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source
            )

            requireActivity().dispatchKeyEvent(escape)
            return true
        }

        return false
    }

    private fun handleFullscreenSelect(event: KeyEvent): Boolean {
        val selectKey =
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (!selectKey) return false

        if (suppressSelectRelease) {
            if (event.action == KeyEvent.ACTION_UP) {
                suppressSelectRelease = false
                return true
            }

            if (
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
                suppressSelectRelease = false
            } else {
                return true
            }
        }

        if (!isInFullscreen && !pointerGesture.isPressed) {
            return false
        }

        if (
            pointerGesture.isPressed &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            pointerGesture.reset()
            pendingSelectDown = null
        }

        if (
            pointerGesture.isPressed &&
            pendingSelectDown?.keyCode != event.keyCode
        ) {
            return true
        }

        if (
            !pointerGesture.isPressed &&
            (
                event.action != KeyEvent.ACTION_DOWN ||
                browserOverlay.isVisible ||
                requireContext().isVoiceViewEnabled()
            )
        ) {
            return false
        }

        val timeout = ViewConfiguration.getLongPressTimeout().toLong()

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!pointerGesture.isPressed) {
                    pendingSelectDown = KeyEvent(event)
                }
                pointerGesture.down(event.eventTime, timeout)
            }

            KeyEvent.ACTION_UP -> {
                pointerGesture.up(
                    event.eventTime,
                    timeout,
                    event.isCanceled ||
                        !isInFullscreen ||
                        browserOverlay.isVisible ||
                        requireContext().isVoiceViewEnabled()
                )
            }

            else -> FullscreenPointerGesture.Action.WAIT
        }

        if (
            action == FullscreenPointerGesture.Action.TOGGLE &&
            isInFullscreen
        ) {
            val enabled = !isFullscreenPointerEnabled

            PreferenceManager.getDefaultSharedPreferences(
                requireContext()
            ).edit()
                .putBoolean("fullscreen_pointer_enabled", enabled)
                .apply()

            cursor?.onPause()
            updateCursorState()
            restorePageFocus()

            Toast.makeText(
                requireContext(),
                if (enabled) {
                    "Fullscreen pointer: On"
                } else {
                    "Fullscreen pointer: Off"
                },
                Toast.LENGTH_SHORT
            ).show()
        } else if (action == FullscreenPointerGesture.Action.CLICK) {
            pendingSelectDown?.let { down ->
                if (cursor?.dispatchSelectClick(down, event) != true) {
                    val page = webView as? View
                    page?.dispatchKeyEvent(down)
                    page?.dispatchKeyEvent(event)
                }
            }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            pendingSelectDown = null
        }

        return true
    }

    private fun updateCursorState() {
        cursor?.setEnabledForCurrentState()

        if (
            (!isInFullscreen || isFullscreenPointerEnabled) &&
            !browserOverlay.isVisible &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            cursor?.onResume()
        } else {
            cursor?.onPause()
        }
    }

    private fun restorePageFocus() {
        val root = view ?: return
        val page = webView ?: return

        page.requestFocus()

        root.post {
            if (
                view === root &&
                webView === page &&
                !isInFullscreen &&
                !browserOverlay.isVisible
            ) {
                page.focusedDOMElement.restore()
            }
        }
    }

    private fun setOverlayVisibileByUser(toShow: Boolean) {
        if (toShow) {
            webView?.focusedDOMElement?.cache()
        }

        browserOverlay.visibility =
            if (toShow) View.VISIBLE else View.GONE

        updateCursorState()

        if (toShow) {
            browserOverlay.updateOverlayForCurrentState()
            browserOverlay.requestFocus()
        } else {
            restorePageFocus()
        }

        TelemetryWrapper.drawerShowHideEvent(toShow)
    }

    internal fun enterFullscreen(
        exit: IWebView.FullscreenCallback,
        videoView: View?
    ) {
        val root = view

        if (root == null) {
            exit.fullScreenExited()
            return
        }

        if (!isInFullscreen) {
            overlayBeforeFullscreen = browserOverlay.visibility

            for (
                id in intArrayOf(
                    R.id.bannerLayout,
                    R.id.hintBarContainer,
                    R.id.progressBar
                )
            ) {
                root.findViewById<View>(id)?.let {
                    fullscreenChrome[it] = it.visibility
                }
            }

            activity?.window?.let { window ->
                systemUiBeforeFullscreen =
                    window.decorView.systemUiVisibility

                keepScreenOnBeforeFullscreen =
                    window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

                window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )

                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }

        navigationState.enterFullscreen {
            exit.fullScreenExited()
        }

        webView?.focusedDOMElement?.cache()
        browserOverlay.visibility = View.GONE
        fullscreenChrome.keys.forEach { it.visibility = View.GONE }
        videoContainer.removeAllViews()

        if (videoView == null) {
            browserContainer.visibility = View.VISIBLE
            videoContainer.visibility = View.GONE
            webView?.requestFocus()
        } else {
            browserContainer.visibility = View.INVISIBLE

            videoContainer.addView(
                videoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            videoContainer.visibility = View.VISIBLE
            videoView.requestFocus()
        }

        updateCursorState()
    }

    internal fun finishFullscreen(restoreFocus: Boolean = true) {
        if (!navigationState.onFullscreenExit()) return

        val root = view

        if (root != null) {
            videoContainer.removeAllViews()
            videoContainer.visibility = View.GONE
            browserContainer.visibility = View.VISIBLE

            fullscreenChrome.forEach { (chrome, visibility) ->
                chrome.visibility = visibility
            }

            browserOverlay.visibility = if (showMenuAfterFullscreen) {
                View.VISIBLE
            } else {
                overlayBeforeFullscreen
            }
        }

        fullscreenChrome.clear()

        activity?.window?.let { window ->
            systemUiBeforeFullscreen?.let {
                window.decorView.systemUiVisibility = it
            }

            if (!keepScreenOnBeforeFullscreen) {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }

        systemUiBeforeFullscreen = null
        showMenuAfterFullscreen = false

        if (root != null && restoreFocus) {
            updateCursorState()

            if (browserOverlay.isVisible) {
                browserOverlay.updateOverlayForCurrentState()
                browserOverlay.requestFocus()
            } else {
                restorePageFocus()
            }
        }
    }

    private inner class NavigationStateProvider :
        BrowserNavigationOverlay.BrowserNavigationStateProvider {

        override fun isBackEnabled() =
            webView?.canGoBack() ?: false

        override fun isForwardEnabled() =
            webView?.canGoForward() ?: false

        override fun getCurrentUrl() = url

        override fun isURLPinned() = url.toUri()?.let {
            CustomTilesManager.getInstance(
                requireContext()
            ).isURLPinned(it.toString()) ||
                BundledTilesManager.getInstance(
                    requireContext()
                ).isURLPinned(it)
        } ?: false
    }
}

private class BrowserIWebViewCallback(
    private val browserFragment: BrowserFragment
) : IWebView.Callback {
    override fun onPageStarted(url: String) {}
    override fun onPageFinished(isSecure: Boolean) {}
    override fun onProgress(progress: Int) {}
    override fun onURLChanged(url: String) {}
    override fun onRequest(isTriggeredByUserGesture: Boolean) {}
    override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {}
    override fun onLongPress(hitTarget: IWebView.HitTarget) {}

    override fun onEnterFullScreen(
        callback: IWebView.FullscreenCallback,
        view: View?
    ) {
        browserFragment.enterFullscreen(callback, view)
    }

    override fun onExitFullScreen() {
        browserFragment.finishFullscreen()
    }
}
