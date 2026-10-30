# Melo Free TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
Melo Free TV is a fork of Free My TV (simbasjungles/free-my-tv, MIT) for Android TV and Google TV only: the LG
(webOS) and Samsung (Tizen) versions and the website were left out until they can be tested.
Before you push, run `tools/check.sh`: the Android TV build and `translate.py --check` (it takes `python` where there is
no `python3`, as on Windows). Every pull request runs it too (`.github/workflows/check.yml`), with JDK 17,
build-tools 34.0.0 and android-34: a newer SDK here can let through a call that fails there.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
  The Kalley 43G200 (`R3_GTV`) turns it back on at every start and boot-looped without its home screen: the switch
  refuses there. On Amazon Fire TV (Fire OS: `HomeMode.AMAZON`), Amazon's launcher keeps the Home button (`priority=950`
  on `CATEGORY_HOME`), so the switch refuses Free My TV mode while *Original* and the watchdog still run, and no
  `com.amazon.*` package is ever turned off (`mayTurnOff`).
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot
  check leaves the setup app and the stock home screen for later.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`. With the release's APK (no
   `config.properties`), they add it on the TV themselves: Home Settings > TMDB key (a QR code for their phone, or typed).
   Without a key everything works except the picks and their photos on the screensaver (photos they send themselves
   still show).
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
   And for `plex_url` and `plex_token` (Google TV only; their Plex server's newest titles join the picks): the token opens
   their Plex account, so suggest a Plex Home managed user's (the README says how), and it goes only in `config.properties`.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. If it hangs, Google Play Protect is asking on the TV (Android 14:
   *Risky app blocked*, "built for an older version of Android", since targetSdk is 33): they choose *More details* →
   *Install anyway* (updates don't ask again). On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   Some TCL firmware (a "Smart TV Pro", Realtek RTD2880, Android 11, build `RP1A200622.001`; the same TV as #264) also
   has a second, separate background killer, `TGuard` (`com.tcl.guard`), which kills the app's processes about a second
   after any other app comes to the front. The switch's benchmark opens the original home screen a few times (that's
   the launcher flashing by during "Measuring…"), so TGuard can kill `ModeJob` there (log: `TGuardMemoryManagerKillHandler
   ... tguard-kill`, then `Scheduling restart of crashed service io.freemytv/.ModeJob`). The JobScheduler runs the job
   again by itself within a minute or two, and a benchmark that fails only logs now (it used to read as "Couldn't turn on
   Free My TV: Read timed out", #277). Tell them: if the settings screen closes on the stock launcher, wait a minute or two, then press
   Home; Free My TV opening confirms the switch. No `adb`/`appops` setting turns TGuard off (its content providers aren't
   exported to the shell user).
   Chromecast with Google TV (Android 14) refuses port 5555 even with USB debugging on: have them turn on *Wireless
   debugging*, pair with `adb pair <address:port> <code>` (the code is on the TV), then run `adb tcpip 5555` over that
   connection. It closes again at every restart (Wireless debugging turns itself off; `persist.adb.tcp.port` is
   refused), and until `adb tcpip 5555` runs again *Original*, the watchdog and the boot check can't reach ADB. Tell
   them before the switch (#195). Google TV's voice results for films and shows come from its home app (launcherx),
   which Free My TV mode turns off; ADB can't turn off only its home activities, so tell them before they switch.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off. On TCL's Android 11 there's no
   service (the TV moved the volume twice with it), so there the buttons work only on Free My TV's home screen. On
   Xiaomi TVs whose remote has no Home button, the same service makes its Mi button open Free My TV instead of PatchWall.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   Before they switch, run `adb disconnect <IP>:5555` (connect again for step 9): on Android 8 to 10 the TV can hand
   its answer to a computer that's still connected (#221).
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Releases

Every release carries `MeloFreeTV.apk` (`Updates.ASSET`), built with `./build.sh --generic` (no config.properties: no
key, and `Place` takes the city, the currency and the region from the TV's time zone through `app/assets/zones.json`)
and signed with the project's key, `freemytv.keystore` (never committed). The release's APK updates itself (`Updates`:
a weekly job, and Ajustes do App > Atualização): it reads `releases/latest`, so keep the asset's name, sign it with the
same key, and raise `versionName` (compared number by number with the tag, `v1.1` > `1.0`) and `versionCode` (Android
refuses a lower one). Before uploading, `apksigner verify --print-certs MeloFreeTV.apk` must show the release
certificate (`375f866ebea425908e74fcbe1b6b0d35b7cd2a090780364e5f8a2a1309efb7ca`, `Updates.RELEASE_CERT`): TVs refuse
any other.
The generic APK has no accessibility service (`RemoteKeyService`) and no notification listener (`NowPlaying`):
`build.sh --generic` drops both from the manifest and fails if either permission is left (`Config.SENSITIVE_SERVICES`).
Google Play Protect's enhanced fraud protection (on in Brazil) blocks any app installed from Downloader or a browser that
declares either, with only an *Entendi* button and no *Install anyway* (tested on a Mi TV Stick, Android 9: with only
the accessibility service, still blocked). adb installs aren't checked that way, so builds made on a computer keep both.
In the generic APK the remote's buttons work only on our home screen (`RemoteKeys.SERVICE`), and Now Playing shows
Unavailable. Never add either back to it, nor `RECEIVE_SMS`/`READ_SMS`, the other permissions that trigger the block.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it. `mayTurnOff` also refuses
  Android's own pieces (`CORE`: with the permission controller or package installer off, a TV may not start again) and
  factory menus (`FACTORY`), and `neededByTv` keeps on, on the TV itself, anything that asks for `TV_INPUT_HARDWARE`
  (the layer under HDMI and the tuner: off, inputs show "No signal") or receives the remote's Input key
  (`fusion.android.tv.demo` does on several MediaTek boards).
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on
HDMI (Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.
The onn 4K Streaming Box (Amlogic S905Y4, Google TV 14 tested) ships no promo apps of its own, so `DEBLOAT` has no onn
entries; its system pieces are in `PROTECTED`. USB debugging opens ADB on 5555 there, as the app needs.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`. On an HY300 with JmLauncher, also
`adb shell setprop persist.sys.home.selectpkg ''`.

## The code

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 to 12L (API 30–32) run the screensaver as `ScreensaverAlone`, in the main process: AOSP starts their
  DreamActivity in the app's default process (13 moved it to the dream's own), and it only works with the dream in that
  process, which can't also hold a home activity (a Sony BRAVIA VH22 on Android 12 crashed with it in `:home`, #94).
  `res/values-v30` and `values-v31` pick it; Android 8 to 10 and 13 and newer (`values`, `values-v33`) keep
  `Screensaver` in `:home`. Keep both in step. `ScreensaverAlone` reads `Prefs` from another process than the settings screen's, so it calls `Prefs.reload()`
  when it starts (which also re-applies a new language; `HomeData` fetches the forecast again when the city changed).
  To check for newer APIs, compile against `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node`, `Ui.Weighted` (the Android 9 font weight)
  and `Activity.setTranslucent` (guarded: TCL's Android 11 marks the home activity translucent, and the app just left
  stayed on screen over it) should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- TMDB's API is reached at `api.tmdb.org`, TMDB's own mirror of `api.themoviedb.org` on the images' domain
  (`image.tmdb.org`): some ISPs block the other name (Jio in India, #327).
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. The sleep timer's overlay (`SleepOverlay`) runs alone in `:sleep`, so its foreground service doesn't keep
  the home screen in memory under the app in front; the remote's key service lives in `:keys` (below).
  `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
  `Updates` (the release APK updating itself) runs there too: the week's check and Settings' OK are `ModeJob`s, one at
  a time, and they write `update.json`, which Settings only reads. The download must be this repository's release
  asset (its size and SHA-256 as GitHub lists them), newer than the app (versionName and versionCode) and signed with
  the release key (`Updates.RELEASE_CERT`); Android's installer always asks. A build made on a computer (not
  `--generic`, or another key) checks only when asked and never downloads.
- The TMDB key given on the TV (`Prefs.tmdbKey`) wins over `Config.TMDB_KEY`; read it through `Recs.key()`. Its screen
  serves a one-time page (`KeyPage`: the TV's private IPv4 on Wi-Fi or Ethernet, never a VPN's or a public one, a random
  port and token, one POST, 5 minutes, and only while that screen shows) behind a QR code (`Qr`, byte mode, level M, after
  Project Nayuki's MIT encoder, whose notice stays in the file), and checks a key with TMDB (`/configuration`) before
  saving it. Never log it. Nothing may escape KeyPage's threads: `:home`'s watchdog counts it as a home screen crash.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German, Russian, Thai and Turkish are hand translations: the reference, edited by hand,
  never replaced with machine ones (`--fill` leaves them alone unless `--lang` names one). Dates and numbers use
  `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  Built with `--generic`, the place values are empty and `Place` fills them from the TV's time zone
  (`app/assets/zones.json`, written by `tools/make_zones.py`).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons, Sleep Timer) wins over those first values.
- Settings rows that need something this TV lacks stay, dimmed as *Unavailable*: `SettingsActivity.need` names what a
  row needs, `Capabilities.missing` says why it's missing (once per screen), and OK on the row shows it in a toast.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar), Sony, Xiaomi and Hisense TVs and Formovie projectors, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  The service is also off while every button is *Nothing*, and always on TCL's Android 11 (`RemoteKeys.FILTER_SAFE`):
  there any key filter makes the TV act on a volume key twice (one press moves 2 steps and a held key gets stuck, #325;
  turning off only our service fixed it), so the buttons work only where they reach our home screen's window.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
  Some Xiaomi TVs (MiTV-MSSP3, MediaTek, Android TV 9 tested) have no Home button on the remote (`RemoteKeys.XIAOMI`
  matches only the TVs, `MiTV-M…` models, not the Mi Box or Mi TV Stick): the Mi button sends
  `KEYCODE_BUTTON_4`, a global key that `com.xiaomi.android.tvsetup.partnercustomizer`'s `GlobalKeyReceiver` turns into
  PatchWall (`com.mitv.tvhome.atv`), never the home activity, so without the service there is no way back to Free My
  TV, and PatchWall's All Apps opens the disabled `tvlauncher`. `Prefs.KEY_MI` opens our home screen by default
  ("Xiaomi default", empty, leaves it to PatchWall). Leave PatchWall on: it also shows the TV's input picker.
  Xiaomi TVs with a Home button (MiTV-MOOR0, MediaTek, Android TV 11 tested) send `KEYCODE_BUTTON_3` for YouTube, a
  global key for the stock home screen (`launcherx`) that Free My TV turns off, and `KEYCODE_BUTTON_5` for PatchWall,
  a global key for the same `partnercustomizer` receiver: both are ours (`Prefs.KEY_XIAOMI_PATCHWALL` is left to the
  TV until an app is picked). Netflix (`BUTTON_6`) stays with the system. Their remote sends one press as a burst of 5
  to 8: `RemoteKeys` opens the app once (Xiaomi only).
  Xiaomi TVs sold in Russia (MiTV-MOOQ1, MediaTek, Android TV 11 tested) send `KEYCODE_BUTTON_13` for Okko, `_1` for
  VK, `_16` for Rutube, `_11` for KION, `_10` for Wink and `_12` for IVI, global keys for Xiaomi's hot-key receiver
  (`com.xiaomi.android.tvsetup.partnercustomizer`, its log: "dealWithHotkey keycode:199"). "Xiaomi default" (empty)
  leaves the key to the TV.
  Hisense (Q6N, MediaTek, Android TV 12 tested) sends `295` for Prime Video, `KEYCODE_BUTTON_3` for YouTube,
  `KEYCODE_ALL_APPS` for its All Apps (3×3) button, `KEYCODE_NOTIFICATION` for the gear and `KEYCODE_PROFILE_SWITCH`
  for the profile button, all global keys for Google TV's home app (`launcherx`), which Free My TV mode turns off, and
  `607` for Disney+: without the service they do nothing. Netflix (`KEYCODE_STEM_1`) stays with the system. The gear
  opens TV Settings and All Apps opens our own sheet (`Prefs.ALL_APPS`, `HomeActivity.EXTRA_ALL_APPS`); the profile
  button has nothing to open without `launcherx`. Hisense keeps only error logs: `adb shell setprop log.tag.FreeMyTV I`
  before reading `adb logcat -s FreeMyTV`.
  `adb shell input keyevent` doesn't test any of these: injected keys skip the accessibility key filter.
- What's playing (`NowPlaying`) comes from the apps' media sessions, which Android hands only to an allowed notification
  listener. A listener could read every notification, so ours follows Home Settings' *Now Playing widget* (off by
  default): turning it on allows it through ADB (`HomeMode.nowPlayingAccess`, `cmd notification allow_listener`; the
  `enabled_notification_listeners` setting grants nothing since Android 8) and proves it took (`Capabilities.LISTENER`,
  else the row shows Unavailable); turning it off, *Original* and the watchdog's revert take it back. The switch to
  Free My TV mode allows nothing. `HomeMode` sets it, under the `RUN` lock, to what the widget says at that moment (so
  quick toggles and a revert can't cross), `verifyNow` does the same after a boot or an update, and a screen that finds
  it on but not allowed asks once.
  It unbinds itself as soon as it connects (the permission is all it's for), and the home screen and the screensaver
  watch the sessions only while they show. The line is cut to fit when the title changes, never while drawing; the
  home screen's bars bounce again only for a new title (`Watch.title`), and TalkBack announces it then.
- HY300 projectors (Hotack, Allwinner H713, Android 11 tested; the build claims to be an `askey`/`adt3`): the stock
  home screen and screensaver are `com.htc.htclauncherhighenglishd08`, which also carries an ad SDK (`com.zeasn.ad`).
  Keystone, zoom and projection mode live in `com.htc.htcsettings` (`action.htcsettings.prjsettings`), and the Miracast
  receiver is `com.softwinner.miracastReceiver/.Miracast`: `HomeApps.drawerShortcuts` adds both (kind `DEVICE`) where
  they resolve. Opening Miracast moves the Wi-Fi to Wi-Fi Direct: adb drops and asks to be allowed again. Nothing opens
  a passthrough input there; Allwinner's `com.softwinner.awlivetv` does, but always on HDMI 1 (the one real port of
  the three the board lists), so `inputs` keeps only HDMI 1.
  Other HY300s (Amlogic txhd2, Android 13 tested; the build claims to be a Google `redfin`, model `t950s_be311`) have
  the same HTC home screen, but the Home button goes to JmLauncher (`com.jm.jmlauncher`), which ignores the default
  home: it opens the package in `persist.sys.home.selectpkg`, else the HTC one, and with that off and the property
  empty, a black screen. Their adb shell runs as root, so the switch sets the property (`HomeMode.JM_HOME`) and only
  then turns the HTC home off; Original and the watchdog put both back. Wireless debugging's port changes and turns
  itself off, but adb also listens on 5555 there. They carry `com.htc.htcsettings` too, but any change in its keystone
  or zoom crashes it (`SecurityException`: SurfaceFlinger doesn't know the Allwinner request code 1050): their own is
  `com.softwinner.tcorrection/.projection.ProjectionSettingActivity`, which the Keystone shortcut opens where it's
  installed.
- Xiaomi's Chinese firmware (MIUI TV; Redmi `MiTV-MFTP0`, Android 9 tested) has no passthrough handler either: its own
  `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity` (action `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY`) opens a port from
  an `input` extra (HDMI 1 to 3 are 23 to 25; there is no number for a fourth), so `inputs` keeps HDMI 1 to 3 there.
- MediaTek TVs that list one hardware tuner input per mode (Haier H32S80EFX, Google TV 14: `AnalogInputService/HW1`,
  `CableInputService/HW0`, `TunerInputService/HW0`, in no fixed order), or two of one name (a Cecotec on a Vestel base,
  Android TV 14: `TunerInputService/HW0` antenna, `/HW1` analog, HW1 listed first, #312): Live TV tunes the digital
  antenna's (`HomeApps.tunerRank`: by name, then the lowest HW number), then cable's, then analog's.
- TVs with Fire OS built in (Xiaomi Fire TV, MediaTek, Fire OS 8 tested): the passthrough request lands on Vewd's
  HbbTV player (`com.vewd.core.tvapp`) or on Amazon's Live TV, and both play the antenna's last channel (RaiPlay's)
  instead of the port. Amazon's input player wants `com.amazon.tv.permission.TUNE_INPUT` and its home screen's switch
  `LAUNCH_INPUTS` (signature only), but `com.amazon.tv.inputpreference.service/.KaminoInputSwitchService` takes an
  `inputType` extra, the port's hardware number (`.../HdmiInputService/HW2` is 2, HDMI 1), from any app
  (`HomeApps.fireInput`). Live TV opens `com.amazon.tv.livetv/.DttIconWrapperActivity`, the antenna's entry.
  A Fire OS TV restarts quietly into standby when turned off (QHB) once it booted from power; after a reboot from
  software (`adb reboot`) every power-off restarts it with the screen on, until it's unplugged for 30 seconds.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.
