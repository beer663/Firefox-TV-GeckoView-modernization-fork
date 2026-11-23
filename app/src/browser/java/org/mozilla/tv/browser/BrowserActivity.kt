package org.mozilla.tv.browser

import android.app.DownloadManager
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.focus.browser.BrowserSessionSupport
import org.mozilla.focus.browser.FullscreenPointerGesture
import org.mozilla.focus.browser.cursor.RemoteKeyRouter
import org.mozilla.focus.browser.cursor.TvCursorLayout
import org.mozilla.focus.diagnostics.CodecReport
import org.mozilla.focus.extensions.ExtensionPermissions
import org.mozilla.focus.extensions.ExtensionsActivity
import org.mozilla.focus.library.DownloadsStore
import org.mozilla.focus.library.HistoryStore
import org.mozilla.focus.utils.SmartAddress
import org.mozilla.geckoview.*
import org.mozilla.tv.firefox.BuildConfig
import org.mozilla.tv.firefox.R
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/** TV interface connected straight to GeckoView; no Firefox engine adapter. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var root: TvCursorLayout
    private lateinit var container: FrameLayout
    private lateinit var toolbar: View
    private lateinit var address: AutoCompleteTextView
    private lateinit var gecko: GeckoView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var errorCover: LinearLayout
    private lateinit var history: HistoryStore
    private lateinit var downloads: DownloadsStore

    private val sessions = mutableListOf<GeckoSession>()
    private val session get() = sessions.last()

    private val urls = mutableMapOf<GeckoSession, String>()
    private val committedUrls = mutableMapOf<GeckoSession, String>()
    private val titles = mutableMapOf<GeckoSession, String>()
    private val back = mutableMapOf<GeckoSession, Boolean>()
    private val forward = mutableMapOf<GeckoSession, Boolean>()
    private val media = mutableMapOf<GeckoSession, MediaSession>()
    private val stopped = mutableSetOf<GeckoSession>()

    private data class PageFailure(val url: String, val message: String)

    private val failures = mutableMapOf<GeckoSession, PageFailure>()
    private val prefs by lazy {
        getSharedPreferences("tv-browser", MODE_PRIVATE)
    }
    private val runtime get() = BrowserApp.getRuntime(this)

    private var panel: View? = null
    private var panelName = "home"
    private var fullscreen = false
    private var pointerInVideo = false
    private var playing = false
    private var resumed = false
    private var loading = false
    private var closed = false
    private var downloadUpdate: Runnable? = null

    private val main = Handler(Looper.getMainLooper())
    private val keys = RemoteKeyRouter<KeyEvent>()
    private val hold = FullscreenPointerGesture()
    private val extensionPermissions = ExtensionPermissions(this)

    // Register the file-picker result before Activity reaches STARTED.
    private val prompts = BrowserSessionSupport(this) { currentUrl() }
    private val permissionDialogs = mutableMapOf<AlertDialog, () -> Unit>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        root = findViewById(R.id.rootView)
        toolbar = findViewById(R.id.tvToolbar)
        container = findViewById(R.id.container)
        address = findViewById(R.id.tvAddressInput)

        history = HistoryStore(this)
        downloads = DownloadsStore(this)

        gecko = GeckoView(this)
        container.addView(gecko, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        )
        progress.max = 100

        container.addView(
            progress,
            FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP)
        )
        progress.visibility = View.GONE

        errorView = label("", 16).apply {
            textSize = 22f
        }

        errorCover = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(20), dp(32), dp(20))
            setBackgroundColor(Color.rgb(42, 44, 50))
            addView(errorView)

            val controls = LinearLayout(this@BrowserActivity)
            action(controls, "Retry page") { retry() }
            action(controls, "Home") { showHome() }
            addView(controls)
        }

        container.addView(errorCover, FrameLayout.LayoutParams(-1, -1))
        errorCover.visibility = View.GONE

        sessions.add(makeSession().also { it.open(runtime) })
        gecko.setSession(session)

        root.scrollPage = { x, y, dx, dy ->
            val origin = IntArray(2)
            val rootOrigin = IntArray(2)

            gecko.getLocationInWindow(origin)
            root.getLocationInWindow(rootOrigin)

            val bounds = Rect(
                origin[0] - rootOrigin[0],
                origin[1] - rootOrigin[1],
                origin[0] - rootOrigin[0] + gecko.width,
                origin[1] - rootOrigin[1] + gecko.height
            )

            if (
                panel == null &&
                errorCover.visibility != View.VISIBLE &&
                !stopped.contains(session) &&
                bounds.contains(x.toInt(), y.toInt())
            ) {
                session.panZoomController.scrollBy(
                    ScreenLength.fromPixels(dx.toDouble()),
                    ScreenLength.fromPixels(dy.toDouble()),
                    PanZoomController.SCROLL_BEHAVIOR_AUTO
                )
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.tvHomeButton).setOnClickListener {
            showHome()
        }

        findViewById<View>(R.id.tvBookmarkButton).setOnClickListener {
            toggleBookmark()
        }

        findViewById<View>(R.id.tvDownloadsButton).setOnClickListener {
            showDownloads()
        }

        findViewById<View>(R.id.tvHistoryButton).setOnClickListener {
            showHistory()
        }

        findViewById<View>(R.id.tvSettingsButton).setOnClickListener {
            showSettings()
        }

        findViewById<View>(R.id.tvBackButton).setOnClickListener {
            navigateBack()
        }

        findViewById<View>(R.id.tvForwardButton).setOnClickListener {
            showPage()
            if (forward[session] == true) session.goForward()
        }

        findViewById<View>(R.id.tvRefreshButton).setOnClickListener {
            retry()
        }

        findViewById<View>(R.id.tvAddressGoButton).setOnClickListener {
            submitAddress()
        }

        address.setOnEditorActionListener { _, action, event ->
            if (
                action == EditorInfo.IME_ACTION_GO ||
                event?.let {
                    it.action == KeyEvent.ACTION_DOWN &&
                        it.keyCode == KeyEvent.KEYCODE_ENTER
                } == true
            ) {
                submitAddress()
                true
            } else {
                false
            }
        }

        address.setOnClickListener {
            openKeyboard()
        }

        address.setSelectAllOnFocus(true)

        address.setOnFocusChangeListener { _, focused ->
            if (focused) {
                address.post {
                    if (address.hasFocus()) address.selectAll()
                }
            }
        }

        address.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                address.post {
                    if (address.hasFocus()) address.selectAll()
                }
            }
            false
        }

        address.setOnItemClickListener { _, _, _, _ ->
            submitAddress()
        }

        findViewById<View>(R.id.tvToolbarButtonRow).addOnLayoutChangeListener {
                row, left, _, right, _, _, _, _, _ ->
            val available = (row.parent as View).width - dp(24)
            val width = (right - left).coerceAtMost(available)
            val addressRow = findViewById<View>(R.id.tvAddressRow)

            if (width > 0 && addressRow.layoutParams.width != width) {
                addressRow.layoutParams = addressRow.layoutParams.apply {
                    this.width = width
                }
            }
        }

        val saved = state?.getString("page")

        if (!saved.isNullOrBlank()) {
            load(saved)
        } else if (!handleIntent(intent)) {
            showHome()
        }

        updateButtons()
    }

    private fun makeSession(): GeckoSession {
        val desktop = prefs.getBoolean("desktop", false)

        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(
                    if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                )
                .viewportMode(
                    if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                )
                .suspendMediaWhenInactive(true)
                .build()
        )

        s.promptDelegate = prompts

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                s: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                if (
                    request.target ==
                    GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                ) {
                    Log.i(TAG, "Blocked website popup")
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                val scheme = Uri.parse(request.uri).scheme?.lowercase()

                if (ExtensionsActivity.isXpiUrl(request.uri)) {
                    if (request.hasUserGesture || request.isDirectNavigation) {
                        ExtensionsActivity.openInstaller(
                            this@BrowserActivity,
                            request.uri
                        )
                    }

                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (
                    scheme in listOf(
                        "http", "https", "about", "data", "blob", "moz-extension"
                    )
                ) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                if (request.hasUserGesture) externalLink(request.uri)

                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (closed || !sessions.contains(s)) return
                if (failures.containsKey(s) && isErrorDocument(url.orEmpty())) return

                if (committedUrls[s] != url) titles.remove(s)

                committedUrls[s] = url.orEmpty()
                urls[s] = url.orEmpty()

                if (s == session) {
                    updateAddress()
                    updateButtons()
                }
            }

            override fun onCanGoBack(s: GeckoSession, value: Boolean) {
                back[s] = value
                if (s == session) updateButtons()
            }

            override fun onCanGoForward(s: GeckoSession, value: Boolean) {
                forward[s] = value
                if (s == session) updateButtons()
            }

            override fun onLoadError(
                s: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                if (closed || !sessions.contains(s)) return null

                Log.e(
                    TAG,
                    "Load failed category=${error.category} code=${error.code}"
                )

                val message = when (error.code) {
                    WebRequestError.ERROR_SECURITY_BAD_CERT,
                    WebRequestError.ERROR_BAD_HSTS_CERT ->
                        "This website's certificate could not be verified. " +
                            "Check the TV's date and time. " +
                            "If those are correct, the website may need to fix its certificate."

                    WebRequestError.ERROR_SECURITY_SSL ->
                        "A secure connection could not be established with this website."

                    WebRequestError.ERROR_UNKNOWN_HOST ->
                        "The website address could not be found. " +
                            "Check the address and your connection."

                    WebRequestError.ERROR_NET_TIMEOUT ->
                        "The website took too long to respond."

                    WebRequestError.ERROR_OFFLINE ->
                        "The TV has no internet connection."

                    else ->
                        "The page could not load (error ${error.code})."
                }

                failures[s] = PageFailure(
                    uri.orEmpty().ifBlank { urls[s].orEmpty() },
                    message
                )

                if (s == session) showError(message)

                // Never leave an unexplained blank Gecko page.
                val html =
                    "<!doctype html><meta name=viewport content='width=device-width'>" +
                        "<h1>Page could not load</h1><p>${Html.escapeHtml(message)}</p>"

                return GeckoResult.fromValue(
                    "data:text/html;charset=utf-8," + Uri.encode(html)
                )
            }

            override fun onNewSession(
                s: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                Log.i(TAG, "Blocked website new-session request")
                return null
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                if (closed || !sessions.contains(s)) return

                if (failures.containsKey(s) && isErrorDocument(url)) {
                    if (s == session) {
                        showError(failures.getValue(s).message)
                    }
                    return
                }

                failures.remove(s)
                urls[s] = url

                if (s == session) {
                    setLoading(true)
                    updateAddress()
                }

                Log.i(TAG, "Page start host=${Uri.parse(url).host}")
            }

            override fun onProgressChange(s: GeckoSession, value: Int) {
                if (s == session) progress.progress = value
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (closed || !sessions.contains(s)) return

                if (success && !failures.containsKey(s)) {
                    history.record(
                        urls[s].orEmpty(),
                        titles[s].orEmpty()
                    )
                }

                if (s == session) {
                    setLoading(false)
                    failures[s]?.let { showError(it.message) }
                    updateAddress()
                }

                Log.i(TAG, "Page stop success=$success")
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (closed || !sessions.contains(s)) return
                if (failures.containsKey(s)) return

                titles[s] = title.orEmpty()
                history.updateTitle(urls[s].orEmpty(), title.orEmpty())
            }

            override fun onFullScreen(s: GeckoSession, value: Boolean) {
                if (s == session) setFullscreen(value)
            }

            override fun onCrash(s: GeckoSession) {
                failed(s, "The website process crashed.")
            }

            override fun onKill(s: GeckoSession) {
                failed(
                    s,
                    "Android stopped this website process. " +
                        "The TV may need more available memory."
                )
            }

            override fun onCloseRequest(s: GeckoSession) {
                if (closed || !sessions.contains(s)) return

                if (s == session && sessions.size > 1) {
                    closeChild()
                } else if (
                    s != sessions.lastOrNull() &&
                    sessions.remove(s)
                ) {
                    disposeSession(s)
                    updateButtons()
                }
            }

            override fun onExternalResponse(
                s: GeckoSession,
                response: WebResponse
            ) {
                committedUrls[s]?.let { urls[s] = it }

                if (s == session) {
                    setLoading(false)
                    updateAddress()
                }

                val mime = response.headers.entries
                    .firstOrNull {
                        it.key.equals("content-type", true)
                    }?.value

                if (
                    ExtensionsActivity.isExtensionResponse(response.uri, mime)
                ) {
                    DownloadsStore.closeBody(response)

                    ExtensionsActivity.openInstaller(
                        this@BrowserActivity,
                        response.uri
                    )
                } else {
                    prompts.confirmDownload(response)
                }
            }
        }

        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                s: GeckoSession,
                p: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val allowed =
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val denied =
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY

                if (
                    p.value !=
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                ) {
                    return GeckoResult.fromValue(p.value)
                }

                if (
                    p.permission ==
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) {
                    return GeckoResult.fromValue(allowed)
                }

                val action = when (p.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                        "play video or audio automatically"

                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                        "play protected video using DRM"

                    GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                        "use cookies from an embedded site"

                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                        "keep website data on this TV"

                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS ->
                        "connect to devices on your local network"

                    else ->
                        return GeckoResult.fromValue(denied)
                }

                val result = GeckoResult<Int>()

                if (closed || isFinishing || isDestroyed) {
                    return GeckoResult.fromValue(denied)
                }

                var answered = false
                lateinit var dialog: AlertDialog

                fun answer(value: Int) {
                    if (!answered) {
                        answered = true
                        permissionDialogs.remove(dialog)
                        result.complete(value)
                    }
                }

                dialog = AlertDialog.Builder(this@BrowserActivity)
                    .setTitle(Uri.parse(p.uri).host ?: "Website")
                    .setMessage("Allow this website to $action?")
                    .setNegativeButton("Block") { _, _ ->
                        answer(denied)
                    }
                    .setPositiveButton("Allow") { _, _ ->
                        answer(allowed)
                    }
                    .setOnCancelListener {
                        answer(denied)
                    }
                    .create()

                permissionDialogs[dialog] = {
                    answer(denied)
                }

                dialog.setOnDismissListener {
                    answer(denied)
                }

                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()

                return result
            }
        }

        s.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(s: GeckoSession, m: MediaSession) {
                media[s] = m
            }

            override fun onDeactivated(s: GeckoSession, m: MediaSession) {
                media.remove(s)

                if (s == session) {
                    playing = false
                    updateKeepAwake()
                }
            }

            override fun onPlay(s: GeckoSession, m: MediaSession) {
                if (s == session && resumed && panel == null) {
                    playing = true
                    updateKeepAwake()
                } else {
                    m.pause()
                }
            }

            override fun onPause(s: GeckoSession, m: MediaSession) {
                if (s == session) {
                    playing = false
                    updateKeepAwake()
                }
            }

            override fun onStop(s: GeckoSession, m: MediaSession) {
                onPause(s, m)
            }
        }

        return s
    }

    private fun currentUrl() =
        sessions.lastOrNull()?.let {
            failures[it]?.url ?: urls[it]
        }.orEmpty()

    private fun isErrorDocument(url: String) =
        url.startsWith("data:text/html") ||
            url.startsWith("about:neterror") ||
            url.startsWith("about:certerror")

    private fun submitAddress() {
        val text = address.text.toString().trim()
        if (text.isNotEmpty()) load(addressOrSearch(text))
    }

    private fun load(url: String) {
        // An address, bookmark, or history selection starts a fresh visit.
        releaseBackgroundWindows()

        if (stopped.contains(session)) replaceStopped()

        failures.remove(session)
        urls[session] = url

        hideKeyboard()
        showPage()
        setLoading(true)

        address.setText(url, false)
        gecko.requestFocus()

        session.loadUri(url)
    }

    private fun retry() {
        if (currentUrl().isBlank()) return

        if (
            stopped.contains(session) ||
            failures.containsKey(session)
        ) {
            load(currentUrl())
        } else {
            showPage()
            setLoading(true)
            session.reload()
        }
    }

    private fun attachCurrent() {
        gecko.releaseSession()
        gecko.setSession(session)
        updateAddress()
        updateButtons()
    }

    private fun failed(s: GeckoSession, message: String) {
        if (!sessions.contains(s)) return

        stopped.add(s)
        failures[s] = PageFailure(urls[s].orEmpty(), message)

        Log.e(TAG, message)

        try {
            File(filesDir, "last-content-failure.txt").writeText(
                "${Date()}\n$message\n" +
                    "Host: ${Uri.parse(urls[s].orEmpty()).host}\n" +
                    "Retained windows: ${sessions.size}\n"
            )
        } catch (_: Exception) {
        }

        if (s == session) {
            setFullscreen(false)
            playing = false
            updateKeepAwake()
            showError("$message Use Retry page to reopen it.")
        }
    }

    private fun replaceStopped() {
        val old = session
        val url = urls[old].orEmpty()

        gecko.releaseSession()
        sessions.removeAt(sessions.lastIndex)

        if (old.isOpen) old.close()

        forget(old)

        sessions.add(
            makeSession().also {
                it.open(runtime)
                urls[it] = url
            }
        )

        gecko.setSession(session)
    }

    private fun forget(s: GeckoSession) {
        urls.remove(s)
        committedUrls.remove(s)
        titles.remove(s)
        back.remove(s)
        forward.remove(s)
        failures.remove(s)
        media.remove(s)
        stopped.remove(s)
    }

    private fun disposeSession(s: GeckoSession) {
        media[s]?.pause()

        if (s.isOpen) {
            s.setFocused(false)
            s.setActive(false)
            s.close()
        }

        forget(s)
    }

    private fun releaseBackgroundWindows() {
        val current = sessions.lastOrNull() ?: return
        val previous = sessions.filter { it !== current }

        sessions.removeAll(previous.toSet())
        previous.forEach(::disposeSession)

        if (previous.isNotEmpty()) {
            Log.i(TAG, "Released ${previous.size} background window(s)")
        }
    }

    private fun closeChild() {
        setFullscreen(false)
        gecko.releaseSession()

        val child = sessions.removeAt(sessions.lastIndex)
        disposeSession(child)

        gecko.setSession(session)

        showPage()
        updateAddress()
        updateButtons()
    }

    private fun setLoading(value: Boolean) {
        loading = value

        if (value) {
            progress.progress = 0
            errorCover.visibility = View.GONE

            if (panel == null) gecko.visibility = View.VISIBLE
        }

        progress.visibility =
            if (value && panel == null) View.VISIBLE
            else View.GONE
    }

    private fun showError(message: String) {
        setLoading(false)

        playing = false
        updateKeepAwake()

        errorView.text = message
        errorCover.visibility =
            if (panel == null) View.VISIBLE
            else View.GONE

        if (panel == null) gecko.visibility = View.INVISIBLE

        updateAddress()
    }

    private fun updateAddress() {
        if (!address.hasFocus()) {
            address.setText(currentUrl(), false)
        }
    }

    private fun updateButtons() {
        findViewById<View>(R.id.tvBackButton).isEnabled =
            panel != null ||
                back[session] == true ||
                sessions.size > 1

        findViewById<View>(R.id.tvForwardButton).isEnabled =
            forward[session] == true

        findViewById<View>(R.id.tvRefreshButton).isEnabled =
            currentUrl().isNotBlank()

        findViewById<Button>(R.id.tvBookmarkButton).text =
            if (bookmarks().any { it.first == currentUrl() }) "Bookmarked"
            else "Bookmark"
    }

    private fun showPage() {
        downloadUpdate?.let(main::removeCallbacks)
        downloadUpdate = null

        panel?.let(container::removeView)
        panel = null
        panelName = "page"

        gecko.visibility = View.VISIBLE

        if (!stopped.contains(session)) {
            session.setActive(resumed)
            session.setFocused(resumed)
        }

        progress.visibility =
            if (loading) View.VISIBLE
            else View.GONE

        errorCover.visibility = View.GONE
        failures[session]?.let { showError(it.message) }

        updateButtons()
    }

    private fun showPanel(view: View, name: String) {
        downloadUpdate?.let(main::removeCallbacks)
        downloadUpdate = null

        if (fullscreen) {
            session.exitFullScreen()
            setFullscreen(false)
        }

        media[session]?.pause()
        playing = false
        updateKeepAwake()

        if (!stopped.contains(session)) {
            session.setFocused(false)
            session.setActive(false)
        }

        gecko.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        errorCover.visibility = View.GONE

        panel?.let(container::removeView)

        panel = view
        panelName = name

        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        updateButtons()
    }

    private fun bookmarks(): List<Pair<String, String>> {
        val array = try {
            JSONArray(prefs.getString("bookmarks", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        return (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.let { item ->
                item.optString("url").takeIf(String::isNotBlank)?.let { url ->
                    url to item.optString("title", url)
                }
            }
        }
    }

    private fun saveBookmarks(items: List<Pair<String, String>>) {
        val array = JSONArray()

        items.forEach {
            array.put(
                JSONObject()
                    .put("url", it.first)
                    .put("title", it.second)
            )
        }

        prefs.edit().putString("bookmarks", array.toString()).apply()
        updateButtons()
    }

    private fun toggleBookmark() {
        val url = currentUrl()

        if (Uri.parse(url).scheme !in listOf("http", "https")) {
            toast("Open a website first.")
            return
        }

        val all = bookmarks()

        if (all.any { it.first == url }) {
            AlertDialog.Builder(this)
                .setTitle("Remove bookmark?")
                .setPositiveButton("Remove") { _, _ ->
                    saveBookmarks(all.filterNot { it.first == url })

                    if (panelName == "home") showHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            saveBookmarks(
                all + (
                    url to titles[session].orEmpty()
                        .ifBlank { Uri.parse(url).host ?: url }
                    )
            )

            toast("Bookmark saved")
        }
    }

    private fun showHome() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(32))
            setBackgroundColor(Color.rgb(228, 226, 221))
        }

        body.addView(
            label(
                getString(R.string.tv_home_title),
                25,
                Color.DKGRAY
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        body.addView(
            label(
                getString(R.string.tv_saved_sites),
                20,
                Color.DKGRAY
            )
        )

        body.addView(
            label(
                getString(R.string.tv_saved_sites_hint),
                14,
                Color.DKGRAY
            )
        )

        val all = bookmarks()

        if (all.isEmpty()) {
            body.addView(
                label(
                    getString(R.string.tv_no_bookmarks),
                    17,
                    Color.DKGRAY
                )
            )
        }

        val columns = (
            (
                resources.displayMetrics.widthPixels /
                    resources.displayMetrics.density - 64
                ) / 180
            ).toInt().coerceIn(2, 6)

        all.chunked(columns).forEach { group ->
            val row = LinearLayout(this)

            group.forEach { (url, title) ->
                val button = Button(this).apply {
                    text = "${title.take(1).uppercase()}\n\n$title"
                    isAllCaps = false
                    textSize = 17f
                    setTextColor(Color.rgb(32, 33, 36))
                    maxLines = 4
                    background = tileBackground()

                    setOnClickListener {
                        load(url)
                    }

                    setOnLongClickListener {
                        AlertDialog.Builder(this@BrowserActivity)
                            .setTitle(title)
                            .setMessage("Remove this bookmark?")
                            .setPositiveButton("Remove") { _, _ ->
                                saveBookmarks(
                                    bookmarks().filterNot { it.first == url }
                                )
                                showHome()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()

                        true
                    }
                }

                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, dp(156), 1f).apply {
                        setMargins(dp(6), dp(10), dp(6), dp(6))
                    }
                )
            }

            repeat(columns - group.size) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, 1, 1f)
                )
            }

            body.addView(row)
        }

        showPanel(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body)
            },
            "home"
        )

        address.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                (
                    all.map { it.first } +
                        history.entries().take(30).map { it.url }
                    ).distinct()
            )
        )

        address.threshold = 1

        // Keep the pointer available until the address field is selected.
        address.clearFocus()
        findViewById<View>(R.id.tvHomeButton).requestFocus()
    }

    private fun showHistory() {
        val body = listBody("History")
        val items = history.entries()

        action(body, "Clear history") {
            AlertDialog.Builder(this)
                .setTitle("Clear browsing history?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    history.clear()
                    showHistory()
                }
                .show()
        }

        if (items.isEmpty()) {
            body.addView(label("No browsing history yet.", 18))
        }

        items.forEach { item ->
            val row = LinearLayout(this)

            action(
                row,
                "${item.title.ifBlank { item.url }}\n${item.url}",
                1f
            ) {
                load(item.url)
            }

            action(row, "Remove") {
                history.remove(item.url)
                showHistory()
            }

            body.addView(row)
        }

        showPanel(
            ScrollView(this).apply { addView(body) },
            "history"
        )
    }

    private fun showDownloads() {
        val body = listBody("Downloads")
        val rows = linkedMapOf<Long, Pair<LinearLayout, TextView>>()

        fun update() {
            val entries = downloads.entries()
            val ids = entries.map { it.id }.toSet()

            rows.keys.filterNot { it in ids }.toList().forEach { id ->
                body.removeView(rows.remove(id)?.first)
            }

            entries.forEach { entry ->
                val status = when (entry.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> "Complete"
                    DownloadManager.STATUS_FAILED -> "Failed / interrupted"
                    else -> "Downloading"
                }

                val text = "$status · ${entry.downloaded} bytes" +
                    if (entry.total > 0) " / ${entry.total}" else ""

                val existing = rows[entry.id]

                if (existing != null) {
                    existing.second.text = text
                } else {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }

                    row.addView(label(entry.title, 20))

                    val detail = label(text, 15)
                    row.addView(detail)

                    val controls = LinearLayout(this)

                    action(controls, "Open") {
                        val fresh = downloads.entries()
                            .firstOrNull { it.id == entry.id }

                        if (
                            fresh?.status ==
                            DownloadManager.STATUS_SUCCESSFUL
                        ) {
                            downloads.open(fresh)
                        } else {
                            toast("The download is not complete.")
                        }
                    }

                    action(controls, "Cancel / delete") {
                        AlertDialog.Builder(this)
                            .setTitle("Cancel or delete this download?")
                            .setMessage(entry.title)
                            .setNegativeButton("Keep", null)
                            .setPositiveButton("Delete") { _, _ ->
                                downloads.remove(entry.id)
                                update()
                            }
                            .show()
                    }

                    row.addView(controls)
                    body.addView(row)
                    rows[entry.id] = row to detail
                }
            }
        }

        update()

        if (rows.isEmpty()) {
            body.addView(label("No downloads yet.", 18))
        }

        showPanel(
            ScrollView(this).apply { addView(body) },
            "downloads"
        )

        downloadUpdate = object : Runnable {
            override fun run() {
                if (!closed && panelName == "downloads" && resumed) {
                    update()
                    main.postDelayed(this, 1000)
                }
            }
        }.also {
            main.postDelayed(it, 1000)
        }
    }

    private fun showSettings() {
        val settings = layoutInflater.inflate(
            R.layout.fragment_settings,
            container,
            false
        )

        settings.findViewById<View>(R.id.extensionsButton)
            .setOnClickListener {
                startActivity(Intent(this, ExtensionsActivity::class.java))
            }

        settings.findViewById<View>(R.id.playbackDiagnosticsButton)
            .setOnClickListener {
                showDiagnostics()
            }

        val desktop = settings.findViewById<CheckBox>(R.id.telemetryButton)
        desktop.isChecked = prefs.getBoolean("desktop", false)

        desktop.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("desktop", value).apply()

            sessions.forEach {
                it.settings.userAgentMode =
                    if (value) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

                it.settings.viewportMode =
                    if (value) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            }

            if (
                currentUrl().isNotBlank() &&
                !stopped.contains(session)
            ) {
                session.reload()
            }
        }

        settings.findViewById<View>(R.id.telemetryView)
            .setOnClickListener {
                desktop.toggle()
            }

        settings.findViewById<ImageView>(R.id.ic_lock)
            .setImageResource(R.drawable.ic_lock)

        settings.findViewById<View>(R.id.aboutButton)
            .setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name))
                    .setMessage(
                        "Version ${BuildConfig.VERSION_NAME}\nGeckoView 159\n\n" +
                            "TV browser interface with Mozilla GeckoView.\n" +
                            "Source and license notices are included with the project; " +
                            "Firefox TV source is available under Mozilla Public License 2.0."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }

        settings.findViewById<View>(R.id.privacyNoticeButton)
            .setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Privacy")
                    .setMessage(
                        "Bookmarks, history, downloads, cookies, and saved crash details are stored " +
                            "on this TV. This app does not send its crash details automatically. " +
                            "Websites and installed extensions have their own privacy policies."
                    )
                    .setNeutralButton("GeckoView privacy") { _, _ ->
                        load("https://www.mozilla.org/privacy/firefox/")
                    }
                    .setPositiveButton("OK", null)
                    .show()
            }

        settings.findViewById<View>(R.id.deleteButton)
            .setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear cookies and website data?")
                    .setMessage(
                        "This signs you out of websites. " +
                            "Bookmarks, history, and downloaded files are kept."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        runtime.storageController
                            .clearData(StorageController.ClearFlags.ALL)
                            .accept(
                                { toast("Website data cleared.") },
                                { toast("Could not clear website data.") }
                            )
                    }
                    .show()
            }

        showPanel(settings, "settings")
    }

    private fun showDiagnostics() {
        val body = listBody("Playback diagnostics")
        val report = label("Collecting…", 15)
        body.addView(report)

        var text = ""

        action(body, "Copy report") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(
                    ClipData.newPlainText("TV browser diagnostics", text)
                )

            toast("Report copied.")
        }

        action(body, "Back to settings") {
            showSettings()
        }

        showPanel(
            ScrollView(this).apply { addView(body) },
            "diagnostics"
        )

        val retained = sessions.size
        val stoppedCount = stopped.size

        WORKER.execute {
            text = try {
                val memory = ActivityManager.MemoryInfo()
                val manager = getSystemService(ActivityManager::class.java)
                manager.getMemoryInfo(memory)

                val saved = File(filesDir, "last-content-failure.txt")

                "Retained browser windows: $retained / $MAX_WINDOWS\n" +
                    "Stopped windows: $stoppedCount\n" +
                    "Android low-memory flag: ${memory.lowMemory}\n" +
                    "Available RAM: ${memory.availMem / 1048576} MiB\n" +
                    "Total RAM: ${memory.totalMem / 1048576} MiB\n\n" +
                    "Last website-process failure:\n" +
                    (
                        if (saved.isFile) saved.readText().take(2000)
                        else "None recorded.\n"
                        ) +
                    "\n" +
                    BrowserApp.crashReport(applicationContext) +
                    "\n\n" +
                    CodecReport.collect()
            } catch (error: Exception) {
                "Could not collect report: ${error.message}"
            }

            main.post {
                if (!closed) report.text = text
            }
        }
    }

    private fun navigateBack() {
        when {
            fullscreen -> {
                session.exitFullScreen()
                setFullscreen(false)
            }

            panel != null && currentUrl().isNotBlank() -> showPage()
            panel != null && panelName != "home" -> showHome()

            panel != null -> {
                AlertDialog.Builder(this)
                    .setTitle("Exit browser?")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .show()
            }

            stopped.contains(session) -> {
                if (sessions.size > 1) closeChild() else showHome()
            }

            back[session] == true -> session.goBack()
            sessions.size > 1 -> closeChild()
            else -> showHome()
        }
    }

    @Deprecated("TV remote back handling")
    override fun onBackPressed() {
        navigateBack()
    }

    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        pointerInVideo = false

        hold.reset()
        root.pausePointer()

        toolbar.visibility = if (value) View.GONE else View.VISIBLE

        window.decorView.systemUiVisibility =
            if (value) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_FULLSCREEN
            }

        updateKeepAwake()

        if (value) gecko.requestFocus()
    }

    private fun updateKeepAwake() {
        if (resumed && panel == null && (fullscreen || playing)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun nativeTvPage(): Boolean {
        val uri = Uri.parse(currentUrl())
        val host = uri.host.orEmpty().lowercase()

        return (
            host == "youtube.com" ||
                host.endsWith(".youtube.com")
            ) && uri.path.orEmpty().startsWith("/tv")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::root.isInitialized) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }

        if (
            panel == null &&
            media[session] != null &&
            event.keyCode in listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_REWIND
            )
        ) {
            if (
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
                val active = media.getValue(session)

                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> active.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> active.pause()
                    KeyEvent.KEYCODE_MEDIA_STOP -> active.stop()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> active.seekForward()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> active.seekBackward()
                    else -> if (playing) active.pause() else active.play()
                }
            }

            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (fullscreen) {
                    session.exitFullScreen()
                    setFullscreen(false)
                }

                openKeyboard()
            }

            return true
        }

        val select = event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER
        )

        // TV keyboards may float without reporting IME insets.
        // Focused address editing must reach the native field first.
        val keyboard =
            address.hasFocus() ||
                root.keyboardVisible() ||
                event.device?.keyboardType ==
                    android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                currentFocus?.onCheckIsTextEditor() == true

        if (fullscreen && !keyboard && select) {
            val action =
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hold.down(event.eventTime, 700)
                } else {
                    hold.up(event.eventTime, 700, event.isCanceled)
                }

            when (action) {
                FullscreenPointerGesture.Action.TOGGLE -> {
                    pointerInVideo = !pointerInVideo
                    root.pausePointer()

                    toast(
                        if (pointerInVideo) "Video pointer on"
                        else "Video remote controls on"
                    )
                }

                FullscreenPointerGesture.Action.CLICK -> {
                    if (pointerInVideo) {
                        root.dispatchSelectClick()
                    } else {
                        val down = KeyEvent(
                            event.downTime,
                            event.eventTime,
                            KeyEvent.ACTION_DOWN,
                            event.keyCode,
                            0
                        )

                        val up = KeyEvent.changeAction(
                            down,
                            KeyEvent.ACTION_UP
                        )

                        gecko.dispatchKeyEvent(down)
                        gecko.dispatchKeyEvent(up)
                    }
                }

                else -> Unit
            }

            return true
        }

        return keys.dispatch(
            event,
            event.action,
            event.keyCode,
            event.repeatCount,
            keyboard,
            panel == null && (
                nativeTvPage() ||
                    fullscreen && !pointerInVideo
                ),
            !fullscreen || pointerInVideo,
            { root.handleRemoteKey(it) },
            {
                if (panel == null && !stopped.contains(session)) {
                    gecko.dispatchKeyEvent(it)
                } else {
                    false
                }
            },
            { super.dispatchKeyEvent(it) },
            { root.pausePointer() }
        )
    }

    private fun openKeyboard() {
        address.requestFocus()
        address.selectAll()

        val original = address.text.toString()

        address.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(
                    address,
                    InputMethodManager.SHOW_IMPLICIT
                )

            if (
                address.hasFocus() &&
                address.text.toString() == original
            ) {
                address.selectAll()
            }

            address.postDelayed({
                // Do not select over replacement text already being typed.
                if (
                    address.hasFocus() &&
                    address.text.toString() == original
                ) {
                    address.selectAll()
                }
            }, 180)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(address.windowToken, 0)

        address.clearFocus()
    }

    private fun externalLink(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Open another app?")
            .setMessage(Uri.parse(url).scheme ?: "External link")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open") { _, _ ->
                try {
                    val intent =
                        if (url.startsWith("intent:")) {
                            Intent.parseUri(
                                url,
                                Intent.URI_INTENT_SCHEME
                            )
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        }

                    intent.component = null
                    intent.selector = null
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)

                    startActivity(intent)
                } catch (_: Exception) {
                    toast("No installed app can open this link.")
                }
            }
            .show()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString?.takeIf {
                    Uri.parse(it).scheme in listOf("http", "https")
                }
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.let(::addressOrSearch)
            }

            else -> null
        }

        if (text.isNullOrBlank()) return false

        load(text)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        resumed = true
        extensionPermissions.attach()

        if (
            ::gecko.isInitialized &&
            panel == null &&
            !stopped.contains(session)
        ) {
            session.setActive(true)
            session.setFocused(true)
        }

        if (panelName == "downloads") showDownloads()

        updateKeepAwake()
    }

    override fun onPause() {
        resumed = false
        extensionPermissions.detach()

        root.pausePointer()
        keys.reset()
        hold.reset()

        downloadUpdate?.let(main::removeCallbacks)

        media[session]?.pause()
        playing = false

        if (!stopped.contains(session)) {
            session.setFocused(false)
            session.setActive(false)
        }

        updateKeepAwake()
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (panel == null && currentUrl().isNotBlank()) {
            out.putString("page", currentUrl())
        }

        super.onSaveInstanceState(out)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            releaseBackgroundWindows()

            if (::root.isInitialized && sessions.isNotEmpty()) {
                updateButtons()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseBackgroundWindows()

        if (::root.isInitialized && sessions.isNotEmpty()) {
            updateButtons()
        }
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        prompts.close()

        permissionDialogs.toMap().forEach { (dialog, cancel) ->
            cancel()
            dialog.dismiss()
        }

        if (::gecko.isInitialized) gecko.releaseSession()

        sessions.forEach {
            if (it.isOpen) it.close()
        }

        sessions.clear()
        super.onDestroy()
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun addressOrSearch(text: String) =
        if (SmartAddress.isAddress(text)) {
            SmartAddress.normalize(text)
        } else {
            "https://www.google.com/search?q=${Uri.encode(text)}"
        }

    private fun label(
        value: String,
        size: Int,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun listBody(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
        setBackgroundColor(Color.rgb(42, 44, 50))
        addView(label(title, 30))
    }

    private fun action(
        parent: LinearLayout,
        title: String,
        weight: Float = 0f,
        run: () -> Unit
    ) {
        parent.addView(
            Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 17f
                setOnClickListener { run() }
            },
            LinearLayout.LayoutParams(
                if (weight > 0) 0 else -2,
                -2,
                weight
            )
        )
    }

    private fun tileBackground(): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                shape(Color.rgb(180, 210, 240))
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                shape(Color.rgb(180, 210, 240))
            )
            addState(intArrayOf(), shape(Color.WHITE))
        }
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "TVBrowser"

        // Website popups and extra windows are always blocked.
        private const val MAX_WINDOWS = 1

        private val WORKER = Executors.newSingleThreadExecutor()
    }
}
