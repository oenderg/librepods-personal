# LibrePods (Personal Fork) — Agent & Contributor Orientation

This repository (`oenderg/librepods-personal`, default branch `fork`) is a personal fork of [`librepods-org/librepods`](https://github.com/librepods-org/librepods) based on the `android/rewrite` branch (`2.0.0-alpha.4+`).

## Repository Map
- `android/app/` — Android application UI (Jetpack Compose, custom Apple-style primitives on Material3 + `backdrop`/`haze`), foreground service (`LibrePodsService`), Quick Settings tile (`AirPodsQSService`), home-screen widgets, app-icon shortcuts, Dynamic Island overlay, and LSPosed hooks (`KotlinModule.kt`, `src/main/cpp/`).
- `android/core/` — Protocol & Bluetooth core (`BLEManager`, `AACPManager`, `AppleDevice`, `MediaController`, packet encoders/decoders, Room `AppleCache`, unit tests).
- `android/privileged/` — Privileged Bluetooth/system API helpers (reflection/hidden APIs requiring `BLUETOOTH_PRIVILEGED` / system-app permissions).
- `linux/` — Upstream Rust Linux client (not actively modified on this fork).
- `docs/` — Upstream Apple AAP protocol documentation (`AAP Definitions.md`, `opcodes.md`, `control_commands.md`, `device-info.md`).
- `docs/fork/` — Fork-specific documentation:
  - [`FORK-CHANGES.md`](docs/fork/FORK-CHANGES.md) — Categorized summary of everything added/fixed on `fork` vs. `upstream/android/rewrite`.
  - [`SMART-ROUTING.md`](docs/fork/SMART-ROUTING.md) — Apple Smart Routing, active-A2DP handoff, and `MediaController` takeover gating.
  - [`COLOROS-BATTERY.md`](docs/fork/COLOROS-BATTERY.md) — ColorOS 17 native AirPods battery injection via LSPosed.
  - [`BUILDING.md`](docs/fork/BUILDING.md) — JDK/NDK setup, Gradle heap flags, unit tests, and ADB/LSPosed debugging.

## Build & Test
See [`docs/fork/BUILDING.md`](docs/fork/BUILDING.md) for full details. Quick commands from `android/`:

```bash
# Build FOSS debug APK (requires JDK 21 and larger Gradle/Kotlin heaps)
./gradlew assembleFossDebug \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
  -Pkotlin.daemon.jvmargs="-Xmx3g"

# Run core unit tests
./gradlew :core:testDebugUnitTest
```

## Key Architectural Conventions
- **Settings storage:** App-level interaction/UI preferences live in `SharedPreferences("settings")`; per-device Apple protocol state & cached values live in Room (`AppleCache` in `:core`).
- **LSPosed hooks (`KotlinModule.kt`):** Only hook unobfuscated framework/Bluetooth classes in `com.android.bluetooth` (already in LibrePods's LSPosed scope). Always guard OEM-specific hooks so they safely no-op on AOSP and other OEMs without throwing.
- **AACP / Bluetooth lifecycle:** Never spin on EOF in `AACPManager`; serialize socket teardown; keep BLE scan timestamps on `SystemClock.elapsedRealtime()`.
- **Upstream cleanliness:** Keep fork-specific docs inside `docs/fork/` so rebases/merges from `upstream/android/rewrite` stay conflict-free.

## Rules
- **No personal data in commits or docs:** Never include real Bluetooth MAC addresses, AirPods serial numbers, or personal device names.
- **Verify changes:** Run `:core:testDebugUnitTest` and `assembleFossDebug` after code edits; verify Bluetooth/UI/LSPosed changes on a real device when possible.
