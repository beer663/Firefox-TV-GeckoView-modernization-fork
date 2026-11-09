/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class StartupReportActivity extends Activity {
    private TextView report;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 25, 32));

        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);

        root.addView(text("Browser startup report", 26));

        root.addView(text(
                "Choose Open browser. If it closes, reopen the app "
                        + "to read the saved error here. "
                        + "Take a photo of the error text.",
                17));

        Button open = new Button(this);
        open.setText("Open browser");
        open.setAllCaps(false);

        open.setOnClickListener(view -> {
            Intent intent = new Intent();

            intent.setClassName(
                    getPackageName(),
                    "org.mozilla.focus.MainActivity");

            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            startActivity(intent);
        });

        root.addView(open);

        Button clear = new Button(this);
        clear.setText("Clear saved report");
        clear.setAllCaps(false);

        clear.setOnClickListener(view -> {
            StartupDiagnosticsApplication.reportFile(this).delete();
            refresh();
        });

        root.addView(clear);

        ScrollView scroll = new ScrollView(this);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(-1, 0, 1));

        report = text("", 15);
        report.setTypeface(android.graphics.Typeface.MONOSPACE);

        scroll.addView(report);
        refresh();
        open.requestFocus();
    }

    private void refresh() {
        String result;

        try {
            result = new String(
                    Files.readAllBytes(
                            StartupDiagnosticsApplication
                                    .reportFile(this)
                                    .toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            result =
                    "No Java crash has been saved yet.\n"
                            + "Open the browser, let it fail, "
                            + "then reopen this app.\n";
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager manager =
                        getSystemService(ActivityManager.class);

                if (manager != null) {
                    for (
                            ApplicationExitInfo exit
                            : manager.getHistoricalProcessExitReasons(
                                    getPackageName(),
                                    0,
                                    8)
                    ) {
                        if (
                                getPackageName().equals(
                                        exit.getProcessName())
                        ) {
                            result +=
                                    "\n\nLast Android exit record "
                                            + "for the browser process:\nReason "
                                            + exit.getReason()
                                            + " (Java crash=4, native crash=5, ANR=6)\n"
                                            + String.valueOf(
                                                    exit.getDescription());
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                result += "\nAndroid exit history unavailable.\n";
            }
        }

        report.setText(result);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
