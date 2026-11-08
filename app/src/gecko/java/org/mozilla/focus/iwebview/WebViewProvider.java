/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.iwebview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.focus.browser.LocalizedContent;
import org.mozilla.focus.diagnostics.PlaybackDiagnostics;
import org.mozilla.focus.session.Session;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.ScreenLength;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;

/** Bridges the original TV IWebView frontend onto GeckoView. */
public final class WebViewProvider {
    private static final String TAG = "TVGeckoAdapter";
    private static final String DESKTOP_SITE_PREF = "desktop_site_mode";
    private static final String BRIDGE_ID =
            "tv-page-controls@firefox-tv.local";

    private static GeckoRuntime runtime;
    private static GeckoResult<WebExtension> bridgeResult;

    private static final Set<GeckoWebView> liveViews =
            Collections.newSetFromMap(
                    new WeakHashMap<GeckoWebView, Boolean>());

    private WebViewProvider() {}

    private static synchronized GeckoRuntime runtime(Context context) {
        if (runtime == null) {
            runtime = GeckoRuntime.create(
                    context.getApplicationContext(),
                    new GeckoRuntimeSettings.Builder().build());

            bridgeResult = runtime.getWebExtensionController().ensureBuiltIn(
                    "resource://android/assets/extensions/tv-bridge/",
                    BRIDGE_ID);
        }

        return runtime;
    }

    public static GeckoRuntime getRuntime(Context context) {
        return runtime(context);
    }

    public static boolean isDesktopModeEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext()
        ).getBoolean(DESKTOP_SITE_PREF, true);
    }

    public static void setDesktopModeEnabled(
            Context context,
            boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext()
        ).edit()
                .putBoolean(DESKTOP_SITE_PREF, enabled)
                .apply();

        for (GeckoWebView view : new ArrayList<>(liveViews)) {
            view.applyDesktopMode(enabled);
        }
    }

    public static void preload(Context context) {
        runtime(context);
    }

    public static View create(Context context, AttributeSet attrs) {
        return new GeckoWebView(context, attrs);
    }

    public static View create(
            Context context,
            AttributeSet attrs,
            Object ignoredLegacyFactory) {
        return create(context, attrs);
    }

    public static void performCleanup(Context context) {
        clearBrowsingData(context).accept(
                ignored -> {},
                error -> Log.e(
                        TAG,
                        "Could not clear browsing data",
                        error));
    }

    public static GeckoResult<Void> clearBrowsingData(Context context) {
        GeckoRuntime currentRuntime = runtime(context);

        for (GeckoWebView view : new ArrayList<>(liveViews)) {
            view.destroy();

            if (view.boundSession != null) {
                view.boundSession.saveWebViewState(null);
            }
        }

        return currentRuntime.getStorageController().clearData(
                StorageController.ClearFlags.SITE_DATA
                        | StorageController.ClearFlags.AUTH_SESSIONS);
    }

    public static void performNewBrowserSessionCleanup() {
        // GeckoRuntime owns process and profile cleanup.
    }

    public static class GeckoWebView
            extends NestedGeckoView implements IWebView {

        private static final String STATE_KEY =
                "firefox.tv.gecko.sessionState";

        private static final String URL_KEY =
                "firefox.tv.gecko.url";

        private final GeckoSession geckoSession;
        private Callback callback;
        private String currentUrl = "about:blank";
        private String title = "";
        private boolean backEnabled;
        private boolean forwardEnabled;
        private boolean secure;
        private Bitmap lastScreenshot;

        private GeckoSession.SessionState latestState;
        private Session boundSession;
        private boolean destroyed;
        private boolean desktopMode;
        private boolean bridgeReady;
        private Runnable pendingNavigation;
        private WebExtension.Port pagePort;

        private final List<JSONObject> pendingCommands =
                new ArrayList<>();

        private final Handler diagnosticsHandler =
                new Handler(Looper.getMainLooper());

        private final Runnable diagnosticsListener =
                this::updateDiagnosticsSampling;

        private boolean diagnosticsForeground;
        private long nextSampleId;
        private long pendingSampleId;
        private long pendingSampleEpoch;
        private long sampleRequestedAt;

        private final Runnable diagnosticsTick = new Runnable() {
            @Override
            public void run() {
                if (destroyed
                        || !diagnosticsForeground
                        || !PlaybackDiagnostics.isRecording()
                        || pagePort == null) {
                    return;
                }

                if (
                        pendingSampleId != 0
                                && SystemClock.elapsedRealtime()
                                        - sampleRequestedAt < 6000) {
                    diagnosticsHandler.postDelayed(this, 2000);
                    return;
                }

                if (pendingSampleId != 0) {
                    PlaybackDiagnostics.recordUnavailable(
                            pendingSampleEpoch);
                }

                pendingSampleId = ++nextSampleId;
                pendingSampleEpoch = PlaybackDiagnostics.epoch();
                sampleRequestedAt = SystemClock.elapsedRealtime();

                try {
                    pagePort.postMessage(
                            new JSONObject()
                                    .put("action", "collectPlayback")
                                    .put("id", pendingSampleId));
                } catch (JSONException | RuntimeException error) {
                    PlaybackDiagnostics.recordUnavailable(
                            pendingSampleEpoch);
                    pendingSampleId = 0;
                }

                diagnosticsHandler.postDelayed(this, 2000);
            }
        };

        private void updateDiagnosticsSampling() {
            diagnosticsHandler.removeCallbacks(diagnosticsTick);
            pendingSampleId = 0;

            if (!destroyed
                    && diagnosticsForeground
                    && PlaybackDiagnostics.isRecording()
                    && pagePort != null) {
                diagnosticsHandler.post(diagnosticsTick);
            }
        }

        private final FocusedDOMElementCache focusCache =
                new FocusedDOMElementCache() {
                    @Override
                    public void cache() {
                        sendCommand("cacheFocus", null);
                    }

                    @Override
                    public void restore() {
                        sendCommand("restoreFocus", null);
                    }
                };

        private final WebExtension.MessageDelegate bridgeDelegate =
                new WebExtension.MessageDelegate() {
                    @Override
                    public void onConnect(
                            @NonNull WebExtension.Port port) {
                        if (destroyed
                                || port.sender.session != geckoSession
                                || !BRIDGE_ID.equals(
                                        port.sender.webExtension.id)
                                || !port.sender.isTopLevel()) {
                            port.disconnect();
                            return;
                        }

                        pagePort = port;

                        port.setDelegate(
                                new WebExtension.PortDelegate() {
                                    @Override
                                    public void onDisconnect(
                                            @NonNull WebExtension.Port disconnected) {
                                        if (pagePort == disconnected) {
                                            pagePort = null;
                                        }
                                    }

                                    @Override
                                    public void onPortMessage(
                                            @NonNull Object message,
                                            @NonNull WebExtension.Port source) {
                                        if (destroyed
                                                || source != pagePort
                                                || !(message instanceof JSONObject)) {
                                            return;
                                        }

                                        JSONObject response =
                                                (JSONObject) message;

                                        if (!"playbackSample".equals(
                                                response.optString("action"))
                                                || pendingSampleId == 0
                                                || response.optLong("id", -1)
                                                        != pendingSampleId) {
                                            return;
                                        }

                                        PlaybackDiagnostics.record(
                                                pendingSampleEpoch,
                                                response);

                                        pendingSampleId = 0;
                                    }
                                });

                        for (
                                JSONObject command
                                : new ArrayList<>(pendingCommands)) {
                            port.postMessage(command);
                        }

                        pendingCommands.clear();
                        updateDiagnosticsSampling();
                    }
                };

        private void sendCommand(
                String action,
                @Nullable String code) {
            if (destroyed) {
                return;
            }

            try {
                JSONObject message =
                        new JSONObject().put("action", action);

                if (code != null) {
                    message.put("code", code);
                }

                if (pagePort != null) {
                    pagePort.postMessage(message);
                } else if (
                        currentUrl.startsWith("https://")
                                || currentUrl.startsWith("http://")) {
                    if (pendingCommands.size() == 32) {
                        pendingCommands.remove(0);
                    }

                    pendingCommands.add(message);
                }
            } catch (JSONException e) {
                throw new IllegalArgumentException(
                        "Invalid page operation",
                        e);
            }
        }

        private void navigate(Runnable navigation) {
            if (destroyed) {
                return;
            }

            if (bridgeReady) {
                navigation.run();
            } else {
                pendingNavigation = navigation;
            }
        }

        private void saveLatestState() {
            if (boundSession == null) {
                return;
            }

            Bundle bundle = new Bundle();
            bundle.putString(URL_KEY, currentUrl);

            if (latestState != null) {
                bundle.putString(
                        STATE_KEY,
                        latestState.toString());
            }

            boundSession.saveWebViewState(bundle);
        }

        public GeckoWebView(Context context, AttributeSet attrs) {
            super(context, attrs);

            desktopMode = isDesktopModeEnabled(context);

            geckoSession = new GeckoSession(
                    new GeckoSessionSettings.Builder()
                            .userAgentMode(
                                    desktopMode
                                            ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                                            : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                            .viewportMode(
                                    desktopMode
                                            ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                                            : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                            .usePrivateMode(false)
                            .useTrackingProtection(false)
                            .build());

            geckoSession.setProgressDelegate(
                    new GeckoSession.ProgressDelegate() {
                        @Override
                        public void onPageStart(
                                @NonNull GeckoSession session,
                                @NonNull String url) {
                            currentUrl = url;
                            secure = false;
                            pagePort = null;
                            pendingCommands.clear();
                            lastScreenshot = null;

                            if (callback != null) {
                                callback.onPageStarted(url);
                            }
                        }

                        @Override
                        public void onPageStop(
                                @NonNull GeckoSession session,
                                boolean success) {
                            if (callback != null) {
                                callback.onProgress(100);
                                callback.onPageFinished(secure);
                            }

                            if (success) {
                                final String capturedUrl = currentUrl;

                                capturePixels().accept(
                                        bitmap -> {
                                            if (!destroyed
                                                    && capturedUrl.equals(currentUrl)) {
                                                lastScreenshot = bitmap;
                                            }
                                        },
                                        error -> Log.d(
                                                TAG,
                                                "Page thumbnail unavailable",
                                                error));
                            }
                        }

                        @Override
                        public void onSessionStateChange(
                                @NonNull GeckoSession session,
                                @NonNull GeckoSession.SessionState state) {
                            if (destroyed) {
                                return;
                            }

                            latestState =
                                    new GeckoSession.SessionState(state);

                            saveLatestState();
                        }

                        @Override
                        public void onProgressChange(
                                @NonNull GeckoSession session,
                                int progress) {
                            if (callback != null) {
                                callback.onProgress(progress);
                            }
                        }

                        @Override
                        public void onSecurityChange(
                                @NonNull GeckoSession session,
                                @NonNull GeckoSession.ProgressDelegate.SecurityInformation information) {
                            secure = information.isSecure;
                        }
                    });

            geckoSession.setNavigationDelegate(
                    new GeckoSession.NavigationDelegate() {
                        @Override
                        public void onLocationChange(
                                @NonNull GeckoSession session,
                                @Nullable String url,
                                @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                                @NonNull Boolean hasUserGesture) {
                            if (url != null) {
                                currentUrl = url;

                                if (callback != null) {
                                    callback.onURLChanged(url);
                                }
                            }
                        }

                        @Override
                        public void onCanGoBack(
                                @NonNull GeckoSession session,
                                boolean canGoBack) {
                            backEnabled = canGoBack;
                        }

                        @Override
                        public void onCanGoForward(
                                @NonNull GeckoSession session,
                                boolean canGoForward) {
                            forwardEnabled = canGoForward;
                        }
                    });

            geckoSession.setContentDelegate(
                    new GeckoSession.ContentDelegate() {
                        @Override
                        public void onTitleChange(
                                @NonNull GeckoSession session,
                                @Nullable String newTitle) {
                            title = newTitle == null ? "" : newTitle;
                        }

                        @Override
                        public void onFullScreen(
                                @NonNull GeckoSession session,
                                boolean fullScreen) {
                            if (callback == null) {
                                return;
                            }

                            if (fullScreen) {
                                callback.onEnterFullScreen(
                                        () -> geckoSession.exitFullScreen(),
                                        null);
                            } else {
                                callback.onExitFullScreen();
                            }
                        }
                    });

            GeckoRuntime currentRuntime = runtime(context);
            geckoSession.open(currentRuntime);
            setSession(geckoSession);
            liveViews.add(this);

            PlaybackDiagnostics.addListener(
                    diagnosticsListener);

            bridgeResult.accept(
                    extension -> {
                        if (destroyed) {
                            return;
                        }

                        geckoSession
                                .getWebExtensionController()
                                .setMessageDelegate(
                                        extension,
                                        bridgeDelegate,
                                        "tvPageControls");

                        finishBridgeSetup();
                    },
                    error -> {
                        if (destroyed) {
                            return;
                        }

                        Log.e(
                                TAG,
                                "TV page controls could not start",
                                error);

                        Toast.makeText(
                                getContext(),
                                "TV page controls unavailable",
                                Toast.LENGTH_LONG).show();

                        finishBridgeSetup();
                    });
        }

        private void applyDesktopMode(boolean enabled) {
            if (destroyed || desktopMode == enabled) {
                return;
            }

            desktopMode = enabled;

            geckoSession.getSettings().setUserAgentMode(
                    enabled
                            ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                            : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);

            geckoSession.getSettings().setViewportMode(
                    enabled
                            ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                            : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);

            if (
                    bridgeReady
                            && (
                                    currentUrl.startsWith("https://")
                                            || currentUrl.startsWith("http://"))) {
                geckoSession.reload(
                        GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
            }
        }

        private void finishBridgeSetup() {
            bridgeReady = true;

            Runnable navigation = pendingNavigation;
            pendingNavigation = null;

            if (navigation != null) {
                navigation.run();
            }
        }

        @Override
        protected void onFocusChanged(
                boolean gainFocus,
                int direction,
                @Nullable Rect previouslyFocusedRect) {
            if (geckoSession != null && !gainFocus) {
                focusCache.cache();
            }

            super.onFocusChanged(
                    gainFocus,
                    direction,
                    previouslyFocusedRect);

            if (geckoSession != null && gainFocus) {
                post(focusCache::restore);
            }
        }

        @Nullable
        @Override
        public Callback getCallback() {
            return callback;
        }

        @Override
        public void setCallback(@Nullable Callback value) {
            callback = value;
        }

        @Nullable
        @Override
        public String getTitle() {
            return title;
        }

        @Nullable
        @Override
        public String getUrl() {
            return currentUrl;
        }

        @Override
        public void loadUrl(@NonNull String url) {
            if (LocalizedContent.handleInternalContent(url, this)) {
                return;
            }

            currentUrl = url;
            navigate(() -> geckoSession.loadUri(url));
        }

        @Override
        public void reload() {
            geckoSession.reload();
        }

        @Override
        public void goBack() {
            geckoSession.goBack();
        }

        @Override
        public void goForward() {
            geckoSession.goForward();
        }

        @Override
        public boolean canGoBack() {
            return backEnabled;
        }

        @Override
        public boolean canGoForward() {
            return forwardEnabled;
        }

        @Override
        public void stopLoading() {
            geckoSession.stop();
        }

        @Override
        public void onPause() {
            diagnosticsForeground = false;
            updateDiagnosticsSampling();

            if (!destroyed) {
                geckoSession.setFocused(false);
            }
        }

        @Override
        public void onResume() {
            applyDesktopMode(
                    isDesktopModeEnabled(getContext()));

            diagnosticsForeground = true;
            updateDiagnosticsSampling();

            if (!destroyed) {
                geckoSession.setFocused(hasFocus());
            }
        }

        @Override
        public void onStart() {
            if (!destroyed) {
                geckoSession.setActive(true);
            }
        }

        @Override
        public void onStop() {
            diagnosticsForeground = false;
            updateDiagnosticsSampling();

            if (!destroyed) {
                geckoSession.setActive(false);
            }
        }

        @Override
        public void pauseTimers() {
            sendCommand("pauseMedia", null);
        }

        @Override
        public void resumeTimers() {
            if (!destroyed) {
                geckoSession.setActive(true);
            }
        }

        @Override
        public void evalJS(@NonNull String js) {
            sendCommand("eval", js);
        }

        @Override
        public void scrollByClamped(int vx, int vy) {
            getPanZoomController().scrollBy(
                    ScreenLength.fromPixels((double) vx),
                    ScreenLength.fromPixels((double) vy));
        }

        @NonNull
        @Override
        public Bitmap takeScreenshot() {
            if (lastScreenshot != null
                    && !lastScreenshot.isRecycled()) {
                return lastScreenshot;
            }

            Bitmap fallback = Bitmap.createBitmap(
                    Math.max(1, getWidth()),
                    Math.max(1, getHeight()),
                    Bitmap.Config.ARGB_8888);

            draw(new Canvas(fallback));
            return fallback;
        }

        @NonNull
        @Override
        public FocusedDOMElementCache getFocusedDOMElement() {
            return focusCache;
        }

        @Override
        public void setBlockingEnabled(boolean enabled) {
            geckoSession.getSettings()
                    .setUseTrackingProtection(enabled);

            if (callback != null) {
                callback.onBlockingStateChanged(enabled);
            }
        }

        @Override
        public void cleanup() {
            performCleanup(getContext());
        }

        @Override
        public void saveWebViewState(@NonNull Session session) {
            if (destroyed) {
                return;
            }

            boundSession = session;
            saveLatestState();
            geckoSession.flushSessionState();
        }

        @Override
        public void restoreWebViewState(@NonNull Session session) {
            if (destroyed) {
                return;
            }

            boundSession = session;
            Bundle bundle = session.getWebViewState();

            if (bundle != null) {
                GeckoSession.SessionState state =
                        GeckoSession.SessionState.fromString(
                                bundle.getString(STATE_KEY));

                if (state != null) {
                    latestState = state;

                    currentUrl = bundle.getString(
                            URL_KEY,
                            session.getUrl().getValue());

                    navigate(
                            () -> geckoSession.restoreState(state));

                    return;
                }
            }

            loadUrl(session.getUrl().getValue());
        }

        @Override
        public void destroy() {
            if (destroyed) {
                return;
            }

            saveLatestState();
            destroyed = true;

            diagnosticsHandler.removeCallbacks(
                    diagnosticsTick);

            PlaybackDiagnostics.removeListener(
                    diagnosticsListener);

            callback = null;
            pendingNavigation = null;
            pendingCommands.clear();

            if (pagePort != null) {
                pagePort.disconnect();
            }

            pagePort = null;
            liveViews.remove(this);
            releaseSession();
            geckoSession.close();
            lastScreenshot = null;
        }

        @Override
        public boolean isYoutubeTV() {
            return currentUrl.contains("youtube.com/tv");
        }
    }
}
