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
import org.mozilla.focus.autocomplete.UrlAutoCompleteFilter
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.widget.InlineAutocompleteEditText

private const val NAVIGATION_BUTTON_ENABLED_ALPHA = 1.0f
private const val NAVIGATION_BUTTON_DISABLED_ALPHA = 0.3f

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

    private val navButtonBack: ImageButton by lazy { findViewById(R.id.navButtonBack) }
    private val navButtonForward: ImageButton by lazy { findViewById(R.id.navButtonForward) }
    private val navButtonReload: ImageButton by lazy { findViewById(R.id.navButtonReload) }
    private val navButtonHome: ImageButton by lazy { findViewById(R.id.navButtonHome) }
    private val navButtonSettings: ImageButton by lazy { findViewById(R.id.navButtonSettings) }
    private val pinButton: CheckableImageButton by lazy { findViewById(R.id.pinButton) }
    private val navCloseHint: TextView by lazy { findViewById(R.id.navCloseHint) }
    private val navUrlInput: InlineAutocompleteEditText by lazy { findViewById(R.id.navUrlInput) }

    private var hasUserChangedURLSinceEditTextFocused = false

    init {
        LayoutInflater.from(context)
                .inflate(R.layout.browser_overlay, this, true)

        listOf(navButtonBack, navButtonForward, navButtonReload, navButtonHome, navButtonSettings,
                pinButton)
                .forEach {
                    it.setOnClickListener(this)
                }

        setupUrlInput()
        navButtonSettings.setImageResource(R.drawable.ic_settings)

        val tintDrawable: (Drawable?) -> Unit = {
            it?.setTint(ContextCompat.getColor(context, R.color.tv_white))
        }
        navCloseHint.compoundDrawablesRelative.forEach(tintDrawable)
        navUrlInput.compoundDrawablesRelative.forEach(tintDrawable)
    }

    private fun setupUrlInput() = with (navUrlInput) {
        setOnCommitListener {
            val userInput = text.toString()
            if (userInput.isNotEmpty()) {
                val cachedAutocompleteResult = lastAutocompleteResult
                setText(cachedAutocompleteResult.text)
                onNavigationEvent?.invoke(NavigationEvent.LOAD, userInput, cachedAutocompleteResult)
            }
        }

        val autocompleteFilter = UrlAutoCompleteFilter()
        autocompleteFilter.load(context.applicationContext)
        setOnFilterListener { searchText, view ->
            autocompleteFilter.onFilter(searchText, view)
        }

        setOnUserInputListener { hasUserChangedURLSinceEditTextFocused = true }
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hasUserChangedURLSinceEditTextFocused = false
                updateOverlayForCurrentState()
            }
        }
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
        val canGoBack = navigationStateProvider?.isBackEnabled() ?: false
        navButtonBack.isEnabled = canGoBack
        navButtonBack.isFocusable = canGoBack
        navButtonBack.alpha =
            if (canGoBack) NAVIGATION_BUTTON_ENABLED_ALPHA
            else NAVIGATION_BUTTON_DISABLED_ALPHA

        val canGoForward = navigationStateProvider?.isForwardEnabled() ?: false
        navButtonForward.isEnabled = canGoForward
        navButtonForward.isFocusable = canGoForward
        navButtonForward.alpha =
            if (canGoForward) NAVIGATION_BUTTON_ENABLED_ALPHA
            else NAVIGATION_BUTTON_DISABLED_ALPHA

        pinButton.isChecked = navigationStateProvider?.isURLPinned() ?: false

        navButtonReload.nextFocusLeftId = when {
            canGoForward -> R.id.navButtonForward
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonReload
        }
        navButtonForward.nextFocusLeftId = when {
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonForward
        }

        maybeUpdateOverlayURLForCurrentState()

        if (findFocus() == null) {
            requestFocus()
        }
    }

    private fun maybeUpdateOverlayURLForCurrentState() {
        // Keep redirects reflected in the address field without replacing user input.
        if (!hasUserChangedURLSinceEditTextFocused) {
            navUrlInput.setText(navigationStateProvider?.getCurrentUrl())
        }
    }

    override fun setVisibility(visibility: Int) {
        onPreSetVisibilityListener?.invoke(visibility == View.VISIBLE)
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            navUrlInput.requestFocus()
            updateOverlayForCurrentState()
        }
    }
}
