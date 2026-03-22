<p align="center">
  <img src="shadesync app icon.png" alt="ShadeSync Logo" width="200"/>
</p>

<h1 align="center">ShadeSync</h1>

<p align="center">
  <b>Real-time virtual lipstick try-on powered by AI</b><br>
  Try before you buy — see how any shade looks on you, instantly.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/CameraX-1.3-4285F4?logo=google&logoColor=white" />
  <img src="https://img.shields.io/badge/MediaPipe-FaceMesh-FF6F00?logo=google&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-24-blue" />
</p>

---

## What is ShadeSync?

ShadeSync is an Android AR app that lets you virtually try on lipstick shades in real-time using your front camera. It uses Google's **MediaPipe Face Landmarker** to detect 478 facial landmarks and renders a realistic lipstick overlay directly on your lips with precise edge mapping, soft glow blending, and zero perceptible lag.

Built as a foundation for a beauty-tech startup — no gimmicky face meshes, just clean, production-quality AR.

## Features

- **Real-time AR lipstick overlay** — Color is rendered precisely on your lips using MediaPipe's 478-point face mesh
- **5 curated shades** — Classic Red, Rose Pink, Berry Plum, Nude MLBB, Coral Orange
- **Precise coordinate mapping** — FILL_CENTER-aware transform ensures landmarks align perfectly with the camera preview on any screen aspect ratio
- **Soft edge blending** — BlurMaskFilter glow creates natural lipstick edges (no hard polygon outlines)
- **GPU-accelerated inference** — MediaPipe runs on GPU delegate with automatic CPU fallback
- **Zero-allocation rendering** — Pre-allocated Paths, Paints, and Bitmaps are reused every frame for smooth 30 fps performance
- **Front camera only** — Purpose-built for selfie-mode try-on

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9 |
| Camera | CameraX 1.3.1 (camera-core, camera2, lifecycle, view) |
| Face Detection | MediaPipe Tasks Vision 0.10.9 (Face Landmarker) |
| Model | `face_landmarker.task` (float16, ~3.6 MB) |
| Rendering | Custom `View` with Canvas API, BlurMaskFilter |
| Build | Gradle 8.2, AGP 8.2.0 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |

## Architecture

```
┌──────────────────────────────────────────────┐
│                 MainActivity                  │
│                                              │
│  ┌──────────┐  ┌────────────┐  ┌──────────┐ │
│  │ CameraX  │──│ ImageProxy │──│ MediaPipe│ │
│  │ Preview  │  │ Analysis   │  │ FaceLand-│ │
│  │          │  │ (4:3)      │  │ marker   │ │
│  └────┬─────┘  └────────────┘  └────┬─────┘ │
│       │                              │       │
│       ▼                              ▼       │
│  ┌──────────┐              ┌──────────────┐  │
│  │ Preview  │              │ FaceMeshOver-│  │
│  │ View     │              │ layView      │  │
│  │(camera   │              │(lipstick     │  │
│  │ feed)    │              │ rendering)   │  │
│  └──────────┘              └──────────────┘  │
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │         Color Picker Bar (5 shades)      ││
│  └──────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
```

**Key design decisions:**
- Preview and ImageAnalysis both use **4:3 aspect ratio** so normalized landmark coordinates from MediaPipe map 1:1 to the camera's field of view
- The overlay computes a **FILL_CENTER transform** (`scale + offset`) matching PreviewView's internal scaling, so landmarks align on any screen shape
- Rendering uses `LAYER_TYPE_SOFTWARE` for `BlurMaskFilter` support — hardware layers don't support blur on most devices
- GPU delegate is attempted first; if it throws at creation time, the app silently falls back to CPU

## Getting Started

### Prerequisites

- Android SDK with platform 34 installed
- JDK 17
- A physical Android device (API 24+) with a front camera
- ADB configured and device connected

### Build & Install

```bash
# Clone
git clone https://github.com/DishankChauhan/shadeSync.git
cd shadeSync

# Set your Android SDK path
export ANDROID_HOME=/path/to/your/android/sdk

# Build
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.shadesync.app/.MainActivity
```

### Permissions

The app requests **Camera** permission at runtime. Grant it to see the live preview and AR overlay.

## Project Structure

```
app/src/main/
├── assets/
│   └── face_landmarker.task          # MediaPipe model (float16)
├── java/com/shadesync/app/
│   ├── MainActivity.kt              # Camera setup, MediaPipe init, color picker
│   └── FaceMeshOverlayView.kt       # AR overlay rendering (lipstick)
└── res/
    ├── drawable/
    │   ├── bottom_bar_bg.xml         # Gradient background for picker
    │   └── color_circle_*.xml        # 5 shade button drawables
    ├── layout/
    │   └── activity_main.xml         # PreviewView + Overlay + Picker
    ├── mipmap-*/
    │   └── ic_launcher.png           # App icon (all densities)
    └── values/
        ├── colors.xml
        ├── strings.xml
        └── themes.xml
```

## Lip Landmark Mapping

ShadeSync uses MediaPipe's Face Landmarker which outputs 478 normalized 3D landmarks. The lipstick overlay targets these specific landmark groups:

| Region | Landmark Indices |
|--------|-----------------|
| Outer lip contour | 61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185 |
| Upper lip (top edge) | 61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291 |
| Upper lip (bottom edge) | 291, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61 |
| Lower lip (top edge) | 61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291 |
| Lower lip (bottom edge) | 291, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78, 61 |

The upper and lower lip are rendered as separate "band" paths (outer edge → inner edge → close), then filled with a semi-transparent color and surrounded by a soft blur glow.

## Roadmap

- [ ] Eye shadow and eyeliner virtual try-on
- [ ] Eyebrow fill/shaping overlay
- [ ] Custom color picker (HSL wheel)
- [ ] Shade recommendations based on skin tone detection
- [ ] Side-by-side before/after comparison
- [ ] Photo mode (capture + share)
- [ ] Brand shade catalog integration

## License

This project is proprietary. All rights reserved.

## Author

**Dishank Chauhan** — [GitHub](https://github.com/DishankChauhan)
