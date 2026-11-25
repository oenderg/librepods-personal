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
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.services.LibrePodsService

// Headless target of the "Toggle listening mode" app shortcut. Switches between two listening
// modes (default: Noise Cancellation <-> Transparency) without opening the UI.
class ToggleListeningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device = LibrePodsService.instance
            ?.devices
            ?.value
            ?.values
            ?.filterIsInstance<AppleDevice>()
            ?.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

        if (device == null) {
            Toast.makeText(this, R.string.airpods_not_connected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val currentMode = device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE]
            ?.getOrNull(0)?.toInt()
        val (first, second) = getModes(getSharedPreferences("settings", MODE_PRIVATE))
        val nextMode = if (currentMode == first) second else first

        Log.d(TAG, "Switching listening mode from $currentMode to $nextMode")
        device.setControlCommand(
            ControlCommandIdentifier.LISTENING_MODE,
            nextMode
        )
        Toast.makeText(this, getModeLabel(nextMode), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getModeLabel(mode: Int): Int {
        return when (mode) {
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.string.transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.string.adaptive
            else -> R.string.noise_cancellation
        }
    }

    companion object {
        private const val TAG = "ToggleListeningMode"
        const val PREF_KEY = "toggle_listening_modes"

        val SELECTABLE_MODES = listOf(
            NoiseControlMode.TRANSPARENCY,
            NoiseControlMode.ADAPTIVE,
            NoiseControlMode.NOISE_CANCELLATION
        )

        private val DEFAULT_MODES = listOf(
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1,
            NoiseControlMode.TRANSPARENCY.ordinal + 1
        )

        fun getModes(sharedPreferences: SharedPreferences): List<Int> {
            val modes = sharedPreferences.getString(PREF_KEY, null)
                ?.split(",")
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { value -> SELECTABLE_MODES.any { it.ordinal + 1 == value } }
            return if (modes?.size == 2) modes else DEFAULT_MODES
        }
    }
}
