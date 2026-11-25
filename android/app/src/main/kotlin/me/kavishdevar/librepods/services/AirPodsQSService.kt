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
import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.data.app.NightTheme
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.LocalDesign
import kotlin.io.encoding.ExperimentalEncodingApi

class AirPodsQSService : TileService() {

    private var listeningScope: CoroutineScope? = null
    private var deviceObserveJob: Job? = null
    private var activeDialog: Dialog? = null
    private var activeDialogLifecycleOwner: QSDialogLifecycleOwner? = null

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
        // Do NOT dismiss activeDialog here: showing a QS dialog causes SystemUI (e.g. ColorOS)
        // to pause the tile and invoke onStopListening() immediately after showDialog().
        deviceObserveJob?.cancel()
        deviceObserveJob = null
        listeningScope?.cancel()
        listeningScope = null
        handler.removeCallbacks(clearReconnecting)
    }

    override fun onDestroy() {
        dismissActiveDialog()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick - Connected: $isAirPodsConnected, Mode: $currentAncMode")
        if (!isAirPodsConnected) {
            reconnect()
            return
        }

        showListeningModeDialog()
    }

    private fun dismissActiveDialog() {
        try {
            activeDialog?.dismiss()
        } catch (_: Exception) {
        }
        activeDialog = null
        activeDialogLifecycleOwner?.stop()
        activeDialogLifecycleOwner = null
    }

    private fun showListeningModeDialog() {
        val device = currentDevice ?: return
        dismissActiveDialog()

        val appSettingsFlow = (application as? LibrePodsApplication)?.appDataRepository?.settings
        val lifecycleOwner = QSDialogLifecycleOwner()
        lifecycleOwner.start()
        activeDialogLifecycleOwner = lifecycleOwner

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                val appSettings = appSettingsFlow?.collectAsState()?.value
                val darkTheme = when (appSettings?.nightMode ?: NightTheme.System) {
                    NightTheme.Dark -> true
                    NightTheme.Light -> false
                    NightTheme.System -> isSystemInDarkTheme()
                }
                val design = if (appSettings != null) {
                    Design(
                        system = appSettings.designSystem,
                        colorStyle = appSettings.colorStyle,
                        overrideMaterialColor = appSettings.overrideMaterialColor,
                        contrastLevel = appSettings.contrastLevel,
                        iconStyle = appSettings.iconStyle
                    )
                } else {
                    Design()
                }

                LibrePodsTheme(
                    design = design,
                    accessibilitySettings = appSettings?.accessibilitySettings
                        ?: me.kavishdevar.librepods.data.app.AccessibilitySettings(),
                    fontSettings = appSettings?.fontSettings
                        ?: me.kavishdevar.librepods.data.app.FontSettings(),
                    darkTheme = darkTheme
                ) {
                    QSListeningModeDialogContent(
                        device = device,
                        onModeSelected = { newMode ->
                            currentAncMode = newMode
                            updateTile()
                            CoroutineScope(Dispatchers.IO).launch {
                                device.setControlCommand(
                                    ControlCommandIdentifier.LISTENING_MODE,
                                    newMode
                                )
                            }
                        },
                        onAdaptiveStrengthChanged = { strength ->
                            CoroutineScope(Dispatchers.IO).launch {
                                device.setControlCommand(
                                    ControlCommandIdentifier.AUTO_ANC_STRENGTH,
                                    strength
                                )
                            }
                        },
                        onConversationAwarenessChanged = { enabled ->
                            CoroutineScope(Dispatchers.IO).launch {
                                device.setControlCommand(
                                    ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
                                    enabled
                                )
                            }
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.decorView?.let { decor ->
            decor.setPadding(0, 0, 0, 0)
            decor.setViewTreeLifecycleOwner(lifecycleOwner)
            decor.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            decor.setViewTreeViewModelStoreOwner(lifecycleOwner)
        }
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
            if (activeDialogLifecycleOwner === lifecycleOwner) {
                lifecycleOwner.stop()
                activeDialogLifecycleOwner = null
            }
        }

        activeDialog = dialog
        try {
            showDialog(dialog)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show QS dialog, falling back to mode cycle", e)
            dismissActiveDialog()
            cycleAncMode()
        }
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
        CoroutineScope(Dispatchers.IO).launch {
            device.setControlCommand(
                ControlCommandIdentifier.LISTENING_MODE,
                nextMode
            )
        }
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

@Composable
private fun QSListeningModeDialogContent(
    device: AppleDevice,
    onModeSelected: (Int) -> Unit,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    onConversationAwarenessChanged: (Boolean) -> Unit
) {
    val state by device.state.collectAsState()
    val metadata by device.metadata.collectAsState()

    val spec = AirPodsSpecs.getSpec(metadata.model)
    val conversationAwarenessCapability =
        spec.baseCapabilities.contains(BaseCapability.CONVERSATION_AWARENESS)
    val showOffListeningMode =
        state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1
    val listeningModeValue =
        state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 2
    val adaptiveStrength =
        state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50
    val conversationAwarenessChecked =
        state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte()

    val budBatteries = state.battery
        .filter { it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT }
        .mapNotNull { it.level.takeIf { lvl -> lvl in 1..100 } }

    val design = LocalDesign.current
    val dialogContainerColor = when (design.system) {
        DesignSystem.Apple -> if (design.colorStyle == ColorStyle.Custom) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        DesignSystem.Material -> MaterialTheme.colorScheme.surfaceContainer
    }
    val badgeContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = dialogContainerColor,
        tonalElevation = if (design.system == DesignSystem.Material) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = metadata.name.ifBlank { "AirPods" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (budBatteries.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeContainerColor
                    ) {
                        Text(
                            text = "${budBatteries.min()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            NoiseControlSettings(
                modifier = if (design.system == DesignSystem.Apple) {
                    Modifier.fillMaxWidth().height(100.dp)
                } else {
                    Modifier.fillMaxWidth()
                },
                showOffListeningMode = showOffListeningMode,
                noiseControlModeValue = listeningModeValue,
                onNoiseControlModeChanged = onModeSelected,
                adaptiveStrength = adaptiveStrength,
                onAdaptiveStrengthChanged = onAdaptiveStrengthChanged,
                showLabels = true
            )

            if (conversationAwarenessCapability) {
                Spacer(modifier = Modifier.height(12.dp))
                StyledList {
                    styledToggle(
                        label = stringResource(R.string.conversation_awareness),
                        checked = conversationAwarenessChecked,
                        onCheckedChange = onConversationAwarenessChanged
                    )
                }
            }
        }
    }
}

private class QSDialogLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
