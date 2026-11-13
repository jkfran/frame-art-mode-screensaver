# Google TV setup with ADB

Google TV's Settings offer only Google's Ambient mode as the screensaver. A third-party screensaver such as Frame Art Mode is selected with ADB, once. These steps apply to the Google TV Streamer, the Chromecast with Google TV and other Google TV devices.

On Android TV devices whose Settings > Device Preferences > Screen saver lists Frame Art Mode (for example NVIDIA SHIELD TV), select it there instead; ADB is not needed.

| | |
|---|---|
| Package | `io.github.jkfran.frameartmode` |
| Screensaver service | `io.github.jkfran.frameartmode/.ArtModeDreamService` |

## What you need

- A computer with ADB, from Google's [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools), on the same network as the streamer. Any other ADB client works too, including ADB apps for phones.
- The APK, `frame-art-mode-screensaver-<version>.apk`, from the [latest release](https://github.com/jkfran/frame-art-mode-screensaver/releases/latest).

## 1. Turn on developer options

On the streamer: Settings > System > About, select **Android TV OS build** seven times, until it says that you are now a developer.

## 2. Turn on debugging

Settings > System > Developer options:

- Turn on **USB debugging**. On many Google TV devices this also accepts ADB connections over the network, on port 5555.
- If the device has **Wireless debugging** instead, turn it on and use the pairing step below.

Find the streamer's IP address under Settings > Network & Internet, on the connected network.

## 3. Connect

```sh
adb connect <streamer-ip>:5555
```

The streamer shows **Allow USB debugging?** Tick **Always allow from this computer** and choose OK. `adb devices` then lists the streamer as `device`.

With Wireless debugging, pair first with the code and port the streamer shows, then connect to the port it shows for connections:

```sh
adb pair <streamer-ip>:<pairing-port>
adb connect <streamer-ip>:<port>
```

## 4. Install

```sh
adb install frame-art-mode-screensaver-<version>.apk
```

For a later version, use `adb install -r` with the new APK; the settings are kept.

## 5. Note the current settings

Write these values down; they are what [Undo](#undo) restores:

```sh
adb shell settings get secure screensaver_components
adb shell settings get system screen_off_timeout
```

On Google TV the first one is normally `com.google.android.apps.tv.dreamx/.service.Backdrop`, Google's Ambient mode.

## 6. Select the screensaver

### Option A: let the app do it (recommended)

```sh
adb shell pm grant io.github.jkfran.frameartmode android.permission.WRITE_SECURE_SETTINGS
```

This lets the app write the system's screensaver settings. It uses the permission only for **Set as screensaver** and **Screensaver delay**. The grant lasts until the app is uninstalled or the permission is revoked.

Then open Frame Art Mode on the streamer:

1. Choose the TV (**Find TVs**, or type its IP address) and try **Test now: switch to Art Mode**.
2. Select **Set as screensaver**.
3. Select **Screensaver delay** and pick 1, 2, 5, 10, 15 or 30 minutes, or 1 hour. Only delays shorter than the time after which the streamer goes to sleep are listed: with a longer one the screensaver never starts.

### Option B: set it from the computer

```sh
adb shell settings put secure screensaver_components io.github.jkfran.frameartmode/.ArtModeDreamService
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_activate_on_sleep 1
adb shell settings put system screen_off_timeout 600000
```

| Setting | Meaning |
|---|---|
| `secure screensaver_components` | The screensaver Android starts. |
| `secure screensaver_enabled` | `1` turns screensavers on. |
| `secure screensaver_activate_on_sleep` | `1` starts the screensaver when the idle time runs out, instead of going straight to sleep. |
| `system screen_off_timeout` | Idle time before the screensaver, in milliseconds: `60000` (1 minute), `300000` (5), `600000` (10), `900000` (15), `1800000` (30), `3600000` (1 hour). |

These are the same commands the app shows under **Set as screensaver** when it has no permission. Then choose the TV in the app as in option A, step 1.

## 7. Check

```sh
adb shell settings get secure screensaver_components
```

prints `io.github.jkfran.frameartmode/.ArtModeDreamService`, and the app's Screensaver section says that Frame Art Mode is this device's screensaver and after how long it starts.

To try it, set the delay to 1 minute, show the streamer on the TV and leave the remote alone. The TV switches to Art Mode and the streamer goes to sleep; the app's **Activity** list records the attempt. Then set the delay you want.

## 8. Turn debugging off (optional)

Settings > System > Developer options > USB debugging (or Wireless debugging): off. The screensaver settings stay. If **Set as screensaver** later shows the ADB commands again, the permission grant is gone; repeat step 6.

## Undo

Put back the values noted in step 5. On Google TV, Google's Ambient mode is:

```sh
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
adb shell settings put system screen_off_timeout <noted-value>
```

Then, if wanted, revoke the permission or remove the app:

```sh
adb shell pm revoke io.github.jkfran.frameartmode android.permission.WRITE_SECURE_SETTINGS
adb uninstall io.github.jkfran.frameartmode
```

## Optional: HDMI-CEC settings that matter

Frame Art Mode does not read or change these settings. They decide what the streamer tells the TV over HDMI-CEC, and are available on Android 12 and later. Read them with:

```sh
adb shell cmd hdmi_control cec_setting get power_control_mode
adb shell cmd hdmi_control cec_setting get power_state_change_on_active_source_lost
```

Change one, where the device allows it, with `adb shell cmd hdmi_control cec_setting set <name> <value>`.

### `power_control_mode`

What the streamer sends when it goes to sleep **by itself** while it is the TV's active source:

| Value | The streamer sends | The Frame |
|---|---|---|
| `to_tv` | `<Standby>` to the TV | Turns off, also from Art Mode. |
| `to_tv_and_audio_system` | `<Standby>` to the TV and the audio system | Turns off, also from Art Mode. |
| `broadcast` | `<Standby>` to all devices (the AOSP default; devices may ship another) | Turns off, also from Art Mode. |
| `none` | `<Inactive Source>` | In TV mode: switches to the previous HDMI input, or shows "Check device power" if that device is off. In Art Mode: stays in Art Mode. With `none`, AOSP also sends no One Touch Play when the streamer wakes, so the TV does not switch to it by itself. |

When Frame Art Mode has switched the TV to Art Mode, the TV puts the streamer to sleep. That sleep comes from HDMI-CEC, so the streamer sends only `<Inactive Source>`, whatever this setting says, and The Frame stays in Art Mode.

The setting matters when the streamer goes to sleep on its own: when Art Mode was not started (for example while the Art Store terms are not accepted), when HDMI-CEC is off on the TV so that it cannot put the streamer to sleep, or with **End the screensaver after Art Mode starts**. Keep the device's default, unless the streamer regularly goes to sleep by itself after Art Mode has started; `none` then keeps The Frame in Art Mode.

### `power_state_change_on_active_source_lost`

What the streamer does when the TV switches to another input:

| Value | Effect |
|---|---|
| `standby_now` | The streamer goes to sleep shortly after the TV switches away from it (Android may show a notice first). |
| `none` | The streamer stays awake. Its screensaver then starts after the delay, and Frame Art Mode switches the TV to Art Mode while the other input is in use. |

With Frame Art Mode, `standby_now` is the setting to use: the app cannot tell which input the TV is showing.
