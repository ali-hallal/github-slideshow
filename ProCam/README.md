# ProCam 📸 — a pro camera for Android

A native Android camera app (Kotlin + Camera2, no third-party dependencies) that gives you
DSLR-style manual control over your phone's camera.

## Features

| | |
|---|---|
| **Zoom & lenses** | Pinch to zoom, plus lens buttons built from your phone's real lenses (e.g. **0.5 · 1× · 3 · 5**). On Android 11+ ProCam uses the phone's full zoom range and the phone switches between ultra-wide, main and telephoto automatically. |
| **PRO mode** | Manual ISO and shutter (1/16000 s – 30 s, whatever the sensor supports) in photographic stops. Slide either dial fully left for **A**uto → **shutter priority** (ISO auto) or **ISO priority** (shutter auto). |
| **Exposure meter** | −3…+3 EV meter in PRO mode shows how far your settings are from a correct exposure. |
| **AUTO mode** | Auto exposure with ±EV compensation. |
| **Manual focus + peaking** | Slide from macro to ∞ with a distance readout; sharp edges glow red (focus peaking). |
| **Zebra stripes** | Stripes over blown-out highlights (95 % or 100 %). |
| **Tap / hold** | Tap to focus and meter on a spot. Long-press for **AE/AF lock**. Tap the yellow badge to go back to continuous AF. |
| **White balance presets** | Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Warm Fluorescent, Twilight. |
| **RAW (DNG)** | RAW + JPEG/HEIF on cameras that support it. |
| **Live histogram & readout** | RGB + luminance histogram; real ISO, shutter, aperture and zoom. |
| **Composition aids** | Rule-of-thirds grid and electronic level. |
| **Flash, timer, keys** | Off / Auto / On / Torch flash with pre-flash metering; 3 s / 10 s timer; volume keys as shutter or zoom. |
| **Settings (remembered)** | HEIF, 4:3 / 16:9, max or 12 MP, quality, mirrored selfies, geotagging, grid, level, peaking, zebra, shutter sound, haptics. Everything you set is remembered between launches. |

Photos are saved to `Pictures/ProCam` in your gallery. Tap the thumbnail to open the last shot.

> Manual ISO/shutter/focus and RAW need a phone whose camera reports the `MANUAL_SENSOR` /
> `RAW` capability (most mid- and high-end phones from the last several years). On other
> phones those controls are greyed out and AUTO mode still works.

## Requirements

- Android 10 (API 29) or newer
- **Camera** permission (saving uses MediaStore, so no storage permission is needed); **Location** only if you turn on geotagging

## Build & install

**Android Studio:** open the `ProCam` folder and press ▶ Run.

**Command line** (Android SDK installed, `ANDROID_HOME` set):

```bash
cd ProCam
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**No SDK? Use GitHub Actions:** every push that touches `ProCam/` runs the
*ProCam Android build* workflow. Download the `ProCam-debug-apk` artifact from the run and
install it on your phone (allow "install unknown apps").

## Code tour

| File | What it does |
|---|---|
| `CameraController.kt` | Camera2 engine on a background thread: session setup, manual/auto request building, tap-to-focus, flash pre-capture, JPEG + DNG capture. |
| `MainActivity.kt` | UI: controls, slider, modes, timer, level sensor, histogram sampling, volume-key shutter. |
| `ShotSettings.kt` | Settings model, camera capabilities (incl. lens detection), stop tables and labels. |
| `FrameAnalyzer.kt` | Viewfinder analysis: histogram, metering, zebra, focus peaking. |
| `MeterView.kt` | The EV exposure meter. |
| `SettingsActivity.kt`, `Prefs.kt` | Settings screen and remembered settings. |
| `LocationTracker.kt` | Location for geotagging. |
| `OverlayView.kt` | Grid, level, focus ring, zebra/peaking overlay. |
| `HistogramView.kt` | Live RGB/luma histogram. |
| `ShutterButton.kt`, `AutoFitTextureView.kt` | Shutter button and aspect-correct preview. |
| `MediaSaver.kt` | Saves into the gallery via MediaStore. |
