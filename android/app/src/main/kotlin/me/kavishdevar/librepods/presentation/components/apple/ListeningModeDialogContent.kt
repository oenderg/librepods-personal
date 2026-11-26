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

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.design.LocalDesign

@Composable
fun ListeningModeDialogContent(
    device: AppleDevice,
    onModeSelected: (Int) -> Unit,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    onConversationAwarenessChanged: (Boolean) -> Unit,
    onOpenApp: (() -> Unit)? = null
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                if (onOpenApp != null) {
                    Surface(
                        onClick = onOpenApp,
                        shape = RoundedCornerShape(12.dp),
                        color = badgeContainerColor
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = stringResource(R.string.open_app),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .size(16.dp)
                        )
                    }
                }

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
