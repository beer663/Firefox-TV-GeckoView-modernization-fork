/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.iwebview;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoView;

/**
 * Retains the adapter name used by WebViewProvider. The browser is hosted in
 * FrameLayouts, so Gecko can handle touch and scrolling directly. The old
 * nested-scroll shim copied every MotionEvent and misreported scroll distances.
 */
public class NestedGeckoView extends GeckoView {
    public NestedGeckoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public GeckoResult<Bitmap> capturePixels() {
        // The adapter uses captures only for bookmark thumbnails. Do not retain
        // a full TV-resolution bitmap throughout video playback.
        return super.capturePixels().map(bitmap -> {
            float scale = Math.min(1f, Math.min(
                    480f / bitmap.getWidth(), 270f / bitmap.getHeight()));
            if (scale >= 1f) return bitmap;

            Bitmap thumbnail = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)),
                    true);
            if (thumbnail != bitmap) bitmap.recycle();
            return thumbnail;
        });
    }
}
