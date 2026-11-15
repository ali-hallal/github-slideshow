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
  given its brightness curve and colours, so it looks like the phone's photos.
- **Zoom from the sensor.** Zoomed in on the same lens, the part in view is cut out of every
  sensor frame and merged (more shots the further in), then enlarged up to twice. Cleaner and
  more detailed than enlarging one processed frame. Beyond the point where under 640 pixels
  across are left, or on another lens, ordinary frames are used.
- **Night.** In the dark each shot exposes as long as a hand holds (1/5 s with a stabilised lens,
  1/10 s without), and a very dark merged photo is brightened on the raw light, up to 3 stops.
- **Video**, 4K where the phone's camera and encoder take it (full HD otherwise), 30 frames a
  second, with the phone's own video steadying where it offers it, and sound. Saved to `Movies/AnyCam`.
- **Measure.** Two ways that work on every phone, nothing to install:
  - *On a photo, against a known object:* lay a bank card (or an A4 or Letter sheet) on or
    beside what you measure, put the four handles on its corners, and drag lines anywhere on
    the same flat surface. Seen at an angle is fine: the card's corners fix the perspective.
    About 1–2 % when the corners are marked carefully (a magnifier helps). Saved with the
    lengths drawn on.
  - *Live, by aiming:* with the camera's height above the floor set once, aim the cross at a
    point on the floor to get its distance; "Mark base" then gives the height of whatever the
    cross is on; "Mark start" gives lengths along the floor. A few per cent, on level floors.
- **Self-timer** (3 or 10 s) and a **grid** of thirds, remembered between uses.
- **Your phone's own processing, where it offers it.** Android lets a phone maker offer its own
  night, HDR and portrait processing to other apps ("camera extensions"). AnyCam asks the phone
  which of these it has and shows each as a mode next to its own **Photo**. Nothing is assumed
  about any brand: a phone that offers none simply shows no extra modes.
- **Tap** to focus and meter on a point. **Drag up or down** to make the picture brighter or darker.
- **Pinch** or use the zoom buttons. On phones with several lenses Android switches lens as you zoom.
- **Light** (torch), **switch camera**, and the **volume keys** as a shutter.
- Photos go to `Pictures/AnyCam`; tap the thumbnail to open the last one.

Not there yet: AnyCam's own portrait mode, true super-resolution (detail finer than the sensor's
pixels), saving RAW files, flash photos, settings.

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
