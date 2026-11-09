/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import android.content.Context
import android.text.TextUtils
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.home.HomeFragment
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.session.Source
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.utils.OnUrlEnteredListener
import org.mozilla.focus.utils.UrlUtils
import org.mozilla.focus.widget.InlineAutocompleteEditText
import org.mozilla.tv.firefox.R

object ScreenController {
    private const val SETTINGS_BACK_STACK = "settings-screen"

    fun onUrlEnteredInner(
        context: Context,
        fragmentManager: FragmentManager,
        urlStr: String,
        isTextInput: Boolean,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?,
        inputLocation: UrlTextInputLocation?
    ) {
        if (TextUtils.isEmpty(urlStr.trim())) {
            return
        }

        val isUrl = UrlUtils.isUrl(urlStr)
        val updatedUrlStr = if (isUrl) {
            UrlUtils.normalize(urlStr)
        } else {
            UrlUtils.createSearchUrl(context, urlStr)
        }

        showBrowserScreenForUrl(
            fragmentManager,
            updatedUrlStr,
            Source.USER_ENTERED
        )

        if (isTextInput) {
            if (autocompleteResult == null) {
                throw IllegalArgumentException(
                    "Expected non-null autocomplete result for text input"
                )
            }

            if (inputLocation == null) {
                throw IllegalArgumentException(
                    "Expected non-null input location for text input"
                )
            }

            TelemetryWrapper.urlBarEvent(
                isUrl,
                autocompleteResult,
                inputLocation
            )
        }
    }

    fun showHomeScreen(
        fragmentManager: FragmentManager,
        onUrlEnteredListener: OnUrlEnteredListener
    ) {
        val homeFragment = fragmentManager.findFragmentByTag(
            HomeFragment.FRAGMENT_TAG
        ) as HomeFragment?

        if (homeFragment != null && homeFragment.isVisible) {
            return
        }

        fragmentManager.popBackStackImmediate(
            null,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        val newHomeFragment = HomeFragment.create()
        newHomeFragment.onSettingsPressed = {
            showSettingsScreen(fragmentManager)
        }
        newHomeFragment.onUrlEnteredListener = onUrlEnteredListener

        fragmentManager.beginTransaction()
            .replace(
                R.id.container,
                newHomeFragment,
                HomeFragment.FRAGMENT_TAG
            )
            .commit()
    }

    fun showSettingsScreen(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return

        val existing = fragmentManager.findFragmentByTag(
            SettingsFragment.FRAGMENT_TAG
        )
        if (existing?.isAdded == true) return

        val current = fragmentManager.fragments.lastOrNull {
            it.id == R.id.container && it.isAdded && !it.isHidden
        }

        val transaction = fragmentManager.beginTransaction()
            .setReorderingAllowed(true)

        if (current != null) {
            transaction.hide(current)
                .setMaxLifecycle(current, Lifecycle.State.STARTED)
        }

        transaction.add(
            R.id.container,
            SettingsFragment.create(),
            SettingsFragment.FRAGMENT_TAG
        )
            .addToBackStack(SETTINGS_BACK_STACK)
            .commit()
    }

    fun showBrowserScreenForCurrentSession(
        fragmentManager: FragmentManager,
        sessionManager: SessionManager
    ) {
        val currentSession = sessionManager.currentSession

        val fragment = fragmentManager.findFragmentByTag(
            BrowserFragment.FRAGMENT_TAG
        ) as BrowserFragment?

        if (
            fragment != null &&
            fragment.session.isSameAs(currentSession)
        ) {
            return
        }

        fragmentManager.beginTransaction()
            .replace(
                R.id.container,
                BrowserFragment.createForSession(currentSession),
                BrowserFragment.FRAGMENT_TAG
            )
            .addToBackStack(null)
            .commit()
    }

    fun showBrowserScreenForUrl(
        fragmentManager: FragmentManager,
        url: String,
        source: Source
    ) {
        if (!fragmentManager.isStateSaved) {
            val settings = fragmentManager.findFragmentByTag(
                SettingsFragment.FRAGMENT_TAG
            )

            if (settings?.isAdded == true) {
                fragmentManager.popBackStackImmediate(
                    SETTINGS_BACK_STACK,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE
                )
            }
        }

        val browserFragment = fragmentManager.findFragmentByTag(
            BrowserFragment.FRAGMENT_TAG
        ) as? BrowserFragment

        if (browserFragment != null && browserFragment.isVisible) {
            browserFragment.loadUrl(url)
        } else {
            SessionManager.getInstance().createSession(source, url)
        }
    }
}
