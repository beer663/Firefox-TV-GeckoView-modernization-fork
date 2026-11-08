# Firefox TV — GeckoView modernization fork

An Android TV browser based on Firefox TV, using GeckoView and a manual
Firefox extension installer.

The legacy System/WebView build variant is disabled. Build the Gecko variant
to use uBlock Origin.

## Requirements

- A full JDK 17, including javac.
- Android SDK.
- Android platform android-37.0.
- Android build tools 36.0.0.
- Android 8 / API 26 or later on the device.

Use the included Gradle wrapper. It pins Gradle 9.3.1.

## Build

Set ANDROID_HOME to your Android SDK directory, or configure sdk.dir in a
local.properties file at the project root.

Install the required SDK packages:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

The current compileSdk 37 configuration expects the platform directory
android-37. If your SDK installed it as android-37.0, create an alias on
Linux or macOS:

```sh
[ -e "$ANDROID_HOME/platforms/android-37" ] || ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
```

Build from the project root:

```sh
bash ./gradlew :app:assembleGeckoDebug
```

On Windows:

```bat
gradlew.bat :app:assembleGeckoDebug
```

In Android Studio, select the geckoDebug build variant.

The APK is written to:

```text
app/build/outputs/apk/gecko/debug/app-gecko-debug.apk
```

The GitHub Actions workflow in .github/workflows/build.yml includes SDK
setup and builds the Gecko debug APK.

## Install on a TV

Enable debugging on the device, then connect and install using ADB:

```sh
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/gecko/debug/app-gecko-debug.apk
```

The debug application ID is org.mozilla.tv.firefox.debug.

The universal APK includes ARMv7, ARM64, x86, and x86_64 native libraries,
so it is large.

## Manually install uBlock Origin

1. Open the browser.
2. Open Settings > Extensions.
3. Select Install uBlock Origin.
4. Confirm installation.
5. Review the requested permissions and select Allow.
6. Confirm uBlock Origin appears as Enabled.
7. Return to browsing.

Nothing is installed automatically.

The installer downloads the Mozilla-signed uBlock Origin XPI from Firefox
Add-ons. Browsing and extension installation use the same GeckoRuntime
and persistent profile.

The Extensions screen supports:

- Installing uBlock Origin.
- Installing a signed extension from a direct HTTPS XPI link.
- Listing installed extensions.
- Enabling and disabling extensions.
- Uninstalling extensions.

Pressing Back or dismissing a permission prompt denies that request.
Closing the Extensions screen also denies its outstanding permission
requests.

The integration does not yet expose extension toolbar popups or the
uBlock dashboard.

## Verification

The updated code passed:

- :app:assembleGeckoDebug
- :app:assembleDebug
- Debug APK signature verification

Device launch, extension installation, and actual ad blocking still need
testing on an Android TV device.

After installing uBlock Origin:

1. Visit a page with known third-party requests.
2. Compare behavior with uBlock enabled and disabled.
3. Restart the app.
4. Confirm uBlock remains installed and enabled.

Existing build-tool deprecation and translated-resource warnings remain.
The legacy automated test suites have not been validated as part of
this update.

## License

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, you can obtain one at https://mozilla.org/MPL/2.0/.
