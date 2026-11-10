/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.extensions;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mozilla.focus.iwebview.WebViewProvider;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

public final class ExtensionPermissions
        implements WebExtensionController.PromptDelegate {

    private final Activity activity;
    private final List<Runnable> pending = new ArrayList<>();
    private WebExtensionController controller;
    private boolean attached;

    public ExtensionPermissions(Activity activity) {
        this.activity = activity;
    }

    private final WebExtensionController.AddonManagerDelegate events =
            new WebExtensionController.AddonManagerDelegate() {
                @Override
                public void onInstalled(
                        @NonNull WebExtension extension) {
                    if (!extension.isBuiltIn) {
                        notifyUser(
                                "Installed " + name(extension) + ".");
                    }
                }

                @Override
                public void onInstallationFailed(
                        WebExtension extension,
                        WebExtension.InstallException error) {
                    notifyUser(
                            "Extension installation failed: "
                                    + (
                                            error.getMessage() == null
                                                    ? "the extension may be incompatible or unavailable."
                                                    : error.getMessage()
                                    ));
                }
            };

    public void attach() {
        controller = WebViewProvider.getRuntime(activity)
                .getWebExtensionController();

        attached = true;
        controller.setPromptDelegate(this);
        controller.setAddonManagerDelegate(events);
    }

    public void detach() {
        attached = false;

        for (Runnable deny : new ArrayList<>(pending)) {
            deny.run();
        }

        pending.clear();

        if (controller != null) {
            if (controller.getPromptDelegate() == this) {
                controller.setPromptDelegate(null);
            }

            controller.setAddonManagerDelegate(null);
        }
    }

    @Override
    public GeckoResult<WebExtension.PermissionPromptResponse>
            onInstallPromptRequest(
                    @NonNull WebExtension extension,
                    @NonNull String[] permissions,
                    @NonNull String[] origins,
                    @NonNull String[] dataCollection) {

        GeckoResult<WebExtension.PermissionPromptResponse> result =
                new GeckoResult<>();

        prompt(
                extension,
                permissions,
                origins,
                dataCollection,
                allowed -> result.complete(
                        new WebExtension.PermissionPromptResponse(
                                allowed,
                                false,
                                false)));

        return result;
    }

    @Override
    public GeckoResult<AllowOrDeny> onUpdatePrompt(
            @NonNull WebExtension extension,
            @NonNull String[] permissions,
            @NonNull String[] origins,
            @NonNull String[] dataCollection) {
        return additional(
                extension,
                permissions,
                origins,
                dataCollection);
    }

    @Override
    public GeckoResult<AllowOrDeny> onOptionalPrompt(
            @NonNull WebExtension extension,
            @NonNull String[] permissions,
            @NonNull String[] origins,
            @NonNull String[] dataCollection) {
        return additional(
                extension,
                permissions,
                origins,
                dataCollection);
    }

    private GeckoResult<AllowOrDeny> additional(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollection) {

        GeckoResult<AllowOrDeny> result = new GeckoResult<>();

        prompt(
                extension,
                permissions,
                origins,
                dataCollection,
                allowed -> result.complete(
                        allowed
                                ? AllowOrDeny.ALLOW
                                : AllowOrDeny.DENY));

        return result;
    }

    private interface Answer {
        void complete(boolean allowed);
    }

    private void prompt(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollection,
            Answer answer) {

        activity.runOnUiThread(() -> {
            if (!attached
                    || activity.isFinishing()
                    || activity.isDestroyed()) {
                answer.complete(false);
                return;
            }

            StringBuilder text = new StringBuilder(
                    "Allow the following access for ")
                    .append(name(extension))
                    .append("?\n");

            append(text, "Permissions", permissions);
            append(text, "Websites", origins);
            append(text, "Data collection", dataCollection);

            AtomicBoolean answered = new AtomicBoolean();

            Answer once = allowed -> {
                if (answered.compareAndSet(false, true)) {
                    answer.complete(allowed);
                }
            };

            AlertDialog dialog =
                    new AlertDialog.Builder(activity)
                            .setTitle(
                                    "Extension permission request")
                            .setMessage(text.toString())
                            .setNegativeButton(
                                    "Deny",
                                    (d, w) -> once.complete(false))
                            .setPositiveButton(
                                    "Allow",
                                    (d, w) -> once.complete(true))
                            .create();

            Runnable deny = () -> {
                once.complete(false);
                dialog.dismiss();
            };

            pending.add(deny);

            dialog.setOnDismissListener(d -> {
                once.complete(false);
                pending.remove(deny);
            });

            dialog.show();

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .requestFocus();
        });
    }

    private void notifyUser(String text) {
        activity.runOnUiThread(() -> {
            if (attached && !activity.isFinishing()) {
                Toast.makeText(
                        activity,
                        text,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String name(WebExtension extension) {
        return extension != null
                && extension.metaData != null
                && extension.metaData.name != null
                ? extension.metaData.name
                : "this extension";
    }

    private static void append(
            StringBuilder text,
            String heading,
            String[] entries) {

        if (entries.length == 0) return;

        text.append("\n").append(heading).append(":");

        for (String entry : entries) {
            text.append("\n• ").append(entry);
        }
    }
}
