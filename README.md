# AODControl

A lightweight Android app for controlling Always-On Display (AOD) opacity and screen-off fingerprint behavior through Shizuku.

## v1.2.0

AODControl has a **default phone state** plus optional automatic modes.


### Appearance

AODControl now has an in-app appearance page:

- **System / Light / Dark** appearance. Dark remains the default.
- **Dynamic color** is optional and follows Android Material You colors on Android 12+.
- With Dynamic color off, AODControl uses its own dark olive palette.
- **Pure black theme** is optional for AMOLED-friendly dark backgrounds.

### Default phone state
Choose one:

- **System default** — removes AODControl's dimming override and restores the captured system AOD/UDFPS settings.
- **AOFP** — enables AOD, requests screen-off UDFPS where supported, and makes the AOD fully black.
- **Manual opacity** — enables AOD and applies a custom 0–100% dimming scrim.

### Optional modes
Each mode can be independently enabled. Every enabled mode can use **System default**, **AOFP**, or its own **Manual opacity**.

- **Night mode** — editable start/end time, default 7:00 PM → 6:00 AM.
- **Navigation mode** — choose installed navigation/ride apps. The mode activates while a selected app is in the foreground.
- **Outdoor hours** — editable daytime schedule, default 8:00 AM → 6:00 PM.
- **Charging mode** — activates while plugged in / charging / full.

Priority when several modes are active:

`Charging → Navigation → Outdoor → Night → Default`

Automatic modes use a low-priority foreground service only while at least one mode is enabled. With all modes disabled, there is no background service.

## Requirements

- Android 8.0+ (`minSdk 26`)
- Shizuku v11+
- A device/SystemUI implementation that honors `always_on_display_constants` / `dimming_scrim_array`
- Screen-off fingerprint support depends on the device/OEM implementation

No root is required when Shizuku is started through Wireless Debugging/ADB.

## Privacy / security

- No internet permission.
- No fingerprint templates or biometric authentication data are accessed.
- Navigation mode only checks the foreground package name through the restricted Shizuku UserService.
- The Shizuku service exposes only the settings operations and foreground-app query used by AODControl; it is not a general shell bridge.

## Build

GitHub Actions builds a debug APK on every push to `main` / `master`.

Locally:

```bash
gradle :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

Some SystemUI implementations cache AOD opacity while an AOD session is already active. In that case a newly selected mode is guaranteed in settings immediately, while the visible AOD may refresh on the next AOD entry (for example the next lock/wake cycle).
