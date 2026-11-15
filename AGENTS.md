# Notes for coding agents

How to build Quiet and install it on a phone connected over adb, safely. Quiet is a **home app**: if a
broken build becomes the default launcher, the phone has no working home screen and may restart.
Always launch-test a new build before making it the home app.

## 0. Prerequisites

- JDK 17+ (`JAVA_HOME` set), Android SDK with `platform-tools` on `PATH`.
- USB debugging on, phone connected and authorised. Check:

```bash
adb devices -l
```

With several devices, add `-s <serial>` to every `adb` command below.

On Git Bash (Windows), set `export MSYS_NO_PATHCONV=1` first, or paths like `/sdcard` get rewritten.

## 1. Build and test

Never install from a tree that doesn't build. Run the unit tests and the release build together:

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk` (signed with the debug key, so it installs over
earlier debug or release builds without losing data).

## 2. Install safely (the phone keeps a working home screen)

Find another launcher on the phone to use as a temporary fallback:

```bash
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Make the fallback the home app while Quiet is replaced (example: Xiaomi's launcher; use whatever the list shows):

```bash
FALLBACK=com.mi.android.globallauncher/com.miui.home.launcher.Launcher
adb shell cmd package set-home-activity --user 0 $FALLBACK
adb shell cmd role add-role-holder --user 0 android.app.role.HOME ${FALLBACK%%/*} 0
```

Install, then start Quiet three times and look for crashes:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c
for i in 1 2 3; do
  adb shell am force-stop app.quiet.launcher
  adb shell am start -W -n app.quiet.launcher/.MainActivity | grep Status
  sleep 4
done
adb logcat -d -b crash | grep -c FATAL   # must print 0
```

Only if every start said `Status: ok` and there were 0 crashes, make Quiet the home app again:

```bash
adb shell cmd package set-home-activity --user 0 app.quiet.launcher/.MainActivity
adb shell cmd role add-role-holder --user 0 android.app.role.HOME app.quiet.launcher 0
adb shell input keyevent KEYCODE_HOME
adb shell cmd role get-role-holders android.app.role.HOME   # should print app.quiet.launcher
```

If anything failed, leave the fallback as home and fix the build first.

## 3. Grant permissions

All of these are for Quiet's own features and can be undone in the phone's Settings.

```bash
P=app.quiet.launcher
adb shell appops set $P GET_USAGE_STATS allow                                   # screen time, reminders
adb shell cmd notification allow_listener $P/$P.feature.media.MediaListenerService   # now playing, notification filter
adb shell appops set $P SYSTEM_ALERT_WINDOW allow                               # floating bar, pauses, lock screen
adb shell appops set $P WRITE_SETTINGS allow                                    # 10 s timeout on Quiet's lock screen
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell pm grant $P android.permission.WRITE_SECURE_SETTINGS                  # monochrome mode
adb shell dumpsys deviceidle whitelist +$P                                      # keep running in the background
```

Precision mode (accessibility). **Append** to the existing list instead of replacing it, so other
accessibility services keep working:

```bash
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW=$P/$P.feature.focus.QuietAccessibilityService
case "$CUR" in null|"") V=$NEW;; *"$NEW"*) V=$CUR;; *) V="$CUR:$NEW";; esac
adb shell settings put secure enabled_accessibility_services "$V"
adb shell settings put secure accessibility_enabled 1
```

On some phones an app update switches the accessibility service off. Re-run the block above after
every install and check it with `adb shell dumpsys accessibility | grep -A1 "Bound services"`.

Xiaomi only (these are MIUI app-ops: 10008 autostart, 10020 show on lock screen, 10021 pop-ups from background):

```bash
adb shell appops set $P 10008 allow
adb shell appops set $P 10020 allow
adb shell appops set $P 10021 allow
```

## 4. Set-up commands

Quiet listens for set-up broadcasts that only adb (or the system) can send: the receiver is protected by
`android.permission.DUMP`.

```bash
# Add apps to Home by their label, in order
adb shell am broadcast -a app.quiet.launcher.SHELL -n app.quiet.launcher/.service.ShellCommandReceiver \
  --es cmd favourites --es labels "WhatsApp,Gmail,Maps"

# Notification filter: turn on and allow these packages
adb shell am broadcast -a app.quiet.launcher.SHELL -n app.quiet.launcher/.service.ShellCommandReceiver \
  --es cmd notif_setup --es allow "com.whatsapp,com.google.android.gm" --ez enable true

# Focus: reminders for these apps, close-and-block for 2 min, two daily schedules, short-video blocking
adb shell "am broadcast -a app.quiet.launcher.SHELL -n app.quiet.launcher/.service.ShellCommandReceiver \
  --es cmd focus_import --es reminders 'YouTube,Chess' --es behaviour EXIT_AND_BLOCK --ei blockMinutes 2 \
  --ez floating true --ez launchDelay true \
  --es schedules 'Morning calm|06:30|12:00|YouTube;Chess#Evening|22:15|01:00|YouTube' --ez shortVideo true"
```

`behaviour` is one of `ONLY_REMIND`, `EXIT_AND_BLOCK`, `COOLDOWN`. Schedules apply every day; times are 24-hour.
Check results with `adb logcat -d | grep " Quiet "`.

## 5. Things not to do

- **Don't** install an untested build while Quiet is the home app (step 2 exists for a reason).
- **Don't** run `adb shell settings put global force_fsg_nav_bar 1` on Xiaomi/HyperOS. It shows the gesture line but the
  gestures stop working with a third-party launcher, and the user can get stuck inside an app.
- **Don't** run `gradlew --stop`; it stops every Gradle daemon on the machine, including other projects' builds.
- **Don't** commit screenshots taken from someone's phone; they contain their apps, music and notifications.

## 6. Undo

```bash
adb shell cmd role add-role-holder --user 0 android.app.role.HOME <previous.launcher.package> 0
adb uninstall app.quiet.launcher   # removes Quiet and its data
```
