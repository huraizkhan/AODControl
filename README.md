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
