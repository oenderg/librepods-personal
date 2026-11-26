# ColorOS 17 Native AirPods Battery Injection

Commit: `96258bd` (`KotlinModule.kt`, `LibrePodsService.kt`).

## Why AOSP `setMetadata()` Doesn't Work on ColorOS
On AOSP/Pixel, calling `BluetoothDevice.setMetadata()` with `METADATA_UNTETHERED_LEFT_BATTERY` / `RIGHT_BATTERY` / `CASE_BATTERY` updates the Bluetooth battery UI. On ColorOS 17 (OnePlus / OPPO), `com.android.bluetooth` delegates battery state to an OEM extension class (`OplusRemoteDevicesExtImpl`) loaded dynamically from `/system_ext/framework/oplus_bluetooth_common_ext.jar` via `OplusBTFactory`. Both **SystemUI** (the vertical Bluetooth battery bar in the Status Bar) and **WirelessSettings** (`In use. Battery: XX %`) read from `OplusRemoteDevicesExtImpl` rather than AOSP's untethered metadata keys.

## How the LSPosed Hook Works
LibrePods's LSPosed module already targets `com.android.bluetooth` (for VendorID spoofing), so no extra LSPosed scope is required:

1. **Hook `com.android.bluetooth.btservice.RemoteDevices` constructor** (unobfuscated AOSP class inside `Bluetooth.apk`).
2. **Extract `mOplusRemoteDevice`** and its runtime `ClassLoader` (since `oplus_bluetooth_common_ext.jar` is not on `param.defaultClassLoader`).
3. **Register a `BLUETOOTH_PRIVILEGED`-gated `BroadcastReceiver`** inside `com.android.bluetooth` listening for `ACTION_INJECT_COLOROS_BATTERY` (sent by `LibrePodsService` whenever AirPods battery changes or on connect).
4. **Inject & notify:**
   - Packs the AirPods battery into ColorOS's `int[6]` format (`left`, `right`, `case`, plus charging flags) and invokes `OplusRemoteDevicesExtImpl.notifyBatteryLevelChanged(device, int[6])`.
   - Hooks `getBatteryInfo`, `is3rdEarBudsDevice`, and `OplusAdapterServiceExtImpl.checkIsAirpodsDevice` so ColorOS treats the AirPods as a supported battery-reporting headset.

## OEM Safety & Stability
- **Non-ColorOS ROMs:** All reflection lookups (`mOplusRemoteDevice`, `OplusRemoteDevicesExtImpl`, `OplusAdapterServiceExtImpl`) are null-checked and wrapped in `runCatching`. On AOSP, Pixel, Samsung, or Xiaomi devices where `mOplusRemoteDevice` does not exist, the hook logs once and safely no-ops without crashing `com.android.bluetooth`.
- **Obfuscation:** None of the hooked classes or methods inside `com.android.bluetooth` or `oplus_bluetooth_common_ext.jar` are R8-obfuscated.

## Known Limitation on EEA ROMs (Split `L / R` Text)
On ColorOS EEA builds (e.g., `CPH2653EEA`), Bluetooth Settings displays the combined `In use. Battery: XX %` string (and the Status Bar shows the vertical battery bar), while split `L XX% R YY%` text in `com.oplus.wirelesssettings` is gated behind region/export checks inside R8-obfuscated classes. Unlocking split text on EEA ROMs OTA-safely would require adding `com.oplus.wirelesssettings` to the LSPosed scope and using bytecode matching (e.g., DexKit) on stable log strings rather than hardcoded obfuscated names.
