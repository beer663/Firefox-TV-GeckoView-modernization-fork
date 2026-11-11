/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.browser.cursor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Keeps repeated presses and releases with the handler that accepted key down. */
public final class RemoteKeyRouter<T> {
    public interface Handler<T> {
        boolean handle(T event);
    }

    private final Map<Integer, Handler<T>> owners = new HashMap<>();

    public void reset() {
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

        Handler<T> owner = owners.get(keyCode);
        if (owner != null && (action == 1 || (action == 0 && repeats > 0))) {
            if (action == 1) owners.remove(keyCode);
            owner.handle(event);
            return true;
        }

        if (action == 0) owners.remove(keyCode);

        if (keyboard || pageFirst || !pointerEnabled) pausePointer.run();

        List<Handler<T>> handlers = keyboard
                ? Collections.singletonList(platform)
                : pageFirst || !pointerEnabled
                        ? Arrays.asList(page, platform)
                        : Arrays.asList(pointer, page, platform);

        for (Handler<T> handler : handlers) {
            if (handler.handle(event)) {
                if (action == 0) owners.put(keyCode, handler);
                return true;
            }
        }
        return false;
    }
}
