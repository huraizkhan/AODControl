# AODControl

A lightweight Android app for native AOD opacity/AOFP control and optional AOD gestures. Uses Shizuku; no root required.

## v1.4.0

### Native AOD controls

- **System default** — remove AODControl's dimming override and restore the captured system AOD/UDFPS settings.
- **AOFP** — keep native AOD/Doze active, make AOD visually black, and preserve screen-off fingerprint where the device supports it.
- **Manual opacity** — native AOD with a custom 0–100% dimming scrim.

Automatic-mode priority remains:

`Charging → Navigation → Outdoor → Night → Default`

### AOD gestures

The custom/Universal AOD fallback experiment has been removed.

AODControl can now observe native-AOD touchscreen input through its restricted Shizuku UserService and map gestures to actions. Monitoring runs only when **AOD gestures** is enabled and at least one gesture has an action.

Supported gestures:

- Double tap
- Triple tap
- Swipe left → right
- Swipe right → left
- Swipe up
- Swipe down
- Left-edge vertical slide
- Right-edge vertical slide

Available actions:

- Torch toggle
- Play / pause
- Next track
- Previous track
- Volume up
- Volume down
- Wake screen
- **Volume slider** for left/right edge slides (slide up = louder, down = quieter)

Gesture actions are executed only while Android reports the display as non-interactive, which covers native AOD / screen-off states. Normal screen-on touch is ignored.

The touch monitor is read-only. It does not inject or consume touch events.

### Compatibility

AOD gesture support depends on the device touchscreen continuing to emit input events during native AOD and on ADB/Shizuku shell being allowed to read that touchscreen device. The app includes **Check AOD touch input** so unsupported devices fail cleanly.

No manual touchscreen calibration is required: the restricted shell service reads the touchscreen's reported coordinate range.

### Appearance

- System / Light / Dark
- Dark olive palette by default
- Optional Material You dynamic color
- Optional pure-black dark theme

## Permissions / services

- **Shizuku** — native AOD settings, foreground-package detection, read-only AOD touchscreen observation, and optional wake-screen action.
- **Camera** — requested only if Torch is assigned to a gesture.
- **Foreground service** — AOD gesture monitor runs only while gestures are enabled and configured.
- Existing automatic-mode service still runs only when one or more automatic modes are enabled.

## Requirements

- Android 8.0+ (`minSdk 26`)
- Shizuku v11+
- No root

## Privacy / security

- No internet permission.
- No fingerprint templates or biometric authentication data are accessed.
- No accessibility service is used.
- No custom lock-screen/AOD overlay is used.
- Navigation mode checks only the foreground package name.
- The Shizuku UserService remains restricted and does not expose arbitrary shell commands.

## Build

GitHub Actions builds a debug APK on every push to `main` / `master`.

```bash
gradle :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```


## v1.4.1 gesture refinements

- Adds a visual gesture-zone preview.
- Splits left/right edge slides into separate up and down gestures.
- Makes horizontal swipe detection more forgiving.
- Sends play/pause/next/previous media keys through the restricted Shizuku shell path first, with AudioManager fallback.
- Adds Wake AOD and Sleep AOD actions. These change the native AOD scrim between visible and fully black without intentionally waking the lock screen.

## v1.4.2 gesture and live-AOD refresh

- Gesture active height, edge width and sensitivity are configurable, and the preview follows the selected zones.
- Assigning Volume slider to one edge direction reserves both directions on that physical edge.
- Wake AOD / Sleep AOD now refresh the native doze session after changing the scrim, without intentionally waking the full lock screen.
- Automatic mode transitions, especially charger connect/disconnect, are re-evaluated immediately and refresh an already-running native AOD so the new opacity becomes visible promptly.

## v1.4.3 live native-AOD refresh

Wake AOD, Sleep AOD, and automatic-mode transitions now rebuild the active native Doze/AOD session with a very short Shizuku power-state pulse after changing the scrim. This is intended for devices such as Pixel where SystemUI caches the scrim for the lifetime of the current Doze session.

## v1.4.4

- Added **Toggle AOD** gesture action (visible native AOD ↔ AOFP blank AOD).
- Default background engine is now a persistent Shizuku UserService, so enabled gestures and automatic AOD modes do not require a permanent AODControl notification.
- General → Background engine includes **Foreground fallback** for OEMs that do not keep the Shizuku UserService alive. Fallback mode uses one compact **AODControl active** notification.


## v1.4.5 gesture scope
AOD gestures can now be limited to AOD only, the visible lock screen only, or both. Unlocked-screen input remains ignored.


## v1.4.6
- New installs follow the system light/dark appearance by default.
- Optional AOD gesture keep-alive periodically reopens the Shizuku touch monitor while AOD is active to reduce low-power touchscreen timeouts. This may increase battery usage while AOD is active.
- Pocket protection is enabled by default and ignores gestures while Android reports the proximity/pocket state as covered.


## v1.4.7 corrective gesture rollback
Restores the exact v1.4.5 Shizuku touch engine after the v1.4.6 keep-alive/pocket changes caused gesture input to stop. New installs still follow the system light/dark theme by default. Keep-alive and pocket protection are temporarily removed until they can be reintroduced without changing the proven gesture transport. The Shizuku UserService version is bumped so the corrected daemon is recreated on update.

## v1.4.8 pocket protection
- Restores pocket/mistouch prevention without changing the stable v1.4.7 Shizuku AIDL gesture interface.
- Pocket protection is enabled by default and checks Android's current proximity state only after a completed AOD/lock-screen touch.
- No AOD keep-alive changes are included in this build.
