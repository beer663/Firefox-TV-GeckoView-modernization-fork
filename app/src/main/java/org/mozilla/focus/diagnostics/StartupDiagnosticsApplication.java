/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mozilla.focus.FocusApplication;
import org.mozilla.tv.firefox.BuildConfig;

public final class StartupDiagnosticsApplication extends FocusApplication {
    private boolean reportProcess;

    private final AtomicBoolean savingCrash =
            new AtomicBoolean();

    static File reportFile(Context context) {
        return new File(
                context.getFilesDir(),
                "startup-java-crash.txt");
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        reportProcess =
                processName(base).endsWith(":startup_report");

        if (reportProcess) {
            return;
        }

        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (savingCrash.compareAndSet(false, true)) {
                try {
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));

                    String text =
                            "App "
                                    + BuildConfig.VERSION_NAME
                                    + " ("
                                    + BuildConfig.VERSION_CODE
                                    + ")\nDevice: "
                                    + Build.MANUFACTURER
                                    + " "
                                    + Build.MODEL
                                    + "; Android "
                                    + Build.VERSION.RELEASE
                                    + " / API "
                                    + Build.VERSION.SDK_INT
                                    + "\nThread: "
                                    + thread.getName()
                                    + "\n\n"
                                    + trace;

                    if (text.length() > 24000) {
                        text = text.substring(0, 14000)
                                + "\n…middle of trace omitted…\n"
                                + text.substring(text.length() - 10000);
                    }

                    try (
                            FileOutputStream out =
                                    new FileOutputStream(reportFile(base))
                    ) {
                        out.write(
                                text.getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                } catch (Throwable ignored) {
                    // Continue normal crash handling if saving fails.
                }
            }

            try {
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            } finally {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    @Override
    public void onCreate() {
        if (reportProcess) {
            // Keep the report process independent of browser startup.
            return;
        }

        super.onCreate();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        if (!reportProcess) {
            super.onConfigurationChanged(configuration);
        }
    }

    private static String processName(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            String name = Application.getProcessName();
            return name == null ? context.getPackageName() : name;
        }

        ActivityManager manager =
                (ActivityManager) context.getSystemService(
                        ACTIVITY_SERVICE);

        if (
                manager != null
                        && manager.getRunningAppProcesses() != null
        ) {
            for (
                    ActivityManager.RunningAppProcessInfo info
                    : manager.getRunningAppProcesses()
            ) {
                if (info.pid == Process.myPid()) {
                    return info.processName;
                }
            }
        }

        try {
            byte[] bytes = Files.readAllBytes(
                    new File("/proc/self/cmdline").toPath());

            int length = 0;
            while (length < bytes.length && bytes[length] != 0) {
                length++;
            }

            return new String(
                    bytes,
                    0,
                    length,
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return context.getPackageName();
        }
    }
}
