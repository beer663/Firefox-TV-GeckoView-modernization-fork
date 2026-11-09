/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.browser;

/** A held OK toggles pointer mode without also clicking the video. */
public final class FullscreenPointerGesture {
    public enum Action {
        WAIT,
        CLICK,
        TOGGLE
    }

    private boolean pressed;
    private boolean toggled;
    private long downAt;

    public boolean isPressed() {
        return pressed;
    }

    public Action down(long time, long timeout) {
        if (!pressed) {
            pressed = true;
            toggled = false;
            downAt = time;
            return Action.WAIT;
        }

        if (!toggled && time - downAt >= timeout) {
            toggled = true;
            return Action.TOGGLE;
        }

        return Action.WAIT;
    }

    public Action up(long time, long timeout, boolean cancelled) {
        if (!pressed) {
            return Action.WAIT;
        }

        Action action = cancelled || toggled
                ? Action.WAIT
                : time - downAt >= timeout
                        ? Action.TOGGLE
                        : Action.CLICK;

        reset();
        return action;
    }

    public void reset() {
        pressed = false;
        toggled = false;
        downAt = 0;
    }
}
