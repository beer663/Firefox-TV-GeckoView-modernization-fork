/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import org.mozilla.tv.firefox.R

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import org.mozilla.focus.extensions.ExtensionsActivity
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.ImageView
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.browser.InfoActivity
import org.mozilla.focus.ext.getAccessibilityManager
import org.mozilla.focus.ext.isVoiceViewEnabled
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.telemetry.DataUploadPreference
import org.mozilla.focus.telemetry.TelemetryWrapper

/** The settings for the app. */
class SettingsFragment : Fragment() {
    private val telemetryButton get() = requireView().findViewById<CheckBox>(R.id.telemetryButton)
    private val telemetryView get() = requireView().findViewById<View>(R.id.telemetryView)
    private val deleteButton get() = requireView().findViewById<View>(R.id.deleteButton)
    private val extensionsButton get() = requireView().findViewById<View>(R.id.extensionsButton)
    private val aboutButton get() = requireView().findViewById<View>(R.id.aboutButton)
    private val privacyNoticeButton get() = requireView().findViewById<View>(R.id.privacyNoticeButton)
    private val settingsWebView get() = requireView().findViewById<View>(R.id.settingsWebView) as IWebView
    private val lockIcon get() = requireView().findViewById<ImageView>(R.id.ic_lock)

    private val voiceViewStateChangeListener = AccessibilityManager.TouchExplorationStateChangeListener {
        updateForAccessibility()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        lockIcon.setImageResource(R.drawable.ic_lock)
        telemetryButton.isChecked = DataUploadPreference.isEnabled(requireContext())
        telemetryView.setOnClickListener { view ->
            val newTelemetryState = !DataUploadPreference.isEnabled(requireContext())
            DataUploadPreference.setIsEnabled(requireContext(), newTelemetryState)
            telemetryButton.isChecked = newTelemetryState
        }

        deleteButton.setOnClickListener { view ->
            val builder1 = AlertDialog.Builder(requireContext())
            builder1.setTitle(R.string.settings_cookies_dialog_title)
            builder1.setMessage(R.string.settings_cookies_dialog_content2)
            builder1.setCancelable(true)

            builder1.setPositiveButton(
                    getString(R.string.action_ok),
                    DialogInterface.OnClickListener { dialog, id ->
                        settingsWebView.cleanup()
                        SessionManager.getInstance().removeAllSessions()
                        dialog.cancel()
                        TelemetryWrapper.clearDataEvent()
                    })

            builder1.setNegativeButton(
                    getString(R.string.action_cancel),
                    DialogInterface.OnClickListener { dialog, id -> dialog.cancel() })

            val alert11 = builder1.create()
            alert11.show()
        }

        extensionsButton.setOnClickListener {
            startActivity(Intent(requireContext(), ExtensionsActivity::class.java))
        }

        aboutButton.setOnClickListener {
            startActivity(InfoActivity.getAboutIntent(requireContext()))
        }

        privacyNoticeButton.setOnClickListener {
            startActivity(InfoActivity.getPrivacyNoticeIntent(requireContext()))
        }
    }

    override fun onStart() {
        super.onStart()
        requireContext().getAccessibilityManager().addTouchExplorationStateChangeListener(voiceViewStateChangeListener)
        updateForAccessibility()
    }

    override fun onStop() {
        super.onStop()
        requireContext().getAccessibilityManager().removeTouchExplorationStateChangeListener(voiceViewStateChangeListener)
    }

    /**
     * Updates the views in this fragment based on Accessibility status.
     * See the comment at the declaration of these views in XML for more details.
     */
    private fun updateForAccessibility() {
        // When VoiceView is enabled, since the parent is initially focusable in the XML, focus is
        // given to the parent when the Settings opens. Here, after we set focusable to false, we
        // must also explicitly clear focus in order to give focus to the child.
        //
        // When we change VoiceView from enabled -> disabled and this setting is focused, focus is
        // cleared from this setting and nothing is selected. This is fine: the user can press
        // left-right to focus something else and it's an edge case that I don't think it is worth
        // adding code to fix.
        val shouldFocus = !requireContext().isVoiceViewEnabled()
        telemetryView.isFocusable = shouldFocus
        if (!shouldFocus) { telemetryView.clearFocus() }
    }

    companion object {
        const val FRAGMENT_TAG = "settings"

        @JvmStatic
        fun create() = SettingsFragment()
    }
}
