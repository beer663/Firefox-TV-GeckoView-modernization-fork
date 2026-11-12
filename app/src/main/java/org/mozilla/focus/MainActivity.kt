/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.lifecycle.Observer
import android.widget.Toast
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.BrowserSessionSupport
import org.mozilla.focus.browser.cursor.RemoteKeyRouter
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.autocomplete.UrlAutoCompleteFilter
import org.mozilla.focus.browser.cursor.TvCursorLayout
import org.mozilla.focus.ext.toSafeIntent
import org.mozilla.focus.extensions.ExtensionPermissions
import org.mozilla.focus.home.CustomTilesManager
import org.mozilla.focus.home.HomeFragment
import org.mozilla.focus.iwebview.WebViewProvider
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity
import org.mozilla.focus.session.Session
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.session.Source
import org.mozilla.focus.telemetry.SentryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.utils.OnUrlEnteredListener
import org.mozilla.focus.utils.SafeIntent
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.widget.InlineAutocompleteEditText
import org.mozilla.tv.firefox.R

class MainActivity : LocaleAwareAppCompatActivity(), OnUrlEnteredListener {
    val browserSupport = BrowserSessionSupport(this)
    private val remoteKeys = RemoteKeyRouter<KeyEvent>()
    private val sessionManager = SessionManager.getInstance()
    private var fragmentsResumed = false
    private var pendingNavigation: Pair<String, Source>? = null
    private lateinit var cursorRoot: TvCursorLayout
    private val extensionPermissions by lazy { ExtensionPermissions(this) }
    private lateinit var addressInput: InlineAutocompleteEditText
    private var observedSession: Session? = null
    private val sessionUrlObserver = Observer<String> { url ->
        updateAddressText(url)
        updateToolbar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SentryWrapper.init(this)

        // Tracking protection (formerly Turbo) is automatic in this TV build.
        val settings = Settings.getInstance(this)
        if (!settings.isBlockingEnabled) settings.isBlockingEnabled = true

        val safeIntent = SafeIntent(intent)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(R.layout.activity_main)
        cursorRoot = findViewById(R.id.rootView)
        addressInput = findViewById(R.id.tvAddressInput)
        val urlSuggestions = UrlAutoCompleteFilter()
        urlSuggestions.load(applicationContext)
        addressInput.setOnFilterListener { text, field ->
            urlSuggestions.onFilter(text, field)
        }
        addressInput.setOnCommitListener { submitAddress() }
        findViewById<Button>(R.id.tvAddressGoButton).setOnClickListener {
            submitAddress()
        }
        // Align the integrated search bar with the right edge of Refresh.
        // The bookmark label can change, so recompute if the button row resizes.
        val toolbarButtons = findViewById<View>(R.id.tvToolbarButtonRow)
        val addressRow = findViewById<View>(R.id.tvAddressRow)
        toolbarButtons.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && addressRow.layoutParams.width != width) {
                addressRow.layoutParams = addressRow.layoutParams.apply { this.width = width }
            }
        }

        findViewById<Button>(R.id.tvHomeButton).setOnClickListener {
            ScreenController.showHomeScreen(supportFragmentManager, this)
        }
        findViewById<Button>(R.id.tvBookmarkButton).setOnClickListener { saveBookmark() }
        findViewById<Button>(R.id.tvDownloadsButton).setOnClickListener {
            ScreenController.showDownloadsScreen(supportFragmentManager)
        }
        findViewById<Button>(R.id.tvHistoryButton).setOnClickListener {
            ScreenController.showHistoryScreen(supportFragmentManager)
        }
        findViewById<Button>(R.id.tvSettingsButton).setOnClickListener {
            ScreenController.showSettingsScreen(supportFragmentManager)
        }
        // Browser history controls live in the persistent toolbar, not a second menu.
        findViewById<ImageButton>(R.id.tvBackButton).setOnClickListener {
            val web = browser()?.takeIf { it.isVisible && !it.isInFullscreen }?.webView
            if (web?.canGoBack() == true) web.goBack()
            updateToolbar()
        }
        findViewById<ImageButton>(R.id.tvForwardButton).setOnClickListener {
            val web = browser()?.takeIf { it.isVisible && !it.isInFullscreen }?.webView
            if (web?.canGoForward() == true) web.goForward()
            updateToolbar()
        }
        findViewById<ImageButton>(R.id.tvRefreshButton).setOnClickListener {
            browser()?.takeIf { it.isVisible && !it.isInFullscreen }?.webView?.reload()
        }

        val pageBounds = Rect()
        cursorRoot.scrollPage = { x, y, dx, dy ->
            val page = browser()
            val pageView = page?.webView as? View
            if (page?.isVisible == true && !page.browserOverlay.isShown &&
                pageView != null && pageView.isShown
            ) {
                pageView.getDrawingRect(pageBounds)
                cursorRoot.offsetDescendantRectToMyCoords(pageView, pageBounds)
                if (pageBounds.contains(x.toInt(), y.toInt())) {
                    page.webView?.scrollByClamped(dx, dy)
                    true
                } else false
            } else false
        }

        cursorRoot.viewTreeObserver.addOnGlobalLayoutListener { updateToolbar() }
        IntentValidator.validateOnCreate(
            this, safeIntent, savedInstanceState, ::onValidBrowserIntent
        )
        sessionManager.sessions.observe(this, object : NonNullObserver<List<Session>>() {
            override fun onValueChanged(sessions: List<Session>) {
                val nextSession = if (sessions.isEmpty()) null else sessionManager.currentSession
                if (observedSession !== nextSession) {
                    observedSession?.url?.removeObserver(sessionUrlObserver)
                    observedSession = nextSession
                    nextSession?.url?.observe(this@MainActivity, sessionUrlObserver)
                }
                if (sessions.isEmpty()) {
                    updateAddressText("")
                    ScreenController.showHomeScreen(supportFragmentManager, this@MainActivity)
                } else {
                    ScreenController.showBrowserScreenForCurrentSession(
                        supportFragmentManager, sessionManager
                    )
                }
            }
        })
        WebViewProvider.preload(this)
    }

    override fun onNewIntent(unsafeIntent: Intent) {
        super.onNewIntent(unsafeIntent)
        IntentValidator.validate(this, unsafeIntent.toSafeIntent(), ::onValidBrowserIntent)
    }

    private fun onValidBrowserIntent(url: String, source: Source) {
        if (!fragmentsResumed || supportFragmentManager.isStateSaved) {
            pendingNavigation = url to source
            return
        }
        pendingNavigation = null
        ScreenController.showBrowserScreenForUrl(supportFragmentManager, url, source)
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        fragmentsResumed = true
        if (supportFragmentManager.isStateSaved) return
        val navigation = pendingNavigation ?: return
        pendingNavigation = null
        ScreenController.showBrowserScreenForUrl(
            supportFragmentManager, navigation.first, navigation.second
        )
    }

    override fun applyLocale() {
        // Fragments update their own displayed content.
    }

    override fun onResume() {
        super.onResume()
        extensionPermissions.attach()
        TelemetryWrapper.startSession(this)
    }

    override fun onPause() {
        extensionPermissions.detach()
        if (::cursorRoot.isInitialized) cursorRoot.pausePointer()
        remoteKeys.reset()
        fragmentsResumed = false
        super.onPause()
        TelemetryWrapper.stopSession(this)
    }

    override fun onStop() {
        super.onStop()
        TelemetryWrapper.stopMainActivity()
    }

    override fun onBackPressed() {
        val page = browser()
        if (page != null && page.isVisible && page.onBackPressed()) return
        super.onBackPressed()
    }

    private fun submitAddress() {
        val raw = addressInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        // The same handler is used for searches, unprefixed domains and full URLs.
        onTextInputUrlEntered(raw, addressInput.lastAutocompleteResult, UrlTextInputLocation.MENU)
        addressInput.clearFocus()
    }

    private fun updateAddressText(url: String?) {
        if (!::addressInput.isInitialized || addressInput.hasFocus()) return
        val displayed = url.orEmpty()
        if (addressInput.text?.toString() != displayed) {
            addressInput.setText(displayed)
        }
    }

    override fun onNonTextInputUrlEntered(urlStr: String) {
        ViewUtils.hideKeyboard(findViewById(R.id.container))
        ScreenController.onUrlEnteredInner(
            this, supportFragmentManager, urlStr, false, null, null
        )
    }

    override fun onTextInputUrlEntered(
        urlStr: String,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?,
        inputLocation: UrlTextInputLocation?
    ) {
        ViewUtils.hideKeyboard(findViewById(R.id.container))
        ScreenController.onUrlEnteredInner(
            this, supportFragmentManager, urlStr, true, autocompleteResult, inputLocation
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::cursorRoot.isInitialized) return super.dispatchKeyEvent(event)
        val page = browser()?.takeIf { it.isVisible }
        val overlay = page?.browserOverlay?.isShown == true
        val fullscreen = page?.isInFullscreen == true
        val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val nativeTv = page?.webView?.isYoutubeTV == true
        val pointerEnabled = page == null || overlay ||
            (if (fullscreen) page.isFullscreenPointerEnabled else !nativeTv)
        val home = supportFragmentManager.findFragmentByTag(
            HomeFragment.FRAGMENT_TAG
        ) as? HomeFragment

        return remoteKeys.dispatch(
            event, event.action, event.keyCode, event.repeatCount,
            cursorRoot.keyboardVisible(),
            page != null && !overlay && fullscreen && select,
            pointerEnabled,
            { routed -> cursorRoot.handleRemoteKey(routed) },
            { routed ->
                when {
                    page != null -> page.dispatchKeyEvent(routed)
                    home?.isVisible == true -> home.dispatchKeyEvent(routed)
                    else -> false
                }
            },
            { routed -> super.dispatchKeyEvent(routed) },
            { cursorRoot.pausePointer() }
        )
    }

    override fun onDestroy() {
        observedSession?.url?.removeObserver(sessionUrlObserver)
        browserSupport.close()
        super.onDestroy()
    }

    private fun browser() = supportFragmentManager.findFragmentByTag(
        BrowserFragment.FRAGMENT_TAG
    ) as? BrowserFragment

    private fun bookmarkUrl(): String? {
        val page = browser()?.takeIf { it.isVisible } ?: return null
        val url = page.webView?.getUrl() ?: page.url ?: return null
        val scheme = Uri.parse(url).scheme
        return url.takeIf { scheme == "https" || scheme == "http" }
    }

    private fun saveBookmark() {
        val url = bookmarkUrl() ?: return
        val manager = CustomTilesManager.getInstance(this)
        val message = if (manager.isURLPinned(url)) {
            R.string.tv_bookmark_exists
        } else {
            manager.pinSite(this, url, null)
            R.string.tv_bookmark_saved
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        updateToolbar()
    }

    private fun updateToolbar() {
        val toolbar = findViewById<View>(R.id.tvToolbar) ?: return
        val page = browser()
        if (page?.isVisible == true) {
            updateAddressText(page.webView?.getUrl() ?: page.url)
        } else if (supportFragmentManager.findFragmentByTag(HomeFragment.FRAGMENT_TAG)?.isVisible == true) {
            updateAddressText("")
        }
        toolbar.visibility = if (page?.isVisible == true && page.isInFullscreen) {
            View.GONE
        } else View.VISIBLE
        val activeWeb = page?.takeIf { it.isVisible && !it.isInFullscreen }?.webView
        findViewById<ImageButton>(R.id.tvBackButton).isEnabled = activeWeb?.canGoBack() == true
        findViewById<ImageButton>(R.id.tvForwardButton).isEnabled = activeWeb?.canGoForward() == true
        findViewById<ImageButton>(R.id.tvRefreshButton).isEnabled = activeWeb != null

        if (page?.isVisible == true && !page.browserOverlay.isShown &&
            (if (page.isInFullscreen) !page.isFullscreenPointerEnabled
             else page.webView?.isYoutubeTV == true)
        ) cursorRoot.pausePointer()

        val button = findViewById<Button>(R.id.tvBookmarkButton)
        val url = bookmarkUrl()
        button.isEnabled = url != null
        val label = if (url != null && CustomTilesManager.getInstance(this).isURLPinned(url)) {
            getString(R.string.tv_bookmarked)
        } else getString(R.string.tv_bookmark)
        if (button.text.toString() != label) button.text = label
    }
}
