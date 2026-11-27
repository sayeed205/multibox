package com.hitarashi.multibox.di

import com.hitarashi.multibox.core.model.di.ApplicationScope
import com.hitarashi.multibox.core.model.dsp.DspLogger
import com.hitarashi.multibox.core.model.dsp.EqualizerSettingsStore
import com.hitarashi.multibox.core.model.dsp.UsbDacPreferences
import com.hitarashi.multibox.data.logging.PlayerLogBuffer
import com.hitarashi.multibox.data.preferences.EqualizerPreferencesRepository
import com.hitarashi.multibox.data.preferences.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreBindingsModule {

    @Singleton
    @Provides
    @ApplicationScope
    fun provideCoreApplicationScope(@AppScope scope: CoroutineScope): CoroutineScope = scope

    @Singleton
    @Provides
    fun provideDspLogger(playerLogBuffer: PlayerLogBuffer): DspLogger =
        DspLogger { message -> playerLogBuffer.logDac(message) }

    @Singleton
    @Provides
    fun provideUsbDacPreferences(
        userPreferencesRepository: UserPreferencesRepository
    ): UsbDacPreferences = object : UsbDacPreferences {
        override val exclusiveUsbAudioEnabledFlow: Flow<Boolean> =
            userPreferencesRepository.exclusiveUsbAudioEnabledFlow
    }

    @Singleton
    @Provides
    fun provideEqualizerSettingsStore(
        repo: EqualizerPreferencesRepository
    ): EqualizerSettingsStore = object : EqualizerSettingsStore {
        override val equalizerEnabledFlow: Flow<Boolean> = repo.equalizerEnabledFlow
        override val equalizerPresetFlow: Flow<String> = repo.equalizerPresetFlow
        override val equalizerCustomBandsFlow: Flow<List<Int>> = repo.equalizerCustomBandsFlow
        override val bassBoostEnabledFlow: Flow<Boolean> = repo.bassBoostEnabledFlow
        override val bassBoostStrengthFlow: Flow<Int> = repo.bassBoostStrengthFlow
        override val virtualizerEnabledFlow: Flow<Boolean> = repo.virtualizerEnabledFlow
        override val virtualizerStrengthFlow: Flow<Int> = repo.virtualizerStrengthFlow
        override val loudnessEnhancerEnabledFlow: Flow<Boolean> = repo.loudnessEnhancerEnabledFlow
        override val loudnessEnhancerStrengthFlow: Flow<Int> = repo.loudnessEnhancerStrengthFlow

        override suspend fun findCustomPresetBandLevels(presetName: String): List<Int>? =
            repo.customPresetsFlow.first().find { it.name == presetName }?.bandLevels
    }
}
