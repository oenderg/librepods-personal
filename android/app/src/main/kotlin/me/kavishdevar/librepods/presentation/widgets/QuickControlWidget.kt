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

package me.kavishdevar.librepods.presentation.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.activities.ListeningModeDialogActivity
import me.kavishdevar.librepods.services.LibrePodsService

class QuickControlWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAppWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAppWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val connectedDevice = LibrePodsService.instance?.devices?.value?.values
                ?.filterIsInstance<AppleDevice>()
                ?.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

            val activeMode = connectedDevice?.state?.value
                ?.controlStates?.get(ControlCommandIdentifier.LISTENING_MODE)
                ?.getOrNull(0)?.toInt()

            val iconRes = if (connectedDevice != null) {
                when (activeMode) {
                    NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.ic_transparency
                    NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.ic_adaptive
                    NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.ic_noise_cancellation
                    NoiseControlMode.OFF.ordinal + 1 -> R.drawable.ic_noise_cancellation
                    else -> R.drawable.ic_airpods
                }
            } else {
                R.drawable.ic_airpods
            }

            val budBattery = connectedDevice?.state?.value?.battery
                ?.filter { it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT }
                ?.mapNotNull { it.level.takeIf { lvl -> lvl in 1..100 } }
                ?.minOrNull()

            val label = when {
                connectedDevice == null -> context.getString(R.string.connect)
                budBattery != null -> "$budBattery%"
                else -> when (activeMode) {
                    NoiseControlMode.OFF.ordinal + 1 -> context.getString(R.string.off)
                    NoiseControlMode.TRANSPARENCY.ordinal + 1 -> context.getString(R.string.transparency)
                    NoiseControlMode.ADAPTIVE.ordinal + 1 -> context.getString(R.string.adaptive)
                    NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> context.getString(R.string.noise_cancellation)
                    else -> context.getString(R.string.app_name)
                }
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.quick_control_widget)
                views.setImageViewResource(R.id.quick_control_widget_icon, iconRes)
                views.setTextViewText(R.id.quick_control_widget_label, label)

                val launchIntent = Intent(context, ListeningModeDialogActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.quick_control_widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
