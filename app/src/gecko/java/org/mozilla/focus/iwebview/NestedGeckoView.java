/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.iwebview;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import org.mozilla.focus.MainActivity;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Gecko host with native touch handling and small bookmark thumbnails. */
public class NestedGeckoView extends GeckoView {
    public NestedGeckoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setSession(GeckoSession session) {
        super.setSession(session);

        Context context = getContext();
        while (context instanceof ContextWrapper && !(context instanceof MainActivity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }

        if (context instanceof MainActivity) {
            ((MainActivity) context).getBrowserSupport().attach(session, this);
        }
    }

    @Override
    public GeckoResult<Bitmap> capturePixels() {
        return super.capturePixels().map(bitmap -> {
            float scale = Math.min(1f, Math.min(
                    480f / bitmap.getWidth(),
                    270f / bitmap.getHeight()));

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
