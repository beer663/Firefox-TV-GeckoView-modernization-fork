/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mozilla.tv.firefox.R;

/** Opt-in, local playback evidence for testing on the actual TV. */
public final class PlaybackDiagnosticsActivity extends AppCompatActivity {
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private Button recordingButton;
    private Button copyButton;
    private TextView reportView;
    private String codecReport;
    private boolean closed;

    @Override
    protected void onCreate(@Nullable Bundle savedState) {
        super.onCreate(savedState);
        setTitle(R.string.playback_diagnostics_title);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(24, 25, 32));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(28);
        content.setPadding(padding, padding, padding, padding);

        scroll.addView(content);
        setContentView(scroll);

        label(
                content,
                getString(R.string.playback_diagnostics_title),
                28);

        label(
                content,
                getString(R.string.playback_diagnostics_instructions),
                17);

        recordingButton = button(
                content,
                R.string.playback_record_start,
                () -> {
                    PlaybackDiagnostics.setRecording(
                            !PlaybackDiagnostics.isRecording());
                    render();
                });

        button(
                content,
                R.string.playback_refresh,
                this::render);

        copyButton = button(
                content,
                R.string.playback_copy,
                () -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(
                                    CLIPBOARD_SERVICE);

                    if (clipboard == null || codecReport == null) {
                        return;
                    }

                    try {
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                        "Firefox TV playback report",
                                        report()));

                        Toast.makeText(
                                this,
                                R.string.playback_copied,
                                Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException error) {
                        Toast.makeText(
                                this,
                                R.string.playback_copy_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });

        copyButton.setEnabled(false);

        button(
                content,
                R.string.playback_clear,
                () -> {
                    PlaybackDiagnostics.clear();
                    render();
                });

        reportView = label(content, "", 15);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setFocusable(true);

        render();
        recordingButton.requestFocus();

        worker.execute(() -> {
            String result = CodecReport.collect();

            runOnUiThread(() -> {
                if (closed || isFinishing() || isDestroyed()) {
                    return;
                }

                codecReport = result;
                copyButton.setEnabled(true);
                render();
            });
        });
    }

    private String report() {
        return (
                codecReport == null
                        ? getString(R.string.playback_querying)
                        : codecReport)
                + "\n\n"
                + PlaybackDiagnostics.report();
    }

    private void render() {
        recordingButton.setText(
                PlaybackDiagnostics.isRecording()
                        ? R.string.playback_record_stop
                        : R.string.playback_record_start);

        reportView.setText(report());
    }

    private TextView label(
            LinearLayout parent,
            String text,
            int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(size);
        label.setPadding(0, dp(10), 0, dp(10));
        parent.addView(label);
        return label;
    }

    private Button button(
            LinearLayout parent,
            int text,
            Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(56));
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());

        parent.addView(
                button,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        return button;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        closed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
