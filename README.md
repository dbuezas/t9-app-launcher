# T9 App Launcher

A tiny Android app launcher that does one thing: find an app with a T9 keypad and open it.

I used [AppDialer](https://play.google.com/store/apps/details?id=name.pilgr.appdialer.pro) for years in place of the
assistant. It is not maintained any more, so I wrote this replacement. It keeps only the T9 app search and focuses on
speed, size and a small RAM and CPU footprint.

- About 21 KB APK. Plain Java, no libraries, no AndroidX.
- The whole UI is one custom `View`: no layouts, no adapters.
- Opens in about 55 ms when the process is warm.
- The app list and the icons are cached on disk, so results show at once, even on a cold start.
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

The assistant gesture (swipe up from a bottom corner, or long-press home) then opens it.

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
