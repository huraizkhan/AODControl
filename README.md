# AODControl

A lightweight Android app for controlling Always-On Display visibility, screen-off dimming, and under-display fingerprint-triggered AOD visibility using **Shizuku**. No root is required.

> **Status:** first public test release. The screen-off path uses standard Android broadcasts. UDFPS touch triggering uses the ADB-shell-readable touchscreen event stream and therefore depends on the device/OEM allowing `getevent` to the shell identity.

## Modes

A fresh install starts with **no mode selected**. A **Restore system AOD defaults** action removes only AODControl's scrim override and returns the AOD enable setting to the system default.

### 1. Always-on Fingerprint

- Enables AOD/doze.
- Applies a fully opaque AOD dimming scrim (`255`).
- The display appears black while AOD remains technically active.
- On devices that support screen-off UDFPS authentication, fingerprint unlock can continue to work.
- **No app background service runs.**

### 2. Manual AOD Opacity

- Enables AOD/doze.
- Provides a `0–100%` opacity slider.
- `0%` = normal AOD visibility.
- `100%` = fully black AOD.
- **No app background service runs.**

### 3. Smart Dimming

Runs a low-activity foreground service only while this mode is selected.

Controls:

- **AOD visible time:** 1–60 seconds.
- **Dimming start delay:** 1 second up to the selected visible time.
- **Opacity mode:**
  - **Auto:** starts with the device/SystemUI AOD ambient brightness and scrim behavior, then fades toward black.
  - **Manual:** choose start and end opacity.
- **Use same settings for Screen Off & Fingerprint:**
  - Checked: one shared profile.
  - Unchecked: separate Screen Off and Fingerprint profiles.

Example: `Visible = 20 s`, `Delay = 10 s` means the normal/start AOD state is held for 10 seconds, then it fades during seconds 10–20 and reaches its final opacity at 20 seconds.

## Fingerprint trigger

Smart Dimming includes a one-time **UDFPS area calibration**.

The calibration stores only normalized screen coordinates and an approximate detection radius. It does **not** read fingerprint templates, biometric matching data, or authentication results.

While the screen is non-interactive/AOD:

1. Shizuku's shell identity watches the touchscreen input event stream with `/system/bin/getevent`.
2. A touch inside the calibrated UDFPS area triggers the Fingerprint dimming profile.
3. A registered fingerprint is still authenticated entirely by Android/SystemUI and unlocks normally.
4. An unregistered fingerprint leaves the device locked; AOD follows the configured timer and returns to the final opacity.
5. Another touch restarts the timer.

If the OEM blocks shell access to touchscreen events, the app reports the fingerprint trigger as unavailable. **Screen-off Smart Dimming still works.** Side/rear fingerprint sensors are not currently detected by this input-coordinate method.

## Auto opacity behavior

AODControl does not implement its own light-sensor brightness algorithm.

At the beginning of an Auto trigger it removes only the `dimming_scrim_array` override from `always_on_display_constants`, allowing SystemUI to use the device's normal AOD brightness/scrim resources. When the fade begins, AODControl reads the device's overlaid `config_doze_brightness_sensor_to_scrim_opacity` resource when available and progressively moves its buckets toward `255` (black).

This leaves the normal AOD brightness curve under Android/SystemUI control.

## Shizuku

The app uses Shizuku's **UserService** API. On a non-rooted phone, Shizuku provides ADB shell identity after it has been started through Wireless Debugging/ADB.

The privileged UserService is intentionally restricted. It exposes only:

- `secure/doze_always_on`
- `global/always_on_display_constants`
- AOD scrim resource lookup
- Read-only touchscreen event monitoring for calibrated UDFPS triggering

There is no general arbitrary-shell-command interface exposed by AODControl.

## Compatibility

- Minimum Android version: **Android 8.0 (API 26)**.
- Shizuku v11+ required; current Shizuku is recommended.
- Core AOD controls require an AOSP-style SystemUI implementation that honors `always_on_display_constants` / `dimming_scrim_array`.
- Screen-off Smart Dimming is the most portable trigger.
- UDFPS Smart Dimming requires a touchscreen device readable by ADB shell and calibration.
- Devices without AOD can install the app, but AOD functions naturally cannot work.

OEMs heavily customize AOD and fingerprint behavior, so compatibility reports are welcome.

## Building

### GitHub Actions

Every push to `main` or `master` runs `.github/workflows/android.yml` and uploads:

`AODControl-debug` → `app-debug.apk`

Open the repository's **Actions** tab, open the latest successful **Android Build**, and download the artifact.

### Android Studio

Open the repository root as an Android Studio project and build the `app` module.

### Command line

The project uses Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, and compile/target SDK 35.

```bash
./gradlew :app:assembleDebug
```

If `gradle-wrapper.jar` is not present yet, the included launcher downloads the official Gradle 8.9 wrapper JAR on first command-line use.

## Initial Termux push

Assuming this GitHub repository already exists and is empty:

```bash
cd ~
git clone https://github.com/huraizkhan/AODControl.git
```

Extract the provided source archive **into `~`**, so its top-level `AODControl` folder merges with the cloned repository, then:

```bash
cd ~/AODControl
git add .
git commit -m "Initial AODControl release"
git push origin main
```

## Important notes

- Smart Dimming uses a foreground service and therefore has an ongoing notification while active.
- Manual AOD Opacity and Always-on Fingerprint do not run a background service.
- Non-root Shizuku availability depends on Shizuku being started. The app reconnects when its binder becomes available.
- This app does not bypass Android biometric authentication. It only changes AOD visibility and observes touchscreen coordinates around a user-calibrated UDFPS area.

## License

MIT
