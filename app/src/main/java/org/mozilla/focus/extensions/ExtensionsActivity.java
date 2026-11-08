/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.extensions;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import org.mozilla.focus.MainActivity;
import org.mozilla.focus.iwebview.WebViewProvider;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;

/**
 * User-driven add-on manager using the browser's shared GeckoRuntime.
 * Nothing is installed automatically.
 */
public final class ExtensionsActivity extends AppCompatActivity {
    private static final String UBLOCK_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final String AMO_URL =
            "https://addons.mozilla.org/en-US/firefox/";

    private LinearLayout root;
    private LinearLayout installedList;
    private TextView status;
    private WebExtensionController controller;
    private boolean busy;
    private final List<Runnable> pendingPermissionDenials = new ArrayList<>();

    private boolean isScreenClosed() {
        return isFinishing() || isDestroyed();
    }

    // Gecko operations may finish after the user leaves this Activity.
    private void onScreen(Runnable update) {
        runOnUiThread(() -> {
            if (!isScreenClosed()) update.run();
        });
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(24, 25, 32));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(30), dp(24), dp(30), dp(40));
        scroll.addView(root);
        setContentView(scroll);

        label(root, "Extensions", 30, true);
        label(root, "Choose an extension to install. Nothing is preinstalled.", 17, false);
        status = label(root, "Checking installed extensions…", 16, false);

        action(root, "Browse Firefox Add-ons", this::browseAddons);
        action(root, "Install uBlock Origin", () -> confirmInstall(UBLOCK_XPI));
        action(root, "Install from signed XPI link", this::inputXpiLink);

        label(root,
                "Browsing the add-ons website does not install an add-on by itself. "
                        + "Return here to install a signed XPI.",
                14, false);

        label(root, "Installed extensions", 23, true);
        installedList = new LinearLayout(this);
        installedList.setOrientation(LinearLayout.VERTICAL);
        root.addView(installedList);

        try {
            controller = WebViewProvider.getRuntime(this).getWebExtensionController();
            controller.setPromptDelegate(promptDelegate);
            refresh("Ready. Choose an extension to install or manage.");
        } catch (RuntimeException | LinkageError e) {
            status.setText("Extensions unavailable: " + message(e));
        }
    }

    @Override
    protected void onDestroy() {
        // Complete GeckoResults before dismissing dialogs so installs never wait forever.
        for (Runnable deny : new ArrayList<>(pendingPermissionDenials)) {
            deny.run();
        }
        pendingPermissionDenials.clear();

        if (controller != null && controller.getPromptDelegate() == promptDelegate) {
            controller.setPromptDelegate(null);
        }

        super.onDestroy();
    }

    private final WebExtensionController.PromptDelegate promptDelegate =
            new WebExtensionController.PromptDelegate() {
        @Nullable
        @Override
        public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                @NonNull WebExtension extension,
                @NonNull String[] permissions,
                @NonNull String[] origins,
                @NonNull String[] dataCollectionPermissions) {
            GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();

            runOnUiThread(() -> permissionPrompt(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions,
                    granted -> result.complete(
                            new WebExtension.PermissionPromptResponse(
                                    granted, false, false))));

            return result;
        }

        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onUpdatePrompt(
                @NonNull WebExtension extension,
                @NonNull String[] newPermissions,
                @NonNull String[] newOrigins,
                @NonNull String[] newDataCollectionPermissions) {
            return additionalPermissions(
                    extension,
                    newPermissions,
                    newOrigins,
                    newDataCollectionPermissions);
        }

        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onOptionalPrompt(
                @NonNull WebExtension extension,
                @NonNull String[] permissions,
                @NonNull String[] origins,
                @NonNull String[] dataCollectionPermissions) {
            return additionalPermissions(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions);
        }
    };

    private interface PermissionAnswer {
        void accept(boolean granted);
    }

    private GeckoResult<AllowOrDeny> additionalPermissions(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollectionPermissions) {
        GeckoResult<AllowOrDeny> result = new GeckoResult<>();

        runOnUiThread(() -> permissionPrompt(
                extension,
                permissions,
                origins,
                dataCollectionPermissions,
                granted -> result.complete(
                        granted ? AllowOrDeny.ALLOW : AllowOrDeny.DENY)));

        return result;
    }

    private void permissionPrompt(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollection,
            PermissionAnswer answer) {
        if (isScreenClosed()) {
            answer.accept(false);
            return;
        }

        // Never grant permissions or private browsing access without a user choice.
        String name = extension.metaData != null && extension.metaData.name != null
                ? extension.metaData.name
                : "this extension";

        StringBuilder details = new StringBuilder("Allow the following access for ")
                .append(name)
                .append("?\n");

        appendDetails(details, "Permissions", permissions);
        appendDetails(details, "Websites", origins);
        appendDetails(details, "Data collection", dataCollection);

        final boolean[] answered = {false};
        final AlertDialog[] dialog = new AlertDialog[1];

        PermissionAnswer once = granted -> {
            if (answered[0]) return;
            answered[0] = true;
            answer.accept(granted);
        };

        Runnable deny = () -> {
            once.accept(false);
            if (dialog[0] != null) dialog[0].dismiss();
        };

        pendingPermissionDenials.add(deny);

        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Extension permission request")
                .setMessage(details.toString())
                .setNegativeButton("Deny", (d, w) -> once.accept(false))
                .setPositiveButton("Allow", (d, w) -> once.accept(true))
                .create();

        dialog[0].setOnDismissListener(d -> {
            // Back, dismissal, and Activity destruction all deny.
            once.accept(false);
            pendingPermissionDenials.remove(deny);
        });

        dialog[0].show();
        dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private static void appendDetails(
            StringBuilder out,
            String title,
            String[] entries) {
        if (entries == null || entries.length == 0) return;

        out.append("\n").append(title).append(":");
        for (String item : entries) {
            out.append("\n• ").append(item);
        }
    }

    private void browseAddons() {
        // Open AMO in this browser. Installation is initiated from this screen.
        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(AMO_URL),
                this,
                MainActivity.class);

        startActivity(intent);
        finish();
    }

    private void inputXpiLink() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/extension.xpi");
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Install a signed extension")
                .setMessage("Paste a direct HTTPS link to a Mozilla-signed XPI package.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, which) -> {
                    String url = input.getText().toString().trim();
                    Uri parsed = Uri.parse(url);

                    if (!"https".equalsIgnoreCase(parsed.getScheme())
                            || parsed.getHost() == null) {
                        status.setText("Only HTTPS extension links are accepted.");
                        return;
                    }

                    confirmInstall(url);
                })
                .show();
    }

    private void confirmInstall(String url) {
        if (controller == null || busy || isScreenClosed()) return;

        new AlertDialog.Builder(this)
                .setTitle("Install extension?")
                .setMessage(
                        "GeckoView will check the Mozilla signature and request permissions "
                                + "before installing.\n\n" + url)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, which) -> install(url))
                .show();
    }

    private void install(String url) {
        if (controller == null || busy || isScreenClosed()) return;

        busy = true;
        status.setText("Downloading and validating extension…");

        try {
            controller.install(url).accept(
                    extension -> onScreen(() -> {
                        busy = false;
                        status.setText("Installed " + displayName(extension) + ".");
                        refresh();
                    }),
                    error -> onScreen(() -> {
                        busy = false;
                        status.setText("Installation failed: " + message(error));
                    }));
        } catch (RuntimeException | LinkageError e) {
            busy = false;
            status.setText("Installation unavailable: " + message(e));
        }
    }

    private void refresh() {
        refresh(null);
    }

    private void refresh(@Nullable String readyMessage) {
        if (controller == null || isScreenClosed()) return;

        controller.list().accept(
                list -> onScreen(() -> {
                    displayExtensions(list);
                    if (readyMessage != null && !busy) {
                        status.setText(readyMessage);
                    }
                }),
                error -> onScreen(() ->
                        status.setText("Cannot list extensions: " + message(error))));
    }

    private void displayExtensions(List<WebExtension> installed) {
        installedList.removeAllViews();

        if (installed == null || installed.isEmpty()) {
            label(installedList, "No extensions installed.", 16, false);
            return;
        }

        for (WebExtension extension : installed) {
            String name = displayName(extension);
            boolean enabled = extension.metaData != null && extension.metaData.enabled;

            label(
                    installedList,
                    name + (enabled ? " — Enabled" : " — Disabled"),
                    20,
                    true);

            action(
                    installedList,
                    enabled ? "Disable " + name : "Enable " + name,
                    () -> setEnabled(extension, !enabled));

            action(
                    installedList,
                    "Uninstall " + name,
                    () -> new AlertDialog.Builder(this)
                            .setTitle("Uninstall " + name + "?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton(
                                    "Uninstall",
                                    (d, w) -> uninstall(extension))
                            .show());
        }
    }

    private void setEnabled(WebExtension extension, boolean enabled) {
        if (controller == null || busy || isScreenClosed()) return;

        busy = true;
        status.setText(
                (enabled ? "Enabling " : "Disabling ")
                        + displayName(extension) + "…");

        try {
            GeckoResult<WebExtension> change = enabled
                    ? controller.enable(
                            extension,
                            WebExtensionController.EnableSource.USER)
                    : controller.disable(
                            extension,
                            WebExtensionController.EnableSource.USER);

            change.accept(
                    updated -> onScreen(() -> {
                        busy = false;
                        status.setText(displayName(updated) + " updated.");
                        refresh();
                    }),
                    error -> onScreen(() -> {
                        busy = false;
                        status.setText("Cannot change extension: " + message(error));
                    }));
        } catch (RuntimeException | LinkageError e) {
            busy = false;
            status.setText("Cannot change extension: " + message(e));
        }
    }

    private void uninstall(WebExtension extension) {
        if (controller == null || busy || isScreenClosed()) return;

        busy = true;
        status.setText("Removing " + displayName(extension) + "…");

        try {
            controller.uninstall(extension).accept(
                    ignored -> onScreen(() -> {
                        busy = false;
                        status.setText("Extension removed.");
                        refresh();
                    }),
                    error -> onScreen(() -> {
                        busy = false;
                        status.setText("Uninstall failed: " + message(error));
                    }));
        } catch (RuntimeException | LinkageError e) {
            busy = false;
            status.setText("Uninstall failed: " + message(e));
        }
    }

    private static String displayName(WebExtension extension) {
        if (extension == null) return "extension";

        if (extension.metaData != null && extension.metaData.name != null) {
            return extension.metaData.name;
        }

        return extension.id;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(
            LinearLayout parent,
            String value,
            int sizeSp,
            boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);

        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        view.setPadding(0, dp(10), 0, dp(10));
        parent.addView(view);
        return view;
    }

    private void action(
            LinearLayout parent,
            String title,
            Runnable click) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setFocusable(true);
        button.setClickable(true);
        button.setMinHeight(dp(58));
        button.setOnClickListener(v -> click.run());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64));

        parent.addView(button, params);
    }
}
