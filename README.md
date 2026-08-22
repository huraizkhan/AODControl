# AODControl

A lightweight Android app for native AOD opacity/AOFP control plus an optional Universal AOD fallback. Uses Shizuku for restricted system-setting operations; no root required.

## v1.3.0

### Native AOD controls

The existing working engine is unchanged:

- **System default** — remove AODControl's dimming override and restore the captured system AOD/UDFPS settings.
- **AOFP** — keep native AOD/Doze active, make the AOD visually black, and preserve screen-off fingerprint where the device supports it.
- **Manual opacity** — native AOD with a custom 0–100% dimming scrim.

Automatic modes remain:

`Charging → Navigation → Outdoor → Night → Default`

### Universal AOD fallback

Universal AOD is optional and lives under **Advanced settings → Universal AOD**.

It is designed for three cases:

1. **Continuous native AOD** — AODControl leaves it alone. The fallback remains idle while Android reports the built-in display in DOZE/DOZE_SUSPEND.
2. **Time-limited native AOD** — AODControl waits for the OEM AOD to finish. If the display later reaches true `STATE_OFF`, the custom AOD can take over.
3. **No native AOD** — once the locked display reaches `STATE_OFF`, the custom AOD can start immediately.

Custom AOD currently shows a minimal clock, date and battery level on black, with small position shifts for OLED image-retention protection.

Modes:

- **Temporary** — 5–60 seconds, default 10 seconds, then requests real screen-off again through the restricted Shizuku service.
- **Continuous** — stays visible until Android/user interaction ends the custom AOD session.

Custom AOD brightness is adjustable from 1–20%.

### LCD safety

AOD on LCD is blocked by default because black pixels still require the LCD backlight.

- **Allow AOD on LCD** must be explicitly enabled.
- Temporary mode is recommended for LCD.
- If automatic display-tech detection cannot identify the panel, AODControl treats it conservatively like a possible LCD until the user allows LCD AOD or manually selects OLED.

Display technology can be set to **Auto / OLED / LCD**. Auto uses read-only panel/property hints through the restricted Shizuku UserService where available; it does not use a hardcoded device-model list.

### Appearance

- System / Light / Dark
- Dark olive palette by default
- Optional Material You dynamic color
- Optional pure-black dark theme

## Permissions / services

- Shizuku: native AOD controls, read-only compatibility hints, and exact sleep request after Temporary AOD.
- Display over other apps: required only for Universal AOD fallback so Android can permit the custom lock-screen AOD to be started from the background.
- A low-priority foreground service runs only while Universal AOD is enabled and allowed.
- The existing automation foreground service still runs only when one or more automatic modes are enabled.

## Requirements

- Android 8.0+ (`minSdk 26`)
- Shizuku v11+ for native controls and exact Temporary AOD sleep behavior
- No root

## Privacy / security

- No internet permission.
- No fingerprint templates or biometric authentication data are accessed.
- Navigation mode checks only the foreground package name.
- The Shizuku UserService is restricted; it does not expose a general arbitrary-shell interface.

### Universal AOD custom layout

When the Universal AOD fallback takes over, AODControl now draws its own full-screen black overlay rather than only waking the OEM lock screen. The first layout includes clock, date, battery percentage and burn-in position shifting. More layout styles and notification content can be added independently later.

## Build

GitHub Actions builds a debug APK on every push to `main` / `master`.

```bash
gradle :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Compatibility note

OEM lock screens and background-start policies vary. Universal AOD deliberately uses capability/fallback behavior rather than assuming every manufacturer implements AOD the same way. The first physical-device testing phase should verify whether each OEM permits the custom lock-screen activity and reports native AOD as DOZE before its time limit expires.


## v1.3.2 lock-screen compatibility
Custom AOD now prefers a lock-screen Activity launched by the restricted Shizuku shell service, with the old application overlay kept as fallback.

## Lock-screen compatibility fallback

On OEMs that keep ordinary activities or app overlays behind the keyguard, Universal AOD can use the optional **AODControl lock-screen compatibility** Accessibility Service. It only creates a `TYPE_ACCESSIBILITY_OVERLAY` for the custom AOD and does not read accessibility events or window content.
