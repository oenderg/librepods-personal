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

package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.activities.ShortcutActionActivity
import me.kavishdevar.librepods.presentation.activities.ToggleListeningModeActivity

object AppShortcutsManager {
    private const val TAG = "AppShortcutsManager"
    private const val MAX_DISPLAYED_SHORTCUTS = 4

    @Volatile
    private var lastSignature: String? = null

    fun update(context: Context, devices: Collection<Device<*, *, *>>) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        val maxCount = shortcutManager.maxShortcutCountPerActivity
            .coerceIn(1, MAX_DISPLAYED_SHORTCUTS)

        val appleDevices = devices.filterIsInstance<AppleDevice>()
        val connected = appleDevices.firstOrNull {
            it.connectionState.value == ConnectionState.CONNECTED
        }

        val signature = buildString {
            if (connected != null) {
                val meta = connected.metadata.value
                append("connected:")
                append(connected.macAddress.value)
                append(':')
                append(meta.model.name)
                append(':')
                append(meta.name)
            } else {
                append("disconnected:")
                appleDevices.forEach { dev ->
                    append(dev.macAddress.value)
                    append('=')
                    append(dev.metadata.value.name)
                    append(';')
                }
            }
        }
        if (signature == lastSignature) return

        val shortcuts = mutableListOf<ShortcutInfo>()

        if (connected != null) {
            val meta = connected.metadata.value
            val spec = AirPodsSpecs.getSpec(meta.model)
            val caps = spec.baseCapabilities
            val mac = connected.macAddress.value
            val name = meta.name.ifBlank { "AirPods" }

            if (caps.contains(BaseCapability.LISTENING_MODE)) {
                shortcuts.add(
                    modeShortcut(
                        context = context,
                        id = "mode_anc",
                        label = context.getString(R.string.noise_cancellation),
                        iconRes = R.drawable.ic_noise_cancellation,
                        mode = NoiseControlMode.NOISE_CANCELLATION.ordinal + 1,
                        mac = mac,
                        rank = shortcuts.size
                    )
                )
                if (caps.contains(BaseCapability.ADAPTIVE_AUDIO)) {
                    shortcuts.add(
                        modeShortcut(
                            context = context,
                            id = "mode_adaptive",
                            label = context.getString(R.string.adaptive),
                            iconRes = R.drawable.ic_adaptive,
                            mode = NoiseControlMode.ADAPTIVE.ordinal + 1,
                            mac = mac,
                            rank = shortcuts.size
                        )
                    )
                }
                shortcuts.add(
                    modeShortcut(
                        context = context,
                        id = "mode_transparency",
                        label = context.getString(R.string.transparency),
                        iconRes = R.drawable.ic_transparency,
                        mode = NoiseControlMode.TRANSPARENCY.ordinal + 1,
                        mac = mac,
                        rank = shortcuts.size
                    )
                )
                if (!caps.contains(BaseCapability.ADAPTIVE_AUDIO)) {
                    shortcuts.add(toggleModeShortcut(context, rank = shortcuts.size))
                }
            }

            shortcuts.add(
                ShortcutInfo.Builder(context, "disconnect_${mac.replace(":", "")}")
                    .setShortLabel(context.getString(R.string.disconnect))
                    .setLongLabel("${context.getString(R.string.disconnect)} $name")
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_airpods))
                    .setRank(shortcuts.size)
                    .setIntent(
                        Intent(context, ShortcutActionActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            putExtra(ShortcutActionActivity.EXTRA_ACTION, ShortcutActionActivity.ACTION_DISCONNECT)
                            putExtra(ShortcutActionActivity.EXTRA_MAC, mac)
                        }
                    )
                    .build()
            )
        } else {
            appleDevices.take((maxCount - 1).coerceAtLeast(1)).forEach { dev ->
                val mac = dev.macAddress.value
                val name = dev.metadata.value.name.ifBlank { "AirPods" }
                val shortLabel = if (appleDevices.size == 1) {
                    context.getString(R.string.connect)
                } else {
                    name
                }
                shortcuts.add(
                    ShortcutInfo.Builder(context, "connect_${mac.replace(":", "")}")
                        .setShortLabel(shortLabel)
                        .setLongLabel("${context.getString(R.string.connect)} $name")
                        .setIcon(Icon.createWithResource(context, R.drawable.ic_airpods))
                        .setRank(shortcuts.size)
                        .setIntent(
                            Intent(context, ShortcutActionActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                putExtra(ShortcutActionActivity.EXTRA_ACTION, ShortcutActionActivity.ACTION_CONNECT)
                                putExtra(ShortcutActionActivity.EXTRA_MAC, mac)
                            }
                        )
                        .build()
                )
            }
            shortcuts.add(toggleModeShortcut(context, rank = shortcuts.size))
        }

        val finalList = shortcuts.take(maxCount)
        try {
            shortcutManager.dynamicShortcuts = finalList
            lastSignature = signature
            Log.d(TAG, "Updated dynamic shortcuts (${finalList.size}): ${finalList.map { it.id }}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update dynamic shortcuts", e)
        }
    }

    private fun modeShortcut(
        context: Context,
        id: String,
        label: String,
        iconRes: Int,
        mode: Int,
        mac: String,
        rank: Int
    ): ShortcutInfo {
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, iconRes))
            .setRank(rank)
            .setIntent(
                Intent(context, ShortcutActionActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(ShortcutActionActivity.EXTRA_ACTION, ShortcutActionActivity.ACTION_SET_MODE)
                    putExtra(ShortcutActionActivity.EXTRA_MODE, mode)
                    putExtra(ShortcutActionActivity.EXTRA_MAC, mac)
                }
            )
            .build()
    }

    private fun toggleModeShortcut(context: Context, rank: Int): ShortcutInfo {
        val label = context.getString(R.string.toggle_listening_mode)
        return ShortcutInfo.Builder(context, "toggle_listening_mode")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_noise_cancellation))
            .setRank(rank)
            .setIntent(
                Intent(context, ToggleListeningModeActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                }
            )
            .build()
    }
}
