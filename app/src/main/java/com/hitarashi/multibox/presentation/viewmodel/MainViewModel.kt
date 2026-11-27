package com.hitarashi.multibox.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hitarashi.multibox.core.model.telegram.TelegramAuthGateway
import com.hitarashi.multibox.core.model.telegram.TelegramAuthState
import com.hitarashi.multibox.data.preferences.UserPreferencesRepository
import com.hitarashi.multibox.data.repository.MusicRepository
import com.hitarashi.multibox.data.worker.SyncManager
import com.hitarashi.multibox.data.worker.SyncProgress
import com.hitarashi.multibox.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncManager: SyncManager,
    musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val telegramAuthGateway: TelegramAuthGateway
) : ViewModel() {

    val isSetupComplete: StateFlow<Boolean?> = userPreferencesRepository.initialSetupDoneFlow
        .map { it as Boolean? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    init {
        viewModelScope.launch {
            telegramAuthGateway.ensureInitialized()
        }
        viewModelScope.launch {
            combine(
                isSetupComplete.filterNotNull(),
                telegramAuthGateway.authState
            ) { setupDone, authState ->
                setupDone to authState
            }.collect { (setupDone, authState) ->
                if (setupDone &&
                    (authState is TelegramAuthState.WaitingForPhoneNumber ||
                        authState is TelegramAuthState.WaitingForApiCredentials)
                ) {
                    LogUtils.w(this@MainViewModel, "Telegram session unauthenticated; returning to Setup gate.")
                    userPreferencesRepository.setInitialSetupDone(false)
                }
            }
        }
    }

    val hasCompletedInitialSync: StateFlow<Boolean> = userPreferencesRepository.lastSyncTimestampFlow
        .map { it > 0L }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    /**
     * A Flow that emits `true` if the SyncWorker is queued or running.
     * Ideal for showing a loading indicator.
     */
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /**
     * Flow that exposes detailed sync progress including file count and phase.
     */
    val syncProgress: StateFlow<SyncProgress> = syncManager.syncProgress
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncProgress()
        )

    /**
     * Emits once each time a library sync ends in failure, so the UI can surface a
     * one-shot toast (the progress flow alone silently reverts to idle on failure).
     */
    val syncFailed: Flow<Unit> = syncManager.syncFailed

    /**
     * A Flow that emits `true` if the Room database has no songs.
     * Helps us know whether this is the first time the app is opened.
     */
    val isLibraryEmpty: StateFlow<Boolean> = musicRepository
        .getAudioFiles()
        .map { it.isEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /**
     * Function to start syncing the music library.
     * Should be called after permissions have been granted.
     */
    fun startSync() {
        LogUtils.i(this, "startSync called")
        viewModelScope.launch {
            if (isSetupComplete.value == true) {
                syncManager.sync()
            }
        }
    }

    /** Re-runs the library sync after a failure (e.g. from a retry affordance). */
    fun retrySync() {
        viewModelScope.launch { syncManager.sync() }
    }
}
