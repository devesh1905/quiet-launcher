# Device setup

Quiet works on Android 8.0+ (shaders need Android 13+; older phones get a simpler fallback). Phone makers
move settings around, and names change between versions. If a path below doesn't match your phone,
search Settings for the bold word.

## Every phone

1. **Make Quiet the home app.** Settings → Apps → Default apps → **Home app** → Quiet.
2. **Allow restricted settings** (Android 13+, if you installed the APK from a browser, file manager or Taildrop):
   Settings → Apps → Quiet → ⋮ (top right) → **Allow restricted settings**. Without this, Android greys out
   notification access for Quiet.
3. **Grant permissions from Quiet itself:** Quiet → Settings → **Permissions**. Each row opens the right system screen.
4. **Keep it running:** set Quiet's battery usage to **Unrestricted** (wording differs per maker, see below), so reminders,
   blocking and the lock screen aren't killed in the background.
5. **Pick a lock-screen mode** in Quiet → Settings → Lock screen and art:
   - **Art on the system lock screen**: your phone keeps its own clock, notifications and fingerprint unlock; Quiet's art is the wallpaper.
   - **Quiet lock screen on top**: Quiet's own lock screen with the music player. Swipe up or touch the ring to reach the system unlock.
   Calls always take over Quiet's lock screen.

## Google Pixel and stock Android

- Home app: Settings → Apps → **Default apps** → Home app.
- Gestures: full-screen gestures work with Quiet. The recents screen may still use the Pixel launcher's animation; that's Android, not Quiet.
- Battery: Settings → Apps → Quiet → **App battery usage** → Unrestricted.

## Samsung (One UI)

- Home app: Settings → Apps → **Choose default apps** → Home app → Quiet.
- Gestures: Settings → Display → **Navigation bar** → Swipe gestures. Works with third-party launchers.
- Battery: Settings → Battery → **Background usage limits** → Never auto sleeping apps → add Quiet. Also make sure Quiet isn't in "Sleeping apps".
- Lock screen: if you use Good Lock / Lock Star or a dynamic lock-screen service, turn it off, or it will replace Quiet's lock wallpaper.

## Motorola (My UX)

- Home app: Settings → Apps → **Default apps** → Home app.
- Gestures: Settings → System → Gestures → **System navigation** → Gesture navigation. Works with Quiet.
- Battery: Settings → Apps → Quiet → **App battery usage** → Unrestricted.
- Moto's "Peek display" / "Moto Display" still shows over Quiet's lock wallpaper; that's fine.

## OnePlus, Oppo and Realme (OxygenOS / ColorOS / Realme UI)

- Home app: Settings → Apps → **Default apps** → Home screen app (Realme: Settings → Home screen → Default launcher).
- Gestures: Settings → System settings → **System navigation** → Gestures. Most recent versions allow gestures with third-party launchers; if yours shows a warning or gestures misbehave, use buttons.
- Battery: Settings → Apps → Quiet → **Battery usage** → allow background activity, and turn on **Auto launch** / Allow auto launch.
- Lock screen: turn off "Lock screen magazine" / "Glance" if it replaces the wallpaper.

## Vivo and iQOO (Funtouch OS / OriginOS)

- Home app: Settings → Apps → **Default apps** → Launcher (sometimes Settings → Apps & permissions → Default app settings).
- Gestures: many versions limit gestures with third-party launchers; use buttons if gestures don't respond.
- Background: i Manager → App management → **Autostart** → Quiet on; Settings → Battery → **Background power consumption management** → Quiet → Allow.
- Lock screen: turn off "Lock screen wallpaper carousel" if it overrides the art.

## Nothing (Nothing OS)

- Home app: Settings → Apps → **Default apps** → Home app.
- Gestures: work with Quiet.
- Battery: Settings → Apps → Quiet → **Battery** → Unrestricted.

## Xiaomi, Redmi and POCO (MIUI / HyperOS)

- Home app: Settings → Apps → **Manage apps** → ⋮ → Default apps → Home screen (or search Settings for "Default launcher").
- **Gestures: HyperOS only allows full-screen gestures with Xiaomi's own launcher.** Use 3-button navigation with Quiet. Don't use the
  `force_fsg_nav_bar` adb trick: on HyperOS 3 it shows the gesture line but the gestures stop working, which can leave you stuck in an app.
- Autostart and pop-ups: Settings → Apps → Quiet → **Autostart** on; **Other permissions** → "Show on Lock screen" and
  "Display pop-up windows while running in the background" → Allow. (Quiet → Settings → Permissions has a shortcut.)
- Battery: Settings → Apps → Quiet → **Battery saver** → No restrictions.
- Lock screen: turn off **Glance / Wallpaper Carousel**, or it replaces Quiet's lock wallpaper.
- Fingerprint: the in-display sensor works on the system lock screen. On Quiet's own lock screen, the ring over the sensor opens the system unlock.

## Monochrome (all phones)

Grayscale needs one permission that only a computer can grant. With USB debugging on:

```bash
adb shell pm grant app.quiet.launcher android.permission.WRITE_SECURE_SETTINGS
```

## Going back

Settings → Apps → Default apps → Home app → pick your previous launcher. Quiet keeps your settings if you switch back later.
