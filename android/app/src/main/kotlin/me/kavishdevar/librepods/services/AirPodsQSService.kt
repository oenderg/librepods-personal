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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.activities.MainActivity
import kotlin.io.encoding.ExperimentalEncodingApi

class AirPodsQSService : TileService() {

    private var listeningScope: CoroutineScope? = null
    private var deviceObserveJob: Job? = null

    private var currentDevice: AppleDevice? = null
    private var currentAncMode: Int = NoiseControlMode.TRANSPARENCY.ordinal + 1
    private var allowOffMode: Boolean = false
    private var isAirPodsConnected: Boolean = false
    private var reconnecting: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val clearReconnecting = Runnable {
        reconnecting = false
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")

        listeningScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        listeningScope = scope

        val service = LibrePodsService.instance
        if (service != null) {
            scope.launch {
                service.devices.collectLatest { devicesMap ->
                    val appleDevices = devicesMap.values.filterIsInstance<AppleDevice>()
                    val target = appleDevices.firstOrNull {
                        it.connectionState.value == ConnectionState.CONNECTED
                    } ?: appleDevices.firstOrNull()

                    observeDevice(scope, target)
                }
            }
        } else {
            currentDevice = null
            isAirPodsConnected = false
            updateTile()
        }
    }

    private fun observeDevice(scope: CoroutineScope, device: AppleDevice?) {
        deviceObserveJob?.cancel()
        currentDevice = device
        if (device == null) {
            isAirPodsConnected = false
            updateTile()
            return
        }

        deviceObserveJob = scope.launch {
            combine(device.connectionState, device.state) { connState, appleState ->
                connState to appleState
            }.collectLatest { (connState, appleState) ->
                val connected = connState == ConnectionState.CONNECTED
                isAirPodsConnected = connected
                if (connected) {
                    reconnecting = false
                    handler.removeCallbacks(clearReconnecting)
                }
                allowOffMode = appleState.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]
                    ?.getOrNull(0)?.toInt() == 1
                currentAncMode = appleState.controlStates[ControlCommandIdentifier.LISTENING_MODE]
                    ?.getOrNull(0)?.toInt()
                    ?: NoiseControlMode.TRANSPARENCY.ordinal + 1
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
        deviceObserveJob?.cancel()
        deviceObserveJob = null
        listeningScope?.cancel()
        listeningScope = null
        handler.removeCallbacks(clearReconnecting)
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick - Connected: $isAirPodsConnected, Mode: $currentAncMode")
        if (!isAirPodsConnected) {
            reconnect()
            return
        }

        cycleAncMode()
    }

    private fun reconnect() {
        if (reconnecting) {
            Log.d(TAG, "Tile clicked while already reconnecting.")
            return
        }
        val service = LibrePodsService.instance
        val device = currentDevice
            ?: service?.devices?.value?.values?.filterIsInstance<AppleDevice>()?.firstOrNull()
        if (service == null || device == null) {
            Log.d(TAG, "Tile clicked while disconnected with no service/device; opening app.")
            launchMainActivity()
            return
        }
        Log.d(TAG, "Tile clicked while disconnected; reconnecting to ${device.macAddress.toRedactedString()}.")
        reconnecting = true
        updateTile()
        device.connect()
        handler.removeCallbacks(clearReconnecting)
        handler.postDelayed(clearReconnecting, RECONNECT_TIMEOUT_MS)
    }

    private fun launchMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                @SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching MainActivity", e)
        }
    }

    private fun cycleAncMode() {
        val device = currentDevice ?: return
        val nextMode = getNextAncMode()
        Log.d(TAG, "Cycling ANC mode from $currentAncMode to $nextMode")
        device.setControlCommand(
            ControlCommandIdentifier.LISTENING_MODE,
            nextMode
        )
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val deviceName = currentDevice?.metadata?.value?.name?.takeIf { it.isNotBlank() } ?: "AirPods"

        if (isAirPodsConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getModeLabel(currentAncMode)
            tile.subtitle = deviceName
            tile.icon = Icon.createWithResource(this, getModeIcon(currentAncMode))
        } else {
            // Keep the tile clickable while disconnected so a tap can reconnect.
            tile.state = Tile.STATE_INACTIVE
            tile.label = deviceName
            tile.subtitle = getString(if (reconnecting) R.string.reconnecting else R.string.tap_to_reconnect)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_airpods)
        }

        try {
            tile.updateTile()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tile", e)
        }
    }

    private fun getAvailableModes(): List<Int> {
        val modes = mutableListOf(
            NoiseControlMode.TRANSPARENCY.ordinal + 1,
            NoiseControlMode.ADAPTIVE.ordinal + 1,
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1
        )
        if (allowOffMode) {
            modes.add(0, NoiseControlMode.OFF.ordinal + 1)
        }
        return modes
    }

    private fun getNextAncMode(): Int {
        val availableModes = getAvailableModes()
        val currentIndex = availableModes.indexOf(currentAncMode)
        val nextIndex = (currentIndex + 1) % availableModes.size
        return availableModes[nextIndex]
    }

    private fun getModeLabel(mode: Int): String {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> getString(R.string.off)
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> getString(R.string.transparency)
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> getString(R.string.adaptive)
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> getString(R.string.noise_cancellation)
            else -> getString(R.string.listening_mode)
        }
    }

    private fun getModeIcon(mode: Int): Int {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> R.drawable.ic_noise_cancellation
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.ic_transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.ic_adaptive
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.ic_noise_cancellation
            else -> R.drawable.ic_airpods
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added")
    }

    companion object {
        private const val TAG = "AirPodsQSService"
        private const val RECONNECT_TIMEOUT_MS = 6_000L
    }
}
