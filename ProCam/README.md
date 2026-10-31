# ProCam 📸 — a pro camera for Android

A native Android camera app (Kotlin + Camera2, no third-party dependencies) that gives you
DSLR-style manual control over your phone's camera.

## Features

| | |
|---|---|
| **PRO mode** | Full manual exposure: dial ISO and shutter speed (1/16000 s – 30 s, whatever your sensor supports) in standard photographic stops. Switching to PRO starts from the current auto exposure so the picture doesn't jump. |
| **AUTO mode** | Auto exposure with ±EV compensation. |
| **Manual focus** | Slide from macro to ∞ with a live distance readout; slide fully left to return to autofocus. |
| **Tap to focus / meter** | Tap the preview to focus and meter on a spot (ring turns green when locked). Long-press to go back to continuous AF. |
| **White balance presets** | Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Warm Fluorescent, Twilight. |
| **RAW (DNG)** | Shoot RAW + JPEG on cameras that support it, saved side by side with matching names. |
| **Live histogram** | RGB + luminance histogram of the viewfinder. |
| **Live readout** | Real ISO, shutter, aperture and focal length the camera is using. |
| **Composition aids** | Rule-of-thirds grid and an electronic level (turns green when level, portrait or landscape). |
| **Flash** | Off / Auto / On / Torch, with proper pre-flash metering. |
| **Self-timer** | 3 s or 10 s. |
| **Hardware shutter** | Volume keys (and camera key) take the picture. |
| **Long exposures** | The viewfinder stays fluid for long shutter speeds (preview is brightness-matched). |
| **Multiple lenses** | Cycle through every camera the phone exposes (back, front, and other lenses where available). |

Photos are saved to `Pictures/ProCam` in your gallery. Tap the thumbnail to open the last shot.

> Manual ISO/shutter/focus and RAW need a phone whose camera reports the `MANUAL_SENSOR` /
> `RAW` capability (most mid- and high-end phones from the last several years). On other
> phones those controls are greyed out and AUTO mode still works.

## Requirements

- Android 10 (API 29) or newer
- Only the **Camera** permission (saving uses MediaStore, so no storage permission is needed)

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
| `ShotSettings.kt` | Settings model, camera capabilities, photographic stop tables and labels. |
| `OverlayView.kt` | Grid, electronic level and focus ring. |
| `HistogramView.kt` | Live RGB/luma histogram. |
| `ShutterButton.kt`, `AutoFitTextureView.kt` | Shutter button and aspect-correct preview. |
| `MediaSaver.kt` | Saves into the gallery via MediaStore. |
