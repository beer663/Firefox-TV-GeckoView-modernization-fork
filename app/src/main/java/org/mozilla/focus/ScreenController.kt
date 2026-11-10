/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus

import android.content.Context
import android.text.TextUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.home.HomeFragment
import org.mozilla.focus.library.LibraryFragment
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
        autocompleteResult:
            InlineAutocompleteEditText.AutocompleteResult?,
        inputLocation: UrlTextInputLocation?
    ) {
        if (TextUtils.isEmpty(urlStr.trim())) return

        val input = urlStr.trim()
        val isUrl = UrlUtils.isUrl(input)

        val updatedUrlStr = if (isUrl) {
            UrlUtils.normalize(input)
        } else {
            UrlUtils.createSearchUrl(context, input)
        }

        showBrowserScreenForUrl(
            fragmentManager,
            updatedUrlStr,
            Source.USER_ENTERED
        )

        if (isTextInput) {
            TelemetryWrapper.urlBarEvent(
                isUrl,
                autocompleteResult
                    ?: InlineAutocompleteEditText
                        .AutocompleteResult.emptyResult(),
                inputLocation ?: UrlTextInputLocation.HOME
            )
        }
    }

    fun showHomeScreen(
        fragmentManager: FragmentManager,
        onUrlEnteredListener: OnUrlEnteredListener
    ) {
        val homeFragment =
            fragmentManager.findFragmentByTag(
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

        newHomeFragment.onUrlEnteredListener =
            onUrlEnteredListener

        fragmentManager.beginTransaction()
            .replace(
                R.id.container,
                newHomeFragment,
                HomeFragment.FRAGMENT_TAG
            )
            .commit()
    }

    fun showSettingsScreen(
        fragmentManager: FragmentManager
    ) = showPanel(
        fragmentManager,
        SettingsFragment.FRAGMENT_TAG
    ) {
        SettingsFragment.create()
    }

    fun showDownloadsScreen(
        fragmentManager: FragmentManager
    ) = showPanel(fragmentManager, "downloads") {
        LibraryFragment.create(false)
    }

    fun showHistoryScreen(
        fragmentManager: FragmentManager
    ) = showPanel(fragmentManager, "history") {
        LibraryFragment.create(true)
    }

    private fun showPanel(
        fragmentManager: FragmentManager,
        tag: String,
        create: () -> Fragment
    ) {
        if (fragmentManager.isStateSaved) return

        fragmentManager.executePendingTransactions()

        if (
            fragmentManager.findFragmentByTag(tag)
                ?.isVisible == true
        ) return

        fragmentManager.popBackStackImmediate(
            SETTINGS_BACK_STACK,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        val current =
            fragmentManager.fragments.lastOrNull {
                it.id == R.id.container &&
                    it.isAdded &&
                    !it.isHidden
            }

        val transaction =
            fragmentManager.beginTransaction()
                .setReorderingAllowed(true)

        if (current != null) {
            transaction.hide(current)
                .setMaxLifecycle(
                    current,
                    Lifecycle.State.STARTED
                )
        }

        transaction.add(
            R.id.container,
            create(),
            tag
        )
            .addToBackStack(SETTINGS_BACK_STACK)
            .commit()
    }

    fun showBrowserScreenForCurrentSession(
        fragmentManager: FragmentManager,
        sessionManager: SessionManager
    ) {
        val currentSession = sessionManager.currentSession

        val fragment =
            fragmentManager.findFragmentByTag(
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
                BrowserFragment.createForSession(
                    currentSession
                ),
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
        if (fragmentManager.isStateSaved) return

        fragmentManager.popBackStackImmediate(
            SETTINGS_BACK_STACK,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        val browserFragment =
            fragmentManager.findFragmentByTag(
                BrowserFragment.FRAGMENT_TAG
            ) as? BrowserFragment

        if (
            browserFragment != null &&
            browserFragment.isVisible
        ) {
            browserFragment.loadUrl(url)
        } else {
            SessionManager.getInstance()
                .createSession(source, url)
        }
    }
}
