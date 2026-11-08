/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.browser;

import java.util.Objects;

/** Remote-key gestures and asynchronous fullscreen exit. */
public final class BrowserNavigationState {
    private Runnable exitFullscreen;
    private boolean exitRequested;
    private boolean backGesture;
    private boolean menuGesture;

    public boolean isFullscreen() {
        return exitFullscreen != null;
    }

    public void enterFullscreen(Runnable exit) {
        exitFullscreen = Objects.requireNonNull(exit);
        exitRequested = false;
    }

    public boolean requestFullscreenExit() {
        if (!isFullscreen()) {
            return false;
        }

        if (!exitRequested) {
            exitRequested = true;
            exitFullscreen.run();
        }

        return true;
    }

    /** An engine notification acknowledges exit; it never requests another exit. */
    public boolean onFullscreenExit() {
        boolean wasFullscreen = isFullscreen();

        exitFullscreen = null;
        exitRequested = false;

        // Preserve key latches because Gecko can exit before the key is released.
        return wasFullscreen;
    }

    public boolean onBackDown(boolean overlayVisible, Runnable closeOverlay) {
        if (backGesture) {
            return true;
        }

        if (isFullscreen()) {
            backGesture = true;
            requestFullscreenExit();
            return true;
        }

        if (overlayVisible) {
            backGesture = true;
            closeOverlay.run();
            return true;
        }

        return false;
    }

    public boolean onBackUp() {
        boolean consumed = backGesture;
        backGesture = false;
        return consumed;
    }

    public void onMenuDown(Runnable toggleMenu) {
        if (menuGesture) {
            return;
        }

        menuGesture = true;
        toggleMenu.run();
    }

    public boolean onMenuUp() {
        boolean consumed = menuGesture;
        menuGesture = false;
        return consumed;
    }

    public void reset() {
        exitFullscreen = null;
        exitRequested = false;
        backGesture = false;
        menuGesture = false;
    }
}
