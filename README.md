# COSC300.1 AR Dinosaur Exhibit Companion

Augmented Reality (AR) Android application that scans dinosaur QR codes and loads corresponding 3D models with guided audio + limited domain chat (Gemini backend) plus placeholder authentication UI (Login & Signup screens – logic not implemented yet).

## Table of Contents
- [Features](#features)
- [Screens](#screens)
- [Architecture Overview](#architecture-overview)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Environment & Secrets](#environment--secrets)
- [Building & Running](#building--running)
- [QR + AR Flow Summary](#qr--ar-flow-summary)
- [Adding New 3D Models](#adding-new-3d-models)
- [Authentication Roadmap](#authentication-roadmap)
- [Development Scripts](#development-scripts)
- [Testing](#testing)
- [CI (GitHub Actions)](#ci-github-actions)
- [Contributing](#contributing)
- [License](#license)

## Features
- ARCore + SceneView integration for rendering GLB dinosaur models.
- QR code detection (ZXing) with pose estimation using OpenCV to anchor models accurately.
- Static IBL environment for consistent lighting (disables AR light estimation jitter).
- Stabilization buffer to reduce pose jitter before anchoring a model.
- Supabase PostgREST + Storage integration to look up model metadata & download GLB files.
- Gemini-based constrained chat assistant (responses restricted to four known dinosaurs) with Text-To-Speech narration.
- Expandable floating chat FAB with animation and state-driven availability.
- Reload/reset scanning flow with re‑stabilization and early reload button reveal.
- UI-only Login & Signup pages (no backend yet) ready for future auth integration.
- Consistent overlay Home button (top-left) on all secondary screens.

## Screens
| Screen | Purpose |
|--------|---------|
| HomeActivity | Navigation hub (Scan, Settings, Tutorial, About, Auth) |
| MainActivity | AR scanning, model placement, chat UI |
| SettingsActivity | Placeholder future configuration |
| TutorialActivity | User guidance placeholder |
| AboutActivity | App info placeholder |
| LoginActivity / SignupActivity | UI-only authentication forms |

## Architecture Overview
- **Presentation**: Traditional XML layouts + AppCompat Activities. (Jetpack Compose dependencies present for future adoption, but current UI = Views.)
- **AR Layer**: `ARSceneView` + custom pose estimation via `QRPoseEstimator`.
- **Data Layer**: Supabase client (PostgREST + Storage) lazily injected; simple model table (`models`).
- **Chat Layer**: `GeminiChatService` behind `ChatBackend` interface with `DinoBoundedChatBackend` enforcing domain restrictions.
- **Speech**: `AndroidTTS` abstraction for speaking both guidance & chat responses.

## Tech Stack
- Kotlin (JVM target 11)
- AndroidX / AppCompat
- ARCore + SceneView
- OpenCV (pose assistance)
- ZXing (QR scanning)
- Supabase-KT (PostgREST, Storage)
- OkHttp + Ktor client pieces
- Google Gemini API (optional)

## Project Structure
```
app/
  src/main/java/com/example/cosc3001/
    MainActivity.kt
    HomeActivity.kt
    ... (other Activities)
  src/main/res/layout/
  src/assets/qr.png (overlay guide)
  build.gradle.kts
sdk/  (native/OpenCV related support)
```

## Prerequisites
- Android Studio / Gradle 8+ (Wrapper included)
- Android device or emulator with ARCore support for full feature set
- Min SDK 26; Target/Compile SDK 36
- (Optional) Supabase project + table + storage bucket
- (Optional) Gemini API key

## Environment & Secrets
All keys are injected via Gradle properties at build time.
Avoid committing secrets to VCS.

| Variable | Usage | Where to Set |
|----------|-------|--------------|
| SUPABASE_URL | Supabase REST endpoint | `~/.gradle/gradle.properties` |
| SUPABASE_ANON_KEY | Supabase anon key | `~/.gradle/gradle.properties` |
| GEMINI_API_KEY | Gemini auth header | `~/.gradle/gradle.properties` or env |
| GEMINI_MODEL | Gemini model override | optional |

Example `~/.gradle/gradle.properties`:
```
SUPABASE_URL=https://xyzcompany.supabase.co
SUPABASE_ANON_KEY=public-anon-key-here
GEMINI_API_KEY=your-key
GEMINI_MODEL=gemini-2.0-flash
```

## Building & Running
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or run directly from Android Studio.

## QR + AR Flow Summary
1. Camera frames acquired from ARCore session.
2. YUV -> NV21 conversion; ZXing attempts fast + harder decode paths.
3. Detected QR corner points -> pose estimation (marker & upright pose).
4. Stabilization buffer ensures consistent pose before anchor.
5. Supabase row & GLB URL fetched; model anchored & scaled; TTS guidance queued.

## Adding New 3D Models
1. Upload `.glb` into Supabase storage bucket (e.g. `glb_files`).
2. Add row to `models` table: `qr_name`, `description`, `scale_factor` (optional).
3. Print / distribute a QR code whose text equals `qr_name`.
4. Scan in app -> model loads.

## Authentication Roadmap
Current Login/Signup screens are **UI-only**. Future steps:
- Integrate Supabase Auth or Firebase Auth.
- Client-side validation & interactive button enabling.
- Persist session + secure token handling.

## Development Scripts
Useful Gradle tasks:
```bash
./gradlew clean
./gradlew :app:assembleRelease
```

## Testing
No dedicated unit test coverage yet for AR or chat. Suggested future additions:
- Pose estimation unit tests with synthetic corner sets.
- ChatBackend contract tests (mock Gemini responses).
- Instrumented UI smoke tests (launch & basic component visibility).

## CI (GitHub Actions)
A workflow (`.github/workflows/android-ci.yml`) builds the debug variant and runs lint & unit tests on push / PR.

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md) for style, branching, and PR guidelines.

## Security
Report vulnerabilities privately – see [SECURITY.md](SECURITY.md).

---
*Generated initialization docs – customize as needed.*

