/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import org.mozilla.tv.firefox.R

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.ext.toSafeIntent
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

class MainActivity : LocaleAwareAppCompatActivity(), OnUrlEnteredListener {

    private val sessionManager = SessionManager.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable crash reporting. Don't add anything above here because if it crashes, we won't know.
        SentryWrapper.init(this)

        val intent = SafeIntent(intent)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setContentView(R.layout.activity_main)

        IntentValidator.validateOnCreate(this, intent, savedInstanceState, ::onValidBrowserIntent)
        sessionManager.sessions.observe(this, object : NonNullObserver<List<Session>>() {
            public override fun onValueChanged(sessions: List<Session>) {
                if (sessions.isEmpty()) {
                    // There's no active session. Show the URL input screen so that the user can
                    // start a new session.
                    ScreenController.showHomeScreen(supportFragmentManager, this@MainActivity)
                } else {
                    ScreenController.showBrowserScreenForCurrentSession(supportFragmentManager, sessionManager)
                }

                if (Settings.getInstance(this@MainActivity).shouldShowOnboarding()) {
                    val onboardingIntent = Intent(this@MainActivity, OnboardingActivity::class.java)
                    startActivity(onboardingIntent)
                }
            }
        })

        WebViewProvider.preload(this)
    }

    override fun onNewIntent(unsafeIntent: Intent) {
        IntentValidator.validate(this, unsafeIntent.toSafeIntent(), ::onValidBrowserIntent)
    }

    private fun onValidBrowserIntent(url: String, source: Source) {
        ScreenController.showBrowserScreenForUrl(supportFragmentManager, url, source)
    }

    override fun applyLocale() {
        // We don't care here: all our fragments update themselves as appropriate
    }

    override fun onResume() {
        super.onResume()
        TelemetryWrapper.startSession(this)
    }

    override fun onPause() {
        super.onPause()
        TelemetryWrapper.stopSession(this)
    }

    override fun onStop() {
        super.onStop()
        TelemetryWrapper.stopMainActivity()
    }

    override fun onBackPressed() {
        val fragmentManager = supportFragmentManager
        val browserFragment = fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?
        if (browserFragment != null &&
                browserFragment.isVisible &&
                browserFragment.onBackPressed()) {
            // The Browser fragment handles back presses on its own because it might just go back
            // in the browsing history.
            return
        }
        super.onBackPressed()
    }

    override fun onNonTextInputUrlEntered(urlStr: String) {
        ViewUtils.hideKeyboard(findViewById(R.id.container))
        ScreenController.onUrlEnteredInner(this, supportFragmentManager, urlStr, false,
                null, null)
    }

    override fun onTextInputUrlEntered(urlStr: String,
                                       autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?,
                                       inputLocation: UrlTextInputLocation?) {
        ViewUtils.hideKeyboard(findViewById(R.id.container))
        // It'd be much cleaner/safer to do this with a kotlin callback.
        ScreenController.onUrlEnteredInner(this, supportFragmentManager, urlStr, true,
                autocompleteResult, inputLocation)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragmentManager = supportFragmentManager
        val browserFragment = fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?
        val homeFragment = fragmentManager.findFragmentByTag(HomeFragment.FRAGMENT_TAG) as HomeFragment?

        return if (browserFragment != null && browserFragment.isVisible) {
            browserFragment.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        } else if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        } else {
            super.dispatchKeyEvent(event)
        }
    }

}
