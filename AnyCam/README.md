# AnyCam — one camera that works the same on any phone

A small Android camera app built on one idea: **every photo is several shots merged into one**,
using only what Android requires every phone's camera to provide. No manufacturer-specific
features, no hidden settings, no libraries, no network.

## What it does (version 0.1)

- **Merged photos.** Pressing the shutter takes a short burst (3 to 7 full-size shots, more in
  dim light). The sharpest shot becomes the photo; the others are aligned to it tile by tile and
  averaged in, which removes noise without smearing detail. Anything that moved between shots is
  left out there, so there are no ghosts.
- **Photos from the sensor itself, where the phone gives it.** On cameras that deliver RAW
  frames, the burst is merged before any of the phone's processing and developed by AnyCam.
  Many phones give other apps a larger RAW frame than an ordinary one, so this is also where
  extra megapixels come from. One ordinary frame is taken alongside, and the developed photo is
  given its brightness curve and colours, so it looks like the phone's photos. At 1× only for
  now; zoomed shots use ordinary frames.
- **Your phone's own processing, where it offers it.** Android lets a phone maker offer its own
  night, HDR and portrait processing to other apps ("camera extensions"). AnyCam asks the phone
  which of these it has and shows each as a mode next to its own **Photo**. Nothing is assumed
  about any brand: a phone that offers none simply shows no extra modes.
- **Tap** to focus and meter on a point. **Drag up or down** to make the picture brighter or darker.
- **Pinch** or use the zoom buttons. On phones with several lenses Android switches lens as you zoom.
- **Light** (torch), **switch camera**, and the **volume keys** as a shutter.
- Photos go to `Pictures/AnyCam`; tap the thumbnail to open the last one.

Not there yet: video, AnyCam's own night and portrait modes, saving RAW files, flash photos, settings.

## How it is built

| Part | Folder | What it is |
|---|---|---|
| Photo engine | `core/` | Plain Kotlin, no Android. Alignment, merging, developing sensor data, the look. Tested by ordinary unit tests in seconds. |
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
