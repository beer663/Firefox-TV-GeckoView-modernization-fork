/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import java.util.ArrayDeque;

/** Bounded, process-only recording. Epochs reject old responses. */
public final class PlaybackSampleStore {
    private final int capacity;
    private final ArrayDeque<String> samples = new ArrayDeque<>();
    private boolean recording;
    private long epoch;

    public PlaybackSampleStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void start() {
        samples.clear();
        recording = true;
        epoch++;
    }

    public synchronized void stop() {
        recording = false;
        epoch++;
    }

    public synchronized void clear() {
        samples.clear();
        epoch++;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized long getEpoch() {
        return epoch;
    }

    public synchronized void record(long requestEpoch, String sample) {
        if (!recording || requestEpoch != epoch || sample == null) {
            return;
        }

        if (samples.size() == capacity) {
            samples.removeFirst();
        }

        samples.addLast(
                sample.length() > 2048
                        ? sample.substring(0, 2048) + "…"
                        : sample);
    }

    public synchronized String[] snapshot() {
        return samples.toArray(new String[0]);
    }
}
