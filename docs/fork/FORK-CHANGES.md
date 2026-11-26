# Fork Changes (`fork` vs. `upstream/android/rewrite`)

Base: `upstream/android/rewrite` (`2e244d3`, `2.0.0-alpha.4`).
This document summarizes the fixes and features carried on `oenderg/librepods-personal` (`fork`).

## Multi-Device Handoff & Apple Smart Routing
- **Active-A2DP handoff & Apple Smart Routing handshake (`ac2a1c9`, `8e596a1`):**
  - Reverses little-endian MAC bytes in `AudioSourcePacket` and fixes the OPACK length byte for `"YES"` in `AACPManager.sendMediaInformataion()` (upstream `#943`).
  - Releases the ACL/AACP link on Android Bluetooth Settings → Disconnect after a 3 s grace window (only when LibrePods didn't release audio itself), and only attaches AACP in the background when A2DP/HFP is connected or connecting (upstream `#921`).
  - Guards `alwaysConnectWhenAccessoryDisconnected` to require `AccessoryState.Disconnected` (upstream `#942`).
  - Yields active A2DP (`removeActiveDevice(0)`) when another Apple host takes audio, silently mutes phone speaker `STREAM_MUSIC` once `AudioPolicyManager` routes to `TYPE_BUILTIN_SPEAKER` (preventing ~45 ms speaker bleed on takeover; restored safely on disconnect/stop/unwear), and restores (`setActiveDevice(dev, 0)`) on phone playback.
  - Implements the Smart Routing handshake (TiPi/MediaInfo registration, `OWNS_CONNECTION`, `sendSmartRoutingShowUI` for macOS *"Moved to iPhone"* banner, `sendHijackRequest`, and `OwnershipToFalseRequest` yield + pause).
  - Details in [`SMART-ROUTING.md`](SMART-ROUTING.md).
- **Non-MediaSession audio takeover toggle (`56121a4`):**
  - Adds *Settings → Interaction → Switch audio for games & social apps* (`takeover_non_media_session_audio`, **off by default**).
  - When disabled, requires an active `STATE_PLAYING` `MediaSession` (via `LibrePodsNotificationListener`), preventing muted YouTube home-feed previews or games from stealing audio.

## Stability, BLE & Protocol Fixes
- **BLE scanner & battery-drain fixes (`076130e`, upstream `#911`):**
  - Unified `lastSeenMap` and timeouts on monotonic `SystemClock.elapsedRealtime()`.
  - Made `start()`/`stop()` idempotent and restarted `connectionTimeoutJob` cleanly.
  - Guarded `flushPendingScanResults` behind `reportDelay > 0L`.
  - Added a 128-entry LRU case-advert decryption cache, caps on `rejectedRandomMac` (512) and `rpasByPublicMac` (16/device), and a `data.size < 16` bounds check.
- **AACP / `AppleDevice` lifecycle hardening (`065fe28`):**
  - Serialized `AACPManager` socket teardown and guarded immediate EOF vs. `CONNECTED`.
  - Scoped ATT reads/polling in `AppleDevice` to a per-connection `CoroutineScope`.
  - Added AACP-only teardown in `LibrePodsService.onDestroy()`, `STATE_DISCONNECTING` guard during external disconnects, and pending A2DP receiver cleanup.
  - Fixed ear-detection / A2DP reconnect race when buds are re-inserted within 3 s of LibrePods releasing audio (`audioReleasedByLibrePods` no longer schedules `pendingAudioReleaseChecks`), plus a media-start A2DP recovery safety net in `restoreYieldedAudioDevice()`.
- **`RenamePacket` & `RenameScreen` fix (`4ca583a`):**
  - Fixed 16-bit LE length and UTF-8 byte-length encoding in `RenamePacket` (prevented AirPods firmware crashes/reboots when opening the Rename screen).
  - Skipped sending on screen open/unchanged text and debounced typing by 400 ms.
- **Immediate audio-profile connect (`ce0b447`):**
  - `AppleDevice.connect()` proactively connects audio profiles in parallel with AACP.
- **Bluetooth toggle recovery (`96258bd`):**
  - Listens to `BluetoothAdapter.ACTION_STATE_CHANGED` (`STATE_ON` / `STATE_OFF`) in `LibrePodsService` to re-register audio profile proxies and restart BLE scanning after toggling Bluetooth.

## ColorOS 17 Native Battery Integration
- **Native Status Bar & Bluetooth Settings battery injection (`96258bd`):**
  - Hooks `RemoteDevices` → `OplusRemoteDevicesExtImpl` inside `com.android.bluetooth` via LSPosed to feed live AirPods battery into ColorOS's native Status Bar Bluetooth battery bar and `In use. Battery: XX %` subtitle in Bluetooth Settings.
  - Guards `broadcastColorOsBattery()` on `ConnectionState.CONNECTED` (and broadcasts disconnected on leaving `CONNECTED`) so BLE case adverts don't show a status bar battery icon while AirPods are in the case.
  - Safely no-ops on AOSP and non-ColorOS OEMs. Details in [`COLOROS-BATTERY.md`](COLOROS-BATTERY.md).

## Quick Settings, Shortcuts, Widgets & Dynamic Island
- **Quick Settings tile (`49ea47d`, `36cb4f3`, `ef5c55d`):**
  - Wired `AirPodsQSService` to `LibrePodsService` with tap-to-reconnect when disconnected and a Compose `TileService.showDialog()` listening-mode + Conversation Awareness picker when connected.
  - Added an open-app pill button next to the battery badge and *Settings → Interaction → Quick Settings tile action* (`Open listening mode dialog` / `Cycle all available modes` / `Cycle selected modes`).
  - Fixed Apple-theme Adaptive mode label clipping in `NoiseControlSettings.kt`.
- **Dynamic app-icon shortcuts (`30ff029`, `57c2c53`, `ce0b447`, `3910e55`, `ef5c55d`):**
  - Live launcher shortcuts reflecting connection state & model capabilities (supported listening modes including **Off** when `ALLOW_OFF_OPTION` is enabled, plus Connect/Disconnect).
  - Uniform white tint on `#1C222A` adaptive badges via `Icon.createWithAdaptiveBitmap()`.
- **Notification & Widget improvements (`ef5c55d`):**
  - Added *Settings → Interaction → Notification mode buttons* toggle.
  - Fixed `NoiseControlWidget` (`LibrePodsIntents` action/extra, active-mode highlight, `ALLOW_OFF_OPTION` visibility, live updates) and `BatteryWidget.onUpdate()`.
- **Dynamic Island & overlay lifecycle polish (`bebd347`, `ba7115b`, overlay lifecycle fix):**
  - Preserves BLE-advertised battery across `loadInitialState()`, fixes single-bud `0%` flash via `computeDisplayBatteryLevel()`, and hides battery ring/text until a `> 0` level is known.
  - Wires `IslandType.MOVED_TO_OTHER_DEVICE` (with circular iOS-blue undo button calling `sendHijackReversed` to reclaim audio) and `IslandType.TAKING_OVER`.
  - Fixes `ComposeView` / `LifecycleRegistry` crash in `IslandWindow` and `ProximityCardWindow` (`updateContext()` no longer adds a closed island overlay, tears down with the old `WindowManager` via `removeViewImmediate()`, and guards `start()`/`stop()`).

## Upstream PRs Integrated
- `#850` (`30ff029`) — Toggle listening mode app shortcut
- `#855` (`41da501`) — Prevent Conversation Awareness volume overshoot & stale ramp
- `#875` (`72bfa27`) — Stop AACP reader loop spinning on peer disconnect
- `#881` (`bebd347`) — iOS-style AirPods card for Dynamic Island
- `#883` (`49ea47d`) — Wire Quick Settings tile to `LibrePodsService`
- `#914` (`5b8146f`) — Harden AirPods Pro 3 heart rate monitoring
- `#930`, `#931` (`6571b22`) — Preserve connected device types and guard short AACP packets

## Tooling / CI
- **`upstream-watch.yml` (`3121e2b`):** Daily GitHub Actions workflow mirroring `upstream/main` and `upstream/android/rewrite` and opening/updating a tracking issue when upstream advances.
