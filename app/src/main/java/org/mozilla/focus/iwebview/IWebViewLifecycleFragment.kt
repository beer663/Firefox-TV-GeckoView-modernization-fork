/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.iwebview

import android.os.Bundle
import android.view.View
import androidx.annotation.UiThread
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.locale.LocaleManager
import org.mozilla.focus.session.Session
import org.mozilla.focus.utils.Settings
import org.mozilla.tv.firefox.R
import java.util.Locale

abstract class IWebViewLifecycleFragment : LocaleAwareFragment() {
    abstract val initialUrl: String
    abstract val session: Session
    abstract val iWebViewCallback: IWebView.Callback

    var webView: IWebView? = null
        @UiThread get
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = (view.findViewById<View>(R.id.webview) as IWebView).apply {
            callback = iWebViewCallback
            session.isBlockingEnabled =
                Settings.getInstance(requireContext()).isBlockingEnabled
            setBlockingEnabled(session.isBlockingEnabled)
            restoreWebViewOrLoadInitialUrl(this)
        }
    }

    private fun restoreWebViewOrLoadInitialUrl(webView: IWebView) {
        if (session.hasWebViewState()) {
            webView.restoreWebViewState(session)
        } else if (initialUrl.isNotEmpty()) {
            webView.loadUrl(initialUrl)
        }
    }

    override fun onPause() {
        webView?.let {
            it.saveWebViewState(session)
            it.onPause()
            it.pauseTimers()
        }
        super.onPause()
    }

    override fun onResume() {
        webView?.onResume()
        webView?.resumeTimers()
        super.onResume()
    }

    override fun onStart() {
        super.onStart()
        webView?.onStart()
    }

    override fun onStop() {
        super.onStop()
        webView?.onStop()
    }

    override fun onDestroyView() {
        webView?.let {
            it.saveWebViewState(session)
            it.callback = null
            it.destroy()
        }

        webView = null
        super.onDestroyView()
    }

    override fun applyLocale() {
        val context = requireContext()
        val localeManager = LocaleManager.getInstance()

        if (!localeManager.isMirroringSystemLocale(context)) {
            val currentLocale = localeManager.getCurrentLocale(context)
            Locale.setDefault(currentLocale)

            val resources = context.resources
            val config = resources.configuration
            config.setLocale(currentLocale)
            resources.updateConfiguration(config, null)
        }
    }
}
