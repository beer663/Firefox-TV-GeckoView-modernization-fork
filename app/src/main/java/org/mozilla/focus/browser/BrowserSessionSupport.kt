/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.focus.extensions.ExtensionsActivity
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.library.DownloadsStore
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import org.mozilla.geckoview.WebResponse
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Website prompts and authenticated downloads; extension prompts stay separate. */
class BrowserSessionSupport(
    private val activity: AppCompatActivity
) : GeckoSession.PromptDelegate {

    private val views = WeakHashMap<GeckoSession, WeakReference<View>>()
    private val pending = mutableSetOf<Ticket>()
    private val downloadDialogs = mutableMapOf<AlertDialog, () -> Unit>()
    private var fileTicket: Ticket? = null
    private var closed = false

    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ticket = fileTicket
        fileTicket = null

        if (ticket != null && !ticket.done) {
            val uris = mutableListOf<Uri>()

            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uris.add(it) }
                result.data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) {
                        uris.add(clip.getItemAt(index).uri)
                    }
                }
            }

            val files = uris.distinct().filter { it.scheme == "content" }
            val prompt = ticket.prompt as FilePrompt

            if (files.isEmpty()) {
                ticket.cancel()
            } else {
                ticket.finish {
                    prompt.confirm(
                        activity,
                        if (prompt.type == FilePrompt.Type.MULTIPLE) {
                            files.toTypedArray()
                        } else {
                            arrayOf(files.first())
                        }
                    )
                }
            }
        }
    }

    fun attach(session: GeckoSession, view: View) {
        if (views.containsKey(session)) return
        views[session] = WeakReference(view)
        session.promptDelegate = this

        val original = session.contentDelegate
            ?: object : GeckoSession.ContentDelegate {}

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            // Preserve the adapter's existing callbacks explicitly.
            override fun onTitleChange(session: GeckoSession, title: String?) {
                original.onTitleChange(session, title)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                original.onFullScreen(session, fullScreen)
            }

            override fun onExternalResponse(
                session: GeckoSession,
                response: WebResponse
            ) {
                if (ExtensionsActivity.isExtensionResponse(
                        response.uri,
                        header(response, "content-type")
                    )
                ) {
                    try {
                        original.onExternalResponse(session, response)
                    } finally {
                        DownloadsStore.closeBody(response)
                    }
                } else {
                    confirmDownload(response)
                }
            }
        }
    }

    private fun title(session: GeckoSession): String {
        val url = (views[session]?.get() as? IWebView)?.getUrl()
        val host = url?.let { Uri.parse(it).host }
        return if (host.isNullOrBlank()) "Website" else "Website — $host"
    }

    private inner class Ticket(
        val prompt: BasePrompt,
        val cancelAction: () -> PromptResponse = { prompt.dismiss() }
    ) {
        val result = GeckoResult<PromptResponse>()
        var dialog: AlertDialog? = null
        var done = false

        init {
            pending.add(this)
            prompt.setDelegate(object : PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) {
                    cancel()
                }

                override fun onPromptUpdate(prompt: BasePrompt) {
                    cancel()
                }
            })
        }

        fun finish(answer: () -> PromptResponse) {
            if (done) return
            done = true
            pending.remove(this)
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()

            try {
                result.complete(if (prompt.isComplete) null else answer())
            } catch (error: RuntimeException) {
                result.completeExceptionally(error)
            }
        }

        fun cancel() = finish(cancelAction)

        fun show(builder: AlertDialog.Builder): GeckoResult<PromptResponse> {
            if (closed || activity.isFinishing || activity.isDestroyed) {
                cancel()
                return result
            }

            dialog = builder.create().also {
                it.setOnDismissListener { cancel() }
                it.show()
                it.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
            }

            return result
        }
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: AlertPrompt
    ): GeckoResult<PromptResponse> {
        val ticket = Ticket(prompt)
        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle(title(session))
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ticket.cancel()
                }
        )
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: ButtonPrompt
    ): GeckoResult<PromptResponse> {
        val ticket = Ticket(prompt)
        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle(title(session))
                .setMessage(prompt.message)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    ticket.finish { prompt.confirm(ButtonPrompt.Type.NEGATIVE) }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ticket.finish { prompt.confirm(ButtonPrompt.Type.POSITIVE) }
                }
        )
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: TextPrompt
    ): GeckoResult<PromptResponse> {
        val input = EditText(activity).apply {
            setText(prompt.defaultValue)
            setSingleLine(true)
        }
        val ticket = Ticket(prompt)

        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle(title(session))
                .setMessage(prompt.message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    ticket.cancel()
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ticket.finish { prompt.confirm(input.text.toString()) }
                }
        )
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: BeforeUnloadPrompt
    ): GeckoResult<PromptResponse> {
        val ticket = Ticket(prompt) { prompt.confirm(AllowOrDeny.DENY) }

        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle(title(session))
                .setMessage("Leave this page? Unsaved changes may be lost.")
                .setNegativeButton("Stay") { _, _ -> ticket.cancel() }
                .setPositiveButton("Leave") { _, _ ->
                    ticket.finish { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        )
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: RepostConfirmPrompt
    ): GeckoResult<PromptResponse> {
        val ticket = Ticket(prompt) { prompt.confirm(AllowOrDeny.DENY) }

        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle(title(session))
                .setMessage("Resend this form? This may repeat a previous action.")
                .setNegativeButton(android.R.string.cancel) { _, _ -> ticket.cancel() }
                .setPositiveButton("Resend") { _, _ ->
                    ticket.finish { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        )
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: AuthPrompt
    ): GeckoResult<PromptResponse> {
        val server = Uri.parse(prompt.authOptions.uri)
        val serverName = server.host ?: "Website"
        val onlyPassword =
            prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0

        val username = EditText(activity).apply {
            hint = "Username"
            setSingleLine(true)
            setText(prompt.authOptions.username)
        }
        val password = EditText(activity).apply {
            hint = "Password"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (!onlyPassword) addView(username)
            addView(password)
        }
        val ticket = Ticket(prompt)

        return ticket.show(
            AlertDialog.Builder(activity)
                .setTitle("Sign in — $serverName")
                .setMessage("This server requires a username and password.")
                .setView(form)
                .setNegativeButton(android.R.string.cancel) { _, _ -> ticket.cancel() }
                .setPositiveButton("Sign in") { _, _ ->
                    ticket.finish {
                        if (onlyPassword) {
                            prompt.confirm(password.text.toString())
                        } else {
                            prompt.confirm(
                                username.text.toString(),
                                password.text.toString()
                            )
                        }
                    }
                }
        )
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: ChoicePrompt
    ): GeckoResult<PromptResponse> {
        val choices = mutableListOf<ChoicePrompt.Choice>()
        val labels = mutableListOf<String>()

        fun flatten(items: Array<ChoicePrompt.Choice>, prefix: String = "") {
            items.forEach { item ->
                val children = item.items
                if (!item.disabled && !children.isNullOrEmpty()) {
                    flatten(children, "$prefix${item.label}: ")
                } else if (!item.separator && !item.disabled) {
                    choices.add(item)
                    labels.add(prefix + item.label)
                }
            }
        }

        flatten(prompt.choices)
        val ticket = Ticket(prompt)
        val builder = AlertDialog.Builder(activity)
            .setTitle(title(session))
            .setNegativeButton(android.R.string.cancel) { _, _ -> ticket.cancel() }

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(choices.size) { choices[it].selected }
            builder.setMultiChoiceItems(
                labels.toTypedArray(),
                checked
            ) { _, index, value ->
                checked[index] = value
            }.setPositiveButton(android.R.string.ok) { _, _ ->
                ticket.finish {
                    prompt.confirm(
                        choices.filterIndexed { index, _ -> checked[index] }.toTypedArray()
                    )
                }
            }
        } else {
            builder.setSingleChoiceItems(
                labels.toTypedArray(),
                choices.indexOfFirst { it.selected }
            ) { _, index ->
                ticket.finish { prompt.confirm(choices[index]) }
            }
        }

        return ticket.show(builder)
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt
    ): GeckoResult<PromptResponse> {
        val ticket = Ticket(prompt)

        if (fileTicket != null || closed || prompt.type == FilePrompt.Type.FOLDER) {
            ticket.cancel()
            return ticket.result
        }

        val types = prompt.mimeTypes.orEmpty().mapNotNull { value ->
            if (value.startsWith(".")) {
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(value.drop(1).lowercase())
            } else {
                value.takeIf { it.contains('/') }
            }
        }.distinct()

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (types.size == 1) types.first() else "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                prompt.type == FilePrompt.Type.MULTIPLE
            )

        if (types.size > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        }

        fileTicket = ticket
        try {
            picker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            fileTicket = null
            ticket.cancel()
            Toast.makeText(
                activity,
                "No file picker is installed on this TV.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            fileTicket = null
            ticket.cancel()
            Toast.makeText(
                activity,
                "Android blocked file selection.",
                Toast.LENGTH_LONG
            ).show()
        }

        return ticket.result
    }

    private fun confirmDownload(response: WebResponse) {
        if (closed || activity.isFinishing ||
            response.body == null || response.statusCode !in 200..299
        ) {
            DownloadsStore.closeBody(response)
            return
        }

        var transferred = false

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Download file?")
            .setMessage(
                DownloadsStore.filename(response) + "\n" +
                    (Uri.parse(response.uri).host ?: "Website")
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Download") { _, _ ->
                try {
                    DownloadsStore.saveResponse(activity.applicationContext, response)
                    transferred = true
                    Toast.makeText(
                        activity,
                        "Download started. Open Downloads for progress.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: RuntimeException) {
                    Toast.makeText(
                        activity,
                        "Download could not start.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.create()

        val close = {
            if (!transferred) DownloadsStore.closeBody(response)
            Unit
        }

        downloadDialogs[dialog] = close
        dialog.setOnDismissListener {
            downloadDialogs.remove(dialog)
            close()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    fun close() {
        closed = true
        pending.toList().forEach { it.cancel() }
        fileTicket = null
        downloadDialogs.keys.toList().forEach { it.dismiss() }

        views.keys.toList().forEach {
            if (it.promptDelegate === this) it.promptDelegate = null
        }
        views.clear()
    }

    private fun header(response: WebResponse, key: String) =
        response.headers.entries.firstOrNull { it.key.equals(key, true) }?.value
}
