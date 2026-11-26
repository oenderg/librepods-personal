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

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.data.app.AccessibilitySettings
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.NightTheme
import me.kavishdevar.librepods.presentation.components.apple.ListeningModeDialogContent
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.services.LibrePodsService

class ListeningModeDialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val service = LibrePodsService.instance
        val appleDevices = service?.devices?.value?.values?.filterIsInstance<AppleDevice>().orEmpty()
        val connected = appleDevices.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

        if (connected == null) {
            val target = appleDevices.firstOrNull()
            if (target != null) {
                val name = target.metadata.value.name.ifBlank { "AirPods" }
                Toast.makeText(this, "${getString(R.string.reconnecting)} $name", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO).launch { target.connect() }
            } else {
                openMainApp()
            }
            finish()
            return
        }

        val appSettingsFlow = (application as? LibrePodsApplication)?.appDataRepository?.settings

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
                accessibilitySettings = appSettings?.accessibilitySettings ?: AccessibilitySettings(),
                fontSettings = appSettings?.fontSettings ?: FontSettings(),
                darkTheme = darkTheme
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                    ) {
                        ListeningModeDialogContent(
                            device = connected,
                            onModeSelected = { newMode ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    connected.setControlCommand(
                                        ControlCommandIdentifier.LISTENING_MODE,
                                        newMode
                                    )
                                }
                            },
                            onAdaptiveStrengthChanged = { strength ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    connected.setControlCommand(
                                        ControlCommandIdentifier.AUTO_ANC_STRENGTH,
                                        strength
                                    )
                                }
                            },
                            onConversationAwarenessChanged = { enabled ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    connected.setControlCommand(
                                        ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
                                        enabled
                                    )
                                }
                            },
                            onOpenApp = {
                                openMainApp()
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
