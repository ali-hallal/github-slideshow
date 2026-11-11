# AnyCam — one camera that works the same on any phone

A small Android camera app built on one idea: **every photo is several shots merged into one**,
using only what Android requires every phone's camera to provide. No manufacturer-specific
features, no hidden settings, no libraries, no network.

## What it does (version 0.1)

- **Merged photos.** Pressing the shutter takes a short burst (3 to 7 full-size shots, more in
  dim light). The sharpest shot becomes the photo; the others are aligned to it tile by tile and
  averaged in, which removes noise without smearing detail. Anything that moved between shots is
  left out there, so there are no ghosts.
- **Tap** to focus and meter on a point. **Drag up or down** to make the picture brighter or darker.
- **Pinch** or use the zoom buttons. On phones with several lenses Android switches lens as you zoom.
- **Light** (torch), **switch camera**, and the **volume keys** as a shutter.
- Photos go to `Pictures/AnyCam`; tap the thumbnail to open the last one.

Not there yet: video, portrait, night and the other special modes, RAW, flash photos, settings.

## How it is built

| Part | Folder | What it is |
|---|---|---|
| Photo engine | `core/` | Plain Kotlin, no Android. Frame alignment and merging. Tested by ordinary unit tests in seconds. |
| Camera | `camera/` | One class around Android's Camera2: viewfinder, focus, zoom, bursts. |
| Photo maker | `capture/` | Burst → merge → JPEG → gallery, in the background. |
| Screen | `ui/` | One screen that wires touches to the camera and shows what comes back. |

## Build

```bash
cd AnyCam
./gradlew testDebugUnitTest     # the photo engine's tests
./gradlew assembleBenchmark     # the optimised APK to install
```

Every push that touches `AnyCam/` runs the *AnyCam Android build* workflow, which also drives
the app on an emulator and takes real photos with it.

Requires Android 10 or newer and the camera permission.
