package com.hitarashi.multibox.core.model.dsp

import kotlinx.coroutines.flow.Flow

/**
 * Narrow logging sink for native/USB DAC diagnostics without exposing app logging buffers.
 */
fun interface DspLogger {
    fun logDac(message: String)
}

/**
 * Narrow preference reader for USB direct DAC activation state.
 */
interface UsbDacPreferences {
    val exclusiveUsbAudioEnabledFlow: Flow<Boolean>
}

/**
 * Narrow preference store required to hydrate the software DSP state.
 */
interface EqualizerSettingsStore {
    val equalizerEnabledFlow: Flow<Boolean>
    val equalizerPresetFlow: Flow<String>
    val equalizerCustomBandsFlow: Flow<List<Int>>
    val bassBoostEnabledFlow: Flow<Boolean>
    val bassBoostStrengthFlow: Flow<Int>
    val virtualizerEnabledFlow: Flow<Boolean>
    val virtualizerStrengthFlow: Flow<Int>
    val loudnessEnhancerEnabledFlow: Flow<Boolean>
    val loudnessEnhancerStrengthFlow: Flow<Int>

    suspend fun findCustomPresetBandLevels(presetName: String): List<Int>?
}
