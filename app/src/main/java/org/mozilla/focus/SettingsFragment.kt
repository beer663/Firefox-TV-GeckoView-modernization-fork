/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.mozilla.tv.firefox.R
import org.mozilla.focus.browser.InfoActivity
import org.mozilla.focus.ext.getAccessibilityManager
import org.mozilla.focus.ext.isVoiceViewEnabled
import org.mozilla.focus.extensions.ExtensionsActivity
import org.mozilla.focus.iwebview.WebViewProvider
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.telemetry.DataUploadPreference
import org.mozilla.focus.telemetry.TelemetryWrapper

class SettingsFragment : Fragment() {
    private val telemetryButton
        get() = requireView().findViewById<CheckBox>(R.id.telemetryButton)

    private val telemetryView
        get() = requireView().findViewById<View>(R.id.telemetryView)

    private val deleteButton
        get() = requireView().findViewById<View>(R.id.deleteButton)

    private val extensionsButton
        get() = requireView().findViewById<View>(R.id.extensionsButton)

    private val aboutButton
        get() = requireView().findViewById<View>(R.id.aboutButton)

    private val privacyNoticeButton
        get() = requireView().findViewById<View>(R.id.privacyNoticeButton)

    private val lockIcon
        get() = requireView().findViewById<ImageView>(R.id.ic_lock)

    private val voiceViewStateChangeListener =
        AccessibilityManager.TouchExplorationStateChangeListener {
            updateForAccessibility()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lockIcon.setImageResource(R.drawable.ic_lock)
        telemetryButton.isChecked = DataUploadPreference.isEnabled(requireContext())

        telemetryView.setOnClickListener {
            val enabled = !DataUploadPreference.isEnabled(requireContext())
            DataUploadPreference.setIsEnabled(requireContext(), enabled)
            telemetryButton.isChecked = enabled
        }

        deleteButton.setOnClickListener { button ->
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(R.string.settings_cookies_dialog_title)
            builder.setMessage(R.string.settings_cookies_dialog_content2)
            builder.setCancelable(true)

            builder.setPositiveButton(
                getString(R.string.action_ok),
                DialogInterface.OnClickListener { dialog, _ ->
                    val appContext = requireContext().applicationContext

                    button.isEnabled = false
                    dialog.dismiss()
                    SessionManager.getInstance().removeAllSessions()

                    WebViewProvider.clearBrowsingData(appContext).accept(
                        {
                            Handler(Looper.getMainLooper()).post {
                                button.isEnabled = true
                                Toast.makeText(
                                    appContext,
                                    "Browsing data cleared",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            TelemetryWrapper.clearDataEvent()
                        },
                        { error ->
                            Handler(Looper.getMainLooper()).post {
                                button.isEnabled = true
                                Toast.makeText(
                                    appContext,
                                    "Could not clear browsing data: " +
                                        (error?.message ?: "Unknown error"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            )

            builder.setNegativeButton(
                getString(R.string.action_cancel),
                DialogInterface.OnClickListener { dialog, _ ->
                    dialog.cancel()
                }
            )

            builder.create().show()
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

        requireContext().getAccessibilityManager()
            .addTouchExplorationStateChangeListener(voiceViewStateChangeListener)

        updateForAccessibility()
    }

    override fun onStop() {
        super.onStop()

        requireContext().getAccessibilityManager()
            .removeTouchExplorationStateChangeListener(voiceViewStateChangeListener)
    }

    private fun updateForAccessibility() {
        val shouldFocus = !requireContext().isVoiceViewEnabled()
        telemetryView.isFocusable = shouldFocus

        if (!shouldFocus) {
            telemetryView.clearFocus()
        }
    }

    companion object {
        const val FRAGMENT_TAG = "settings"

        @JvmStatic
        fun create() = SettingsFragment()
    }
}
