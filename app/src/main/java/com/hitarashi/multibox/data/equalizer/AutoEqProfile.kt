package com.hitarashi.multibox.data.equalizer

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Represents an AutoEQ headphone target compensation profile.
 * Band gains map to standard 10-band equalizer frequencies:
 * 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
 * Values are normalized from -15 to +15 dB.
 */
@Serializable
@Immutable
data class AutoEqProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val formFactor: String,
    val bandGains: List<Int>
) {
    val displayName: String
        get() = "$manufacturer $model"

    fun toEqualizerPreset(): EqualizerPreset {
        return EqualizerPreset(
            name = "autoeq_$id",
            displayName = displayName,
            bandLevels = bandGains,
            isCustom = true
        )
    }
}
