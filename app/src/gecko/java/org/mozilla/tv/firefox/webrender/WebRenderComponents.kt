/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.webrender

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.support.utils.SafeIntent
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.tv.firefox.R

/**
 * Helper class for lazily instantiating and keeping references to components needed by the
 * application.
 */
class WebRenderComponents(applicationContext: Context, systemUserAgent: String) {
    // The first intent the App was launched with.  Used to pass configuration through to Gecko.
    private var launchSafeIntent: SafeIntent? = null

    fun notifyLaunchWithSafeIntent(safeIntent: SafeIntent): Boolean {
        // We can't access the property reference outside of our own lexical scope,
        // so this helper must be in this class.
        if (launchSafeIntent == null) {
            launchSafeIntent = safeIntent
            return true
        }
        return false
    }

    val engine: Engine by lazy {
        val runtimeSettingsBuilder = GeckoRuntimeSettings.Builder()
        // Legacy Mozilla Components helper is not used by the GeckoSession browser.
        // GeckoView 159 removed this runtime autoplay setter.

        val runtime = GeckoRuntime.create(applicationContext,
                runtimeSettingsBuilder.build())

        GeckoEngine(applicationContext, DefaultSettings(
                userAgentString = systemUserAgent,

                displayZoomControls = false,
                loadWithOverviewMode = true, // To respect the html viewport

                // We don't have a reason for users to access local files; assets can still
                // be loaded via file:///android_asset/
                allowFileAccess = false,
                allowContentAccess = false,

                remoteDebuggingEnabled = false,

                mediaPlaybackRequiresUserGesture = false // Allows auto-play (which improves YouTube experience).
        ), runtime)
    }

    val sessionManager by lazy { SessionManager(engine) }

    val sessionUseCases by lazy { SessionUseCases(sessionManager) }
}
