package com.hitarashi.multibox.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hitarashi.multibox.data.usb.UsbDacState
import com.hitarashi.multibox.data.usb.UsbDirectDacManager
import com.hitarashi.multibox.presentation.viewmodel.ColorSchemePair
import com.hitarashi.multibox.presentation.viewmodel.PlayerViewModel
import java.util.Locale
import kotlin.math.log10

@Composable
fun DacVolumeHud(
    usbDacManager: UsbDirectDacManager,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel? = null
) {
    val isVisible by usbDacManager.dacVolumeHudVisible.collectAsStateWithLifecycle()
    val volumePercent by usbDacManager.dacVolumePercent.collectAsStateWithLifecycle()
    val dacState by usbDacManager.dacState.collectAsStateWithLifecycle()

    val activePlayerSchemePair by playerViewModel?.activePlayerColorSchemePair?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<ColorSchemePair?>(null) }

    val baseScheme = MaterialTheme.colorScheme
    val isDark = baseScheme.background.luminance() < 0.5f
    val dynamicScheme = activePlayerSchemePair?.let { if (isDark) it.dark else it.light }
    val activeScheme = dynamicScheme ?: baseScheme

    val dacName = (dacState as? UsbDacState.Connected)?.deviceName ?: "USB DAC"
    val currentDb = if (volumePercent == 0) -64.0 else 20.0 * log10(volumePercent / 100.0)

    val volumeIcon = when {
        volumePercent == 0 -> Icons.AutoMirrored.Rounded.VolumeMute
        volumePercent < 50 -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }

    MaterialTheme(colorScheme = activeScheme) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 16.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(min = 290.dp, max = 390.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                val delta = (dragAmount / 4f).toInt()
                                if (delta != 0) {
                                    usbDacManager.adjustVolume(delta)
                                }
                            }
                        },
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = volumeIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = dacName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "BIT-PERFECT",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 1.sp,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "$volumePercent%",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f dB", currentDb),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { volumePercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}
