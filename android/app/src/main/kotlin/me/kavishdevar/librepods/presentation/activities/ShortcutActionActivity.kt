/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.data.app.LibrePodsIntents
import me.kavishdevar.librepods.services.LibrePodsService

// Headless target for dynamic home-screen app-icon shortcuts (individual listening modes,
// connect, and disconnect).
class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.getStringExtra(EXTRA_ACTION)
        val mac = intent?.getStringExtra(EXTRA_MAC)?.let { MacAddress(it) }
        val service = LibrePodsService.instance

        when (action) {
            ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, -1)
                val device = findConnectedAppleDevice(service, mac)
                if (device == null || mode == -1) {
                    Toast.makeText(this, R.string.airpods_not_connected, Toast.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "Shortcut setting listening mode to $mode")
                    CoroutineScope(Dispatchers.IO).launch {
                        device.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, mode)
                    }
                    Toast.makeText(this, getModeLabel(mode), Toast.LENGTH_SHORT).show()
                }
            }

            ACTION_CONNECT -> {
                val device = findKnownAppleDevice(service, mac)
                if (device != null) {
                    Log.d(TAG, "Shortcut connecting to ${device.macAddress.toRedactedString()}")
                    device.connect()
                    Toast.makeText(this, R.string.connecting, Toast.LENGTH_SHORT).show()
                } else if (mac != null) {
                    val svcIntent = Intent(this, LibrePodsService::class.java).apply {
                        this.action = LibrePodsIntents.ACTION_CONNECT
                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, mac.value)
                    }
                    startForegroundService(svcIntent)
                    Toast.makeText(this, R.string.connecting, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.airpods_not_connected, Toast.LENGTH_SHORT).show()
                }
            }

            ACTION_DISCONNECT -> {
                val device = findConnectedAppleDevice(service, mac)
                if (device != null) {
                    Log.d(TAG, "Shortcut disconnecting ${device.macAddress.toRedactedString()}")
                    device.disconnect()
                    Toast.makeText(this, R.string.disconnect, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.airpods_not_connected, Toast.LENGTH_SHORT).show()
                }
            }
        }

        finish()
    }

    private fun findConnectedAppleDevice(
        service: LibrePodsService?,
        mac: MacAddress?
    ): AppleDevice? {
        val devices = service?.devices?.value ?: return null
        val byMac = mac?.let { devices[it] as? AppleDevice }
            ?.takeIf { it.connectionState.value == ConnectionState.CONNECTED }
        return byMac ?: devices.values
            .filterIsInstance<AppleDevice>()
            .firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }
    }

    private fun findKnownAppleDevice(
        service: LibrePodsService?,
        mac: MacAddress?
    ): AppleDevice? {
        val devices = service?.devices?.value ?: return null
        return (mac?.let { devices[it] as? AppleDevice })
            ?: devices.values.filterIsInstance<AppleDevice>().firstOrNull()
    }

    private fun getModeLabel(mode: Int): Int {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> R.string.off
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.string.transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.string.adaptive
            else -> R.string.noise_cancellation
        }
    }

    companion object {
        private const val TAG = "ShortcutActionActivity"
        const val EXTRA_ACTION = "shortcut_action"
        const val EXTRA_MODE = "shortcut_mode"
        const val EXTRA_MAC = "shortcut_mac"

        const val ACTION_SET_MODE = "set_mode"
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
    }
}
