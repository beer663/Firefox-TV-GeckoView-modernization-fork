/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView
import org.mozilla.focus.widget.CheckableImageButton
import org.mozilla.tv.firefox.R
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.widget.InlineAutocompleteEditText

enum class NavigationEvent {
    HOME, SETTINGS, BACK, FORWARD, RELOAD, LOAD, TURBO, PIN_ACTION;

    companion object {
        fun fromViewClick(viewId: Int?) = when (viewId) {
            R.id.navButtonBack -> BACK
            R.id.navButtonForward -> FORWARD
            R.id.navButtonReload -> RELOAD
            R.id.navButtonHome -> HOME
            R.id.navButtonSettings -> SETTINGS
            R.id.pinButton -> PIN_ACTION
            else -> null
        }

        const val VAL_CHECKED = "checked"
        const val VAL_UNCHECKED = "unchecked"
    }
}

class BrowserNavigationOverlay @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0 )
    : LinearLayout(context, attrs, defStyle), View.OnClickListener {

    interface BrowserNavigationStateProvider {
        fun isBackEnabled(): Boolean
        fun isForwardEnabled(): Boolean
        fun getCurrentUrl(): String?
        fun isURLPinned(): Boolean
    }

    var onNavigationEvent: ((event: NavigationEvent, value: String?,
                             autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?) -> Unit)? = null
    var navigationStateProvider: BrowserNavigationStateProvider? = null

    /** Called inside [setVisibility] right before super.setVisibility is called. */
    var onPreSetVisibilityListener: ((isVisible: Boolean) -> Unit)? = null

    private val navButtonHome: ImageButton by lazy { findViewById(R.id.navButtonHome) }
    private val navButtonSettings: ImageButton by lazy { findViewById(R.id.navButtonSettings) }
    private val pinButton: CheckableImageButton by lazy { findViewById(R.id.pinButton) }
    private val navCloseHint: TextView by lazy { findViewById(R.id.navCloseHint) }
    init {
        LayoutInflater.from(context)
                .inflate(R.layout.browser_overlay, this, true)

        listOf(navButtonHome, navButtonSettings, pinButton)
                .forEach {
                    it.setOnClickListener(this)
                }

        navButtonSettings.setImageResource(R.drawable.ic_settings)

        val tintDrawable: (Drawable?) -> Unit = {
            it?.setTint(ContextCompat.getColor(context, R.color.tv_white))
        }
        navCloseHint.compoundDrawablesRelative.forEach(tintDrawable)
    }

    override fun onClick(view: View?) {
        val event = NavigationEvent.fromViewClick(view?.id) ?: return
        var value: String? = null

        val isTrackingProtectionEnabled = Settings.getInstance(context).isBlockingEnabled
        val isPinButtonChecked = pinButton.isChecked

        when (event) {
            NavigationEvent.PIN_ACTION -> {
                value = if (isPinButtonChecked) NavigationEvent.VAL_CHECKED
                else NavigationEvent.VAL_UNCHECKED
            }
            else -> Unit
        }

        onNavigationEvent?.invoke(event, value, null)
        TelemetryWrapper.overlayClickEvent(event, isTrackingProtectionEnabled, isPinButtonChecked)
    }

    fun updateOverlayForCurrentState() {
        // Back, Forward and Refresh were moved to MainActivity's toolbar.
        pinButton.isChecked = navigationStateProvider?.isURLPinned() ?: false
        if (findFocus() == null) {
            navButtonHome.requestFocus()
        }
    }

    override fun setVisibility(visibility: Int) {
        onPreSetVisibilityListener?.invoke(visibility == View.VISIBLE)
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            navButtonHome.requestFocus()
            updateOverlayForCurrentState()
        }
    }
}
