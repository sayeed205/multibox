package com.hitarashi.multibox.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hitarashi.multibox.R
import com.hitarashi.multibox.data.model.Song
import com.hitarashi.multibox.data.service.player.BitPerfectUsbStatus
import com.hitarashi.multibox.data.usb.UsbDacState
import com.hitarashi.multibox.data.usb.UsbDirectDacManager
import java.util.Locale
import kotlin.math.log10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioInspectorSheet(
    song: Song,
    audioMimeType: String?,
    audioBitrate: Int?,
    audioSampleRate: Int?,
    audioBitDepth: Int?,
    isUsbDacActive: Boolean,
    isDspEffectsActive: Boolean,
    bitPerfectUsbStatus: BitPerfectUsbStatus?,
    usbDacManager: UsbDirectDacManager,
    onDismiss: () -> Unit
) {
    val dacState by usbDacManager.dacState.collectAsStateWithLifecycle()
    val volumePercent by usbDacManager.dacVolumePercent.collectAsStateWithLifecycle()

    val currentDb = if (volumePercent == 0) -64.0 else 20.0 * log10(volumePercent / 100.0)

    val cleanMime = (audioMimeType ?: song.mimeType)?.substringAfterLast('/')?.substringAfterLast('.')?.uppercase() ?: "PCM"
    val cleanSampleRate = audioSampleRate ?: song.sampleRate
    val cleanBitrate = audioBitrate ?: song.bitrate
    val cleanBitDepth = audioBitDepth

    val isHiRes = (cleanSampleRate != null && cleanSampleRate >= 88200) || (cleanBitDepth != null && cleanBitDepth >= 24)
    val isLossless = cleanMime.contains("FLAC") || cleanMime.contains("ALAC") || cleanMime.contains("WAV") || cleanMime.contains("AIFF") || cleanMime.contains("DSD")
    val isAnyBitPerfect = (isUsbDacActive || bitPerfectUsbStatus != null) && !isDspEffectsActive

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.audio_inspector_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.audio_inspector_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isAnyBitPerfect) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Bit-Perfect",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.audio_inspector_source),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isHiRes) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.2f),
                                contentColor = Color(0xFFFFB300)
                            ) {
                                Text(
                                    text = "HI-RES AUDIO",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (isLossless) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = "LOSSLESS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        QualityMetricCell(
                            label = stringResource(R.string.audio_inspector_format),
                            value = cleanMime.ifBlank { "PCM" }
                        )
                        QualityMetricCell(
                            label = stringResource(R.string.audio_inspector_sample_rate),
                            value = if (cleanSampleRate != null && cleanSampleRate > 0) "${cleanSampleRate / 1000.0} kHz" else "--"
                        )
                        QualityMetricCell(
                            label = stringResource(R.string.audio_inspector_bit_depth),
                            value = if (cleanBitDepth != null && cleanBitDepth > 0) "$cleanBitDepth-bit" else (if (isLossless) "16-bit" else "--")
                        )
                        QualityMetricCell(
                            label = stringResource(R.string.audio_inspector_bitrate),
                            value = if (cleanBitrate != null && cleanBitrate > 0) "${cleanBitrate / 1000} kbps" else "--"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.audio_inspector_signal_path),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SignalPathStep(
                        icon = Icons.Rounded.MusicNote,
                        stepTitle = stringResource(R.string.audio_inspector_step_source),
                        stepDescription = "$cleanMime • ${cleanSampleRate?.let { "${it / 1000.0} kHz" } ?: "Direct PCM"}",
                        isHighlight = false
                    )

                    SignalPathConnector()

                    SignalPathStep(
                        icon = Icons.Rounded.Tune,
                        stepTitle = stringResource(R.string.audio_inspector_step_dsp),
                        stepDescription = if (isDspEffectsActive) {
                            stringResource(R.string.audio_inspector_dsp_active)
                        } else {
                            stringResource(R.string.audio_inspector_dsp_bypass)
                        },
                        isHighlight = isDspEffectsActive
                    )

                    SignalPathConnector()

                    val targetDacRate = (dacState as? UsbDacState.Connected)?.sampleRate ?: usbDacManager.activeTargetSampleRate
                    val engineDescription = when {
                        isUsbDacActive -> {
                            if (cleanSampleRate != null && cleanSampleRate > targetDacRate && (cleanSampleRate % targetDacRate == 0)) {
                                val decimationRatio = cleanSampleRate / targetDacRate
                                stringResource(R.string.audio_inspector_engine_decimation, decimationRatio, "${targetDacRate / 1000}")
                            } else {
                                stringResource(R.string.audio_inspector_engine_bitperfect)
                            }
                        }
                        bitPerfectUsbStatus != null -> stringResource(
                            R.string.audio_inspector_engine_android_bitperfect,
                            "${bitPerfectUsbStatus.sampleRateHz / 1000.0}"
                        )
                        else -> stringResource(R.string.audio_inspector_engine_system)
                    }

                    SignalPathStep(
                        icon = Icons.Rounded.Speed,
                        stepTitle = stringResource(R.string.audio_inspector_step_engine),
                        stepDescription = engineDescription,
                        isHighlight = isUsbDacActive || bitPerfectUsbStatus != null
                    )

                    SignalPathConnector()

                    val deviceName = when {
                        isUsbDacActive -> (dacState as? UsbDacState.Connected)?.deviceName ?: "USB Audio DAC"
                        bitPerfectUsbStatus != null -> bitPerfectUsbStatus.deviceName.ifBlank { "USB Audio DAC" }
                        else -> stringResource(R.string.audio_inspector_output_system)
                    }
                    SignalPathStep(
                        icon = Icons.Rounded.Headphones,
                        stepTitle = stringResource(R.string.audio_inspector_step_output),
                        stepDescription = deviceName,
                        isHighlight = isUsbDacActive || bitPerfectUsbStatus != null
                    )
                }
            }

            if (isUsbDacActive) {
                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.audio_inspector_master_gain),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%.1f dB (%d%%)", currentDb, volumePercent),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = volumePercent.toFloat(),
                            onValueChange = { usbDacManager.setHardwareVolume(it.toInt()) },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalPathConnector() {
    HorizontalDivider(
        modifier = Modifier
            .padding(start = 18.dp)
            .height(12.dp)
            .width(2.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun QualityMetricCell(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SignalPathStep(
    icon: ImageVector,
    stepTitle: String,
    stepDescription: String,
    isHighlight: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isHighlight) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = stepTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stepDescription,
                style = MaterialTheme.typography.bodySmall,
                color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
