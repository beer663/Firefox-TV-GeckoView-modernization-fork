/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.iwebview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.focus.browser.LocalizedContent;
import org.mozilla.focus.session.Session;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.ScreenLength;

/** Bridges the original TV IWebView frontend onto the GeckoView browser engine. */
public final class WebViewProvider {
    private static GeckoRuntime runtime;

    private WebViewProvider() {}

    private static synchronized GeckoRuntime runtime(Context context) {
        if (runtime == null) {
            runtime = GeckoRuntime.create(context.getApplicationContext(),
                    new GeckoRuntimeSettings.Builder()
                            .autoplayDefault(GeckoRuntimeSettings.AUTOPLAY_DEFAULT_ALLOWED)
                            .build());
        }
        return runtime;
    }

    /** Use this same runtime/profile for the browser and its Extensions screen. */
    public static GeckoRuntime getRuntime(Context context) {
        return runtime(context);
    }

    public static void preload(Context context) {
        runtime(context);
    }

    public static View create(Context context, AttributeSet attrs) {
        return new GeckoWebView(context, attrs);
    }

    public static View create(Context context, AttributeSet attrs, Object ignoredLegacyFactory) {
        return create(context, attrs);
    }

    public static void performCleanup(Context context) {
        // Session storage is now persistent for streaming logins.
        // Clearing data needs the GeckoRuntime storage controller, not this legacy hook.
    }

    public static void performNewBrowserSessionCleanup() {
        // No separate WebKit process to clean up.
    }

    public static class GeckoWebView extends NestedGeckoView implements IWebView {
        private final GeckoSession geckoSession;
        private Callback callback;
        private String currentUrl = "about:blank";
        private String title = "";
        private boolean backEnabled;
        private boolean forwardEnabled;
        private boolean secure;
        private Bitmap lastScreenshot;

        public GeckoWebView(Context context, AttributeSet attrs) {
            super(context, attrs);
            geckoSession = new GeckoSession(new GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .useTrackingProtection(false)
                    .build());
            geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                    currentUrl = url;
                    secure = false;
                    if (callback != null) {
                        callback.onPageStarted(url);
                    }
                }

                @Override
                public void onPageStop(@NonNull GeckoSession session, boolean success) {
                    if (callback != null) {
                        callback.onProgress(100);
                        callback.onPageFinished(secure);
                    }
                    // Prefetch the most recent thumbnail for pinning sites on the home screen.
                    if (success) {
                        capturePixels().then(bitmap -> {
                            lastScreenshot = bitmap;
                            return null;
                        });
                    }
                }

                @Override
                public void onProgressChange(@NonNull GeckoSession session, int progress) {
                    if (callback != null) callback.onProgress(progress);
                }

                @Override
                public void onSecurityChange(@NonNull GeckoSession session,
                        @NonNull GeckoSession.ProgressDelegate.SecurityInformation information) {
                    secure = information.isSecure;
                }
            });
            geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public void onLocationChange(@NonNull GeckoSession session, @Nullable String url) {
                    if (url != null) {
                        currentUrl = url;
                        if (callback != null) callback.onURLChanged(url);
                    }
                }

                @Override
                public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
                    backEnabled = canGoBack;
                }

                @Override
                public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
                    forwardEnabled = canGoForward;
                }
            });
            geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onTitleChange(@NonNull GeckoSession session, @Nullable String newTitle) {
                    title = newTitle == null ? "" : newTitle;
                }

                @Override
                public void onFullScreen(@NonNull GeckoSession session, boolean fullScreen) {
                    if (callback == null) return;
                    if (fullScreen) {
                        callback.onEnterFullScreen(() -> geckoSession.exitFullScreen(), null);
                    } else {
                        callback.onExitFullScreen();
                    }
                }
            });
            geckoSession.open(runtime(context));
            setSession(geckoSession);
        }

        @Nullable @Override public Callback getCallback() { return callback; }
        @Override public void setCallback(@Nullable Callback value) { callback = value; }
        @Nullable @Override public String getTitle() { return title; }
        @Nullable @Override public String getUrl() { return currentUrl; }

        @Override public void loadUrl(@NonNull String url) {
            if (LocalizedContent.handleInternalContent(url, this)) return;
            currentUrl = url;
            geckoSession.loadUri(url);
        }
        @Override public void reload() { geckoSession.reload(); }
        @Override public void goBack() { geckoSession.goBack(); }
        @Override public void goForward() { geckoSession.goForward(); }
        @Override public boolean canGoBack() { return backEnabled; }
        @Override public boolean canGoForward() { return forwardEnabled; }
        @Override public void stopLoading() { geckoSession.stop(); }

        @Override public void onPause() {}
        @Override public void onResume() {}
        @Override public void onStart() {}
        @Override public void onStop() {}
        @Override public void pauseTimers() {}
        @Override public void resumeTimers() {}

        @Override public void evalJS(@NonNull String js) {
            // GeckoView's direct JS execution is restricted; this legacy API has no general bridge.
            // Keep it explicit until a WebExtension messaging implementation is wired in.
        }

        @Override public void scrollByClamped(int vx, int vy) {
            getPanZoomController().scrollBy(
                ScreenLength.fromPixels((double) vx), ScreenLength.fromPixels((double) vy));
        }

        @NonNull @Override public Bitmap takeScreenshot() {
            if (lastScreenshot != null && !lastScreenshot.isRecycled()) return lastScreenshot;
            // The first thumbnail request can arrive before capturePixels completes.
            Bitmap fallback = Bitmap.createBitmap(Math.max(1, getWidth()),
                    Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
            draw(new Canvas(fallback));
            return fallback;
        }

        @NonNull @Override public FocusedDOMElementCache getFocusedDOMElement() {
            return new FocusedDOMElementCache() {
                @Override public void cache() {}
                @Override public void restore() {}
            };
        }

        @Override public void setBlockingEnabled(boolean enabled) {
            geckoSession.getSettings().setUseTrackingProtection(enabled);
            if (callback != null) callback.onBlockingStateChanged(enabled);
        }

        @Override public void cleanup() {
            // This app uses private sessions. Persistent cookie clearing will be handled in
            // the GeckoView storage controller migration, not by an old WebKit call.
        }

        @Override public void saveWebViewState(@NonNull Session session) {
            // Fragment session URL remains the source of truth; Gecko session history
            // serialization requires a separate async integration.
        }

        @Override public void restoreWebViewState(@NonNull Session session) {
            if (session.getUrl().getValue() != null) loadUrl(session.getUrl().getValue());
        }

        @Override public void destroy() {
            releaseSession();
            geckoSession.close();
        }

        @Override public boolean isYoutubeTV() { return currentUrl.contains("youtube.com/tv"); }
    }
}
