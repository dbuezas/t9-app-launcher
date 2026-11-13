# T9 App Launcher

A tiny Android app launcher that does one thing: find an app with a T9 keypad and open it.

I used [AppDialer](https://play.google.com/store/apps/details?id=name.pilgr.appdialer.pro) for years in place of the
assistant. It is not maintained any more, so I wrote this replacement. It keeps only the T9 app search and focuses on
speed, size and a small RAM and CPU footprint.

- About 23 KB APK. Plain Java, no libraries, no AndroidX.
- The whole UI is one custom `View`: no layouts, no adapters.
- Drawn in software: for a panel this small it is faster than starting a GPU context and uses far less memory.
- On my Pixel 8: opens in about 30 ms when the process is warm and 90 ms from cold; about 11 MB PSS while open.
- The app list and the icons are cached on disk, so results show at once, even on a cold start.
- On a cold start the app list is only rescanned when Android reports a package change.
- Only the icons on screen are loaded; at most 48 stay in memory (8 once the panel is closed).
- No permissions, no network.

## How it works

- Type with the keypad (`2`–`9`). `C` clears; on an empty query it closes the panel.
- An app matches when the keys match:
  - the start of its name (`432` → **Hea**rts),
  - the start of any word (`6277` → Google **Maps**),
  - the first letters of words (`46` → **G**oogle **M**aps), or
  - any part of its name (3 or more keys).
- The matched letters are shown in blue.
- The apps I opened most recently from here come first.
- Tap an app to open it. Long-press it to open its App info.
- Apps from the work profile are included.

## Open it with the assistant gesture

The activity handles `android.intent.action.ASSIST`, so Android offers it as a digital assistant app.

Settings → Apps → Default apps → Digital assistant app → **T9 App Launcher**.

The assistant gesture (swipe up from a bottom corner, or long-press home) then opens it. Until it is set, the app
shows a bar on top that opens this settings page (tap ✕ to hide it).

If the corner swipe does nothing, check that the gesture is on. On Pixel: Settings → System → Gestures → Navigation
mode → ⚙ → Swipe to invoke assistant.

With adb:

```sh
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.dbuezas.t9launcher 0
```

## Download

Get the APK from [Releases](../../releases). Every push to `main` also builds an APK in GitHub Actions (see the
workflow artifacts).

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./install.sh                # build and install on the connected device
```

Release signing reads `keystore.properties` (gitignored) or the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` environment variables. Without them, the release build uses the debug key.

## License

MIT
