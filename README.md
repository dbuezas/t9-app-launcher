# T9 App Launcher

A tiny Android app launcher that does one thing: find an app (or a settings page) with a T9 keypad and open it.

| Recent apps | `6277` → Maps | `46` → **G**mail, **Go**ogle… |
|---|---|---|
| <img src="docs/empty.png" width="260"> | <img src="docs/maps.png" width="260"> | <img src="docs/go.png" width="260"> |

I used [AppDialer](https://play.google.com/store/apps/details?id=name.pilgr.appdialer.pro) for years in place of the
assistant. It is not maintained any more, so I wrote this replacement. It keeps only the T9 app search and focuses on
speed, size and a small RAM and CPU footprint.

- About 23 KB APK. Plain Java, no libraries, no AndroidX.
- The whole UI is one custom `View`: no layouts, no adapters.
- Drawn in software: for a panel this small it is faster than starting a GPU context and uses far less memory.
- On my Pixel 8: opens in about 30 ms when the process is warm and 90 ms from cold; about 11 MB PSS while open.
- The app list and the icons are cached on disk, so results show at once, even on a cold start.
- The app list is only rescanned when Android reports a package change. That check runs in the background on every
  open, so it never delays the panel.
- Only the icons on screen are loaded; at most 48 stay in memory (8 once the panel is closed).
- No permissions, no network.

## Performance

Measured on my Pixel 8 (Android 17): the median of 8 cold and 8 warm opens, with `am start -W` for the time and
`/proc/<pid>/stat` for the CPU time; RAM is the PSS from `dumpsys meminfo`.

| | AppDialer Pro 7.5.1 | T9 App Launcher 1.0.0 | T9 App Launcher 1.1.0 |
|---|---:|---:|---:|
| APK size | 2,203 KB | 21 KB | **23 KB** |
| Cold open, time | 124 ms | 105 ms | **96 ms** |
| Cold open, CPU time | 250 ms | 170 ms | **50 ms** |
| Warm open, time | 61 ms | 54 ms | **42 ms** |
| Warm open, CPU time | 90 ms | 30 ms | **20 ms** |
| RAM while open | 119 MB | 36 MB | **18 MB** |
| RAM after close | 57 MB | 22 MB | **19 MB** |

"Cold" means the process was not running; "warm" means it was still in memory.

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
- Settings pages are searchable too, read from the phone's own Settings app: every page it lets other apps open, with
  its translated title (about 160 on my Pixel 8). The words of the page's internal name are searchable as well, so
  `blue` finds the Bluetooth page even where it is titled "Devices". Settings pages appear in the empty panel only
  once you used them.
- Long names wrap to a second line at any character, so no space is lost to word wrapping.

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
