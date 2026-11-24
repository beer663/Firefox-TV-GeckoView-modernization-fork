# Firefox TV — GeckoView modernization fork

# Mom Firefox for Android TV

A community Android TV browser built with Mozilla GeckoView and a remote-friendly interface, based on Firefox for Fire TV.

This is an independent project, not an official Mozilla release. The current downloadable APK is an ARMv7 **debug test build**. A public GitHub release does not change it into an Android release build.

## Download and install

1. Open this repository's **Releases** page.
2. Open the latest release and expand **Assets**.
3. Download `Moms-Firefox-TV-ARMv7-debug.apk`.
4. Transfer the APK to your TV and install it. Allow installation from your chosen file manager or installer when Android requests it.

The source-code ZIP files under Assets contain the project source, not an installable app.

### Requirements

- Android TV running Android 8.0 / API 26 or later.
- Support for 32-bit ARMv7 apps (`armeabi-v7a`). A device that supports only 64-bit apps cannot install this APK.
- An internet connection for browsing and installing extensions.

Some streaming services require DRM, codecs, subscriptions, or device certification that this app cannot provide.

### Updating test builds

The current workflow uses automatic Android debug signing. Different GitHub runners can use different debug certificates, so some builds may require uninstalling the previous build before installation.

**Uninstalling removes the app's saved bookmarks, history, extensions, cookies, and other local app data.**

Reliable updates that preserve app data require future builds to use the same persistent signing key.

## Features

- GeckoView as the browser engine.
- TV remote cursor navigation and edge scrolling.
- Gray-and-white toolbar with address/search, Back, Forward, Refresh, Home, bookmarks, history, downloads, and settings.
- Address text selected when the address field is opened, making replacement easier.
- Equal-sized bookmark tiles with longer visible names. Hold OK on a tile to edit its name or remove it.
- Video helper with Unmute and Full screen controls for supported HTML video players.
- Optional uBlock Origin installation, enabling, disabling, and removal.
- Blocking of website requests for additional browser windows.
- Desktop-site option and manually opened playback diagnostics.
- Page recovery controls when Android stops a website process.
- Direct startup into the browser home screen without the old startup report or Turbo selection screens.

## Remote controls

- **D-pad:** move the cursor. Hold a direction at the screen edge to scroll.
- **OK:** click the item under the cursor.
- **Back:** leave fullscreen, return to the page, or navigate back as appropriate.
- **Menu:** open the address field.
- **Hold OK on a bookmark:** edit its name or remove it.
- **Hold OK during fullscreen video:** switch between the video pointer and video remote controls.

## uBlock Origin

Open **Settings > Extensions > Install uBlock Origin**, then review and approve its requested permissions.

uBlock Origin is optional. Its install, enable/disable, and uninstall controls remain available in Extensions. Other signed Firefox extensions can be installed from an HTTPS XPI link when compatible with GeckoView.

## Recent changes

See [RELEASE-NOTES.md](RELEASE-NOTES.md) for the current release notes, including the latest ntv.cx scrolling change and verification status.

The existing [CHANGELOG.md](CHANGELOG.md) retains the original Firefox TV release history.

Install the SDK packages:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

Set `ANDROID_HOME` to your Android SDK directory, or set `sdk.dir` in a local `local.properties` file.

If the SDK platform directory is named `android-37.0`, create the alias expected by `compileSdk 37` on Linux or macOS:

```sh
if [ ! -e "$ANDROID_HOME/platforms/android-37" ]; then
  ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
fi
```

Build:

```sh
bash ./gradlew :app:assembleGeckoDebug
```

On Windows, use `gradlew.bat :app:assembleGeckoDebug`.

Output: `app/build/outputs/apk/gecko/debug/app-gecko-debug.apk`.

The default build includes ARMv7 only. The debug application ID is `org.mozilla.tv.firefox.debug`.

## Publish a GitHub release

1. Make the repository public if you want downloads available to everyone.
2. Update `RELEASE-NOTES.md` with the changes included in the next build.
3. Commit the application changes and release notes to the branch you want to publish.
4. Open **Actions > Build Mom Firefox and publish GitHub release > Run workflow** and select that branch.
5. After the workflow succeeds, open **Releases** and download the APK from Assets.

The workflow creates a normal GitHub release and marks it Latest. The APK remains a debug test build.

## License and credits

Based on [Mozilla Firefox for Fire TV](https://github.com/mozilla-mobile/firefox-tv) and built with [Mozilla GeckoView](https://mozilla.github.io/geckoview/).

Project source is provided under the [Mozilla Public License 2.0](LICENSE). Preserve the existing license and source notices when redistributing this project. Dependencies and installed extensions have their own licenses.
