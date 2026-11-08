/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** UI-thread controls shared by browser views and the diagnostics screen. */
public final class PlaybackDiagnostics {
    private static final PlaybackSampleStore STORE =
            new PlaybackSampleStore(120);

    private static final Set<Runnable> LISTENERS =
            Collections.newSetFromMap(
                    new WeakHashMap<Runnable, Boolean>());

    private PlaybackDiagnostics() {}

    public static boolean isRecording() {
        return STORE.isRecording();
    }

    public static long epoch() {
        return STORE.getEpoch();
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    public static void setRecording(boolean enabled) {
        if (enabled) {
            STORE.start();
        } else {
            STORE.stop();
        }
        notifyListeners();
    }

    public static void clear() {
        STORE.clear();
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(LISTENERS)) {
            listener.run();
        }
    }

    public static void record(long epoch, JSONObject response) {
        StringBuilder text = new StringBuilder(
                DateFormat.getTimeInstance().format(new Date()));

        JSONArray frames = response.optJSONArray("frames");

        if (frames == null) {
            STORE.record(
                    epoch,
                    text.append(" — Playback sample unavailable").toString());
            return;
        }

        int videos = 0;

        for (
                int frame = 0;
                frame < Math.min(frames.length(), 16) && videos < 8;
                frame++) {
            JSONArray media = frames.optJSONArray(frame);

            if (media == null) {
                continue;
            }

            for (
                    int index = 0;
                    index < Math.min(media.length(), 8) && videos < 8;
                    index++) {
                JSONObject video = media.optJSONObject(index);

                if (video == null) {
                    continue;
                }

                videos++;

                text.append("\n  Frame ")
                        .append(frame)
                        .append(" video ")
                        .append(index)
                        .append(": ")
                        .append(video.optInt("width"))
                        .append('x')
                        .append(video.optInt("height"))
                        .append("; time=")
                        .append(number(video, "time"))
                        .append("s; buffered ahead=")
                        .append(number(video, "bufferedAhead"))
                        .append("s; dropped=")
                        .append(number(video, "dropped"))
                        .append('/')
                        .append(number(video, "total"))
                        .append("; ready=")
                        .append(video.optInt("ready"))
                        .append("; network=")
                        .append(video.optInt("network"))
                        .append("; paused=")
                        .append(video.optBoolean("paused"))
                        .append("; muted=")
                        .append(video.optBoolean("muted"))
                        .append("; volume=")
                        .append(number(video, "volume"))
                        .append("; speed=")
                        .append(number(video, "speed"))
                        .append("; seeking=")
                        .append(video.optBoolean("seeking"))
                        .append("; ended=")
                        .append(video.optBoolean("ended"))
                        .append("; fullscreen=")
                        .append(video.optBoolean("fullscreen"))
                        .append("; error=")
                        .append(video.optInt("error"));
            }
        }

        if (videos == 0) {
            text.append(" — No video elements found in accessible frames");
        }

        STORE.record(epoch, text.toString());
    }

    public static void recordUnavailable(long epoch) {
        STORE.record(
                epoch,
                DateFormat.getTimeInstance().format(new Date())
                        + " — Playback sample timed out");
    }

    private static String number(JSONObject object, String key) {
        double value = object.optDouble(key, Double.NaN);

        return Double.isFinite(value)
                ? String.format(Locale.US, "%.2f", value)
                : "unknown";
    }

    public static String report() {
        String[] samples = STORE.snapshot();

        StringBuilder report = new StringBuilder("Playback recording: ")
                .append(isRecording() ? "ON" : "OFF")
                .append("\nSamples retained: ")
                .append(samples.length)
                .append(" / 120")
                .append(
                        "\nSamples are taken about every 2 seconds "
                                + "while the browser is in the foreground.")
                .append(
                        "\nDropped/total frames are cumulative counters "
                                + "for each video, not a measured frame rate.")
                .append(
                        "\nready: 0=no data, 1=metadata, 2=current frame, "
                                + "3=future data, 4=enough data.")
                .append(
                        "\nnetwork: 0=empty, 1=idle, 2=loading, 3=no source.")
                .append(
                        "\nNo page addresses or media URLs are recorded. "
                                + "Samples clear when the app process ends.\n");

        if (samples.length == 0) {
            report.append(
                    "\nNo samples. Start recording, return to a video, "
                            + "then reopen this screen.\n");
        }

        for (String sample : samples) {
            report.append('\n').append(sample).append('\n');
        }

        return report.toString();
    }
}
