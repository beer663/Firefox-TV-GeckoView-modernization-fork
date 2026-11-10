/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */
package org.mozilla.focus.browser.cursor;

/** Cursor movement in host-view coordinates, independent of Android input. */
public final class RemoteCursorMotion {
    public static final int LEFT = 1, RIGHT = 2, UP = 4, DOWN = 8;

    public float x, y, scrollX, scrollY;

    private float width, height, speed;
    private int directions;

    public void resize(float w, float h) {
        if (w <= 1 || h <= 1) return;

        if (width == 0 || height == 0) {
            x = w / 2;
            y = h / 2;
        }

        width = w;
        height = h;
        clamp();
    }

    public void key(int direction, boolean pressed) {
        directions = pressed
                ? directions | direction
                : directions & ~direction;

        if (directions == 0) speed = 0;
    }

    public boolean moving() {
        return directions != 0;
    }

    public void stop() {
        directions = 0;
        speed = scrollX = scrollY = 0;
    }

    public void step(float seconds) {
        scrollX = scrollY = 0;

        if (width <= 1 || height <= 1 || directions == 0) return;

        float dt = Math.max(0, Math.min(seconds, 0.04f));
        float maximum = Math.max(360, width * 0.65f);

        speed = Math.min(
                maximum,
                Math.max(120, speed) + maximum * dt * 2);

        float dx = ((directions & RIGHT) != 0 ? 1 : 0)
                - ((directions & LEFT) != 0 ? 1 : 0);

        float dy = ((directions & DOWN) != 0 ? 1 : 0)
                - ((directions & UP) != 0 ? 1 : 0);

        if (dx != 0 && dy != 0) {
            dx *= 0.70710678f;
            dy *= 0.70710678f;
        }

        float nextX = x + dx * speed * dt;
        float nextY = y + dy * speed * dt;

        if (nextX < 1 || nextX > width - 1) {
            scrollX = dx * speed * dt * 0.6f;
        }

        if (nextY < 1 || nextY > height - 1) {
            scrollY = dy * speed * dt * 0.6f;
        }

        x = nextX;
        y = nextY;
        clamp();
    }

    private void clamp() {
        x = Math.max(1, Math.min(width - 1, x));
        y = Math.max(1, Math.min(height - 1, y));
    }
}
