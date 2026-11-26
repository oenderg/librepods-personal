# Smart Routing, Multi-Device Handoff & Link Release

Commits: `8e596a1`, `ac2a1c9`, `56121a4`, `065fe28`, `ba7115b`.

## Background
Upstream `android/rewrite` parses incoming `OWNS_CONNECTION` and `SetOwnershipToFalse` AACP packets, but does not actively send Apple Smart Routing packets or release the ACL link when the user disconnects from Android Bluetooth Settings. On multi-device setups (Mac + iPad + Android), this causes two main issues:

1. **Ghost reconnects after Settings → Disconnect (upstream `#921`):** Android's Disconnect only tears down A2DP and HFP; LibrePods's open AACP L2CAP socket keeps the ACL alive, so the AirPods reconnect A2DP/HFP to Android ~40 s later and steal audio from the Mac.
2. **Broken handoff & ownership signaling (upstream `#942`, `#943`):**
   - `AudioSourcePacket` didn't reverse little-endian MAC bytes.
   - `sendMediaInformataion()` had a hardcoded `0x42` length byte for 3-byte `"YES"`.
   - `alwaysConnectWhenAccessoryDisconnected` could fire even when the AirPods were not disconnected.

## Fork Behavior
- **Release-link on external Disconnect (`#921`):** When A2DP/HFP transitions to `STATE_DISCONNECTED` (and LibrePods did not drop audio itself for ear-detection), LibrePods waits a 3 s grace window. If neither profile is connected/connecting (and `STATE_DISCONNECTING` isn't fighting an intentional disconnect), it closes AACP and drops the ACL link. Background AACP attachment only happens when A2DP or HFP is connected/connecting.
- **Active-A2DP yield & restore:** When another Apple host takes ownership (`OWNS_CONNECTION -> false` or `OwnershipToFalseRequest`), LibrePods calls `removeActiveDevice(0)` and pauses local playback. When local media starts, it restores `setActiveDevice(dev, 0)`.
- **Smart Routing handshake:** Registers TiPi / MediaInfo, sends `OWNS_CONNECTION`, `sendSmartRoutingShowUI` (triggering the macOS *"Moved to iPhone"* banner), `sendHijackRequest`, and supports reclaiming audio via `sendHijackReversed` from the `MOVED_TO_OTHER_DEVICE` Dynamic Island banner.
- **Takeover gating (`56121a4`):** *Settings → Interaction → Switch audio for games & social apps* (`takeover_non_media_session_audio`) defaults to **off**. When off, audio takeover requires an active `STATE_PLAYING` `MediaSession` (observed via `LibrePodsNotificationListener` + `MediaSessionManager`), preventing muted YouTube feed previews or games from hijacking the AirPods.

## Known Platform Edge Cases
- **YouTube Shorts:** Leaves its `MediaSession` in `STATE_NONE`, so AVRCP `KEYCODE_MEDIA_PAUSE` from the AirPods may not pause the video when the Mac takes over (audio still moves cleanly to the Mac).
- **Mac → Android → Mac without pausing Mac:** If macOS was never paused when Android took over, macOS may not emit a fresh `AVDTP_START` until you pause/play or trigger a new sound on the Mac.
- **Post-reboot reconnect:** Android's Bluetooth stack may reconnect the last audio device once right after a reboot; this happens outside LibrePods.
