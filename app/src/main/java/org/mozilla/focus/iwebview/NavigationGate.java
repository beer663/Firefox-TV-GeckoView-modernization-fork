/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.iwebview;

import android.os.Handler;

public final class NavigationGate {
    private final Handler handler;
    private final Runnable fallback = this::release;
    private Runnable pending;
    private boolean released;
    private boolean closed;

    public NavigationGate(
            Handler handler,
            long timeoutMillis) {
        this.handler = handler;
        handler.postDelayed(fallback, timeoutMillis);
    }

    public void navigate(Runnable navigation) {
        if (closed) return;

        if (released) {
            navigation.run();
        } else {
            pending = navigation;
        }
    }

    public void release() {
        if (closed || released) return;

        released = true;
        handler.removeCallbacks(fallback);

        Runnable navigation = pending;
        pending = null;

        if (navigation != null) {
            navigation.run();
        }
    }

    public void close() {
        closed = true;
        pending = null;
        handler.removeCallbacks(fallback);
    }
}
