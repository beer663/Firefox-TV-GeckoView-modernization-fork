/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.browser.cursor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Keeps repeated presses and releases with the handler that accepted key down. */
public final class RemoteKeyRouter<T> {
    public interface Handler<T> {
        boolean handle(T event);
    }

    private final Map<Integer, Handler<T>> owners = new HashMap<>();
    private final Set<Integer> pressed = new HashSet<>();
    private final Set<Integer> cancelled = new HashSet<>();
    private long generation;

    public void reset() {
        generation++;
        cancelled.addAll(pressed);
        cancelled.addAll(owners.keySet());
        pressed.clear();
        owners.clear();
    }

    public boolean dispatch(
            T event,
            int action,
            int keyCode,
            int repeats,
            boolean keyboard,
            boolean pageFirst,
            boolean pointerEnabled,
            Handler<T> pointer,
            Handler<T> page,
            Handler<T> platform,
            Runnable pausePointer) {

        // A held key from the previous window must not click the new window.
        if (cancelled.contains(keyCode)) {
            if (action == 0 && repeats == 0) {
                cancelled.remove(keyCode);
            } else {
                if (action == 1) cancelled.remove(keyCode);
                return true;
            }
        }

        if (action == 0) pressed.add(keyCode);
        if (action == 1) pressed.remove(keyCode);
        final long dispatchGeneration = generation;

        Handler<T> owner = owners.get(keyCode);
        if (owner != null && (action == 1 || (action == 0 && repeats > 0))) {
            if (action == 1) owners.remove(keyCode);
            owner.handle(event);
            return true;
        }

        if (action == 0) owners.remove(keyCode);

        if (keyboard || pageFirst || !pointerEnabled) pausePointer.run();
        if (dispatchGeneration != generation) return true;

        List<Handler<T>> handlers = keyboard
                ? Collections.singletonList(platform)
                : pageFirst || !pointerEnabled
                        ? Arrays.asList(page, platform)
                        : Arrays.asList(pointer, page, platform);

        for (Handler<T> handler : handlers) {
            if (handler.handle(event)) {
                // A focus change can reset us from inside a handler.
                if (action == 0 && dispatchGeneration == generation) {
                    owners.put(keyCode, handler);
                }
                return true;
            }
            if (dispatchGeneration != generation) return true;
        }
        if (action == 0) pressed.remove(keyCode);
        return false;
    }
}
