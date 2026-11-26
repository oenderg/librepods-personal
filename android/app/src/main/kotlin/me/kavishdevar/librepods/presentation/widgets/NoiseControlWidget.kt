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
import android.view.View
import android.widget.RemoteViews
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.data.app.LibrePodsIntents
import me.kavishdevar.librepods.services.LibrePodsService

class NoiseControlWidget : AppWidgetProvider() {
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
                ?.getOrNull(0)?.toInt() ?: -1
            val allowOff = connectedDevice?.state?.value
                ?.controlStates?.get(ControlCommandIdentifier.ALLOW_OFF_OPTION)
                ?.getOrNull(0)?.toInt() == 1

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.listening_mode_widget)

                views.setViewVisibility(
                    R.id.widget_off_button,
                    if (allowOff) View.VISIBLE else View.GONE
                )

                views.setInt(
                    R.id.widget_off_button,
                    "setBackgroundResource",
                    if (activeMode == 1) R.drawable.shape_widget_button_start_checked
                    else R.drawable.shape_widget_button_start
                )
                views.setInt(
                    R.id.widget_transparency_button,
                    "setBackgroundResource",
                    when {
                        !allowOff && activeMode == 3 -> R.drawable.shape_widget_button_start_checked
                        !allowOff -> R.drawable.shape_widget_button_start
                        activeMode == 3 -> R.drawable.shape_widget_button_middle_checked
                        else -> R.drawable.shape_widget_button_middle
                    }
                )
                views.setInt(
                    R.id.widget_adaptive_button,
                    "setBackgroundResource",
                    if (activeMode == 4) R.drawable.shape_widget_button_middle_checked
                    else R.drawable.shape_widget_button_middle
                )
                views.setInt(
                    R.id.widget_anc_button,
                    "setBackgroundResource",
                    if (activeMode == 2) R.drawable.shape_widget_button_end_checked
                    else R.drawable.shape_widget_button_end
                )

                fun modePendingIntent(mode: Int, slot: Int): PendingIntent {
                    val intent = Intent(context, LibrePodsService::class.java).apply {
                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        putExtra(LibrePodsIntents.Extras.ANC_MODE, mode)
                    }
                    return PendingIntent.getForegroundService(
                        context,
                        appWidgetId * 10 + slot,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }

                views.setOnClickPendingIntent(R.id.widget_off_button, modePendingIntent(1, 1))
                views.setOnClickPendingIntent(R.id.widget_transparency_button, modePendingIntent(3, 2))
                views.setOnClickPendingIntent(R.id.widget_adaptive_button, modePendingIntent(4, 3))
                views.setOnClickPendingIntent(R.id.widget_anc_button, modePendingIntent(2, 4))

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
