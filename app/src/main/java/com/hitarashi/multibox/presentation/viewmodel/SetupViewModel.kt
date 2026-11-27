package com.hitarashi.multibox.presentation.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hitarashi.multibox.R
import com.hitarashi.multibox.core.model.telegram.TelegramApiCredentials
import com.hitarashi.multibox.core.model.telegram.TelegramAuthGateway
import com.hitarashi.multibox.core.model.telegram.TelegramAuthState
import com.hitarashi.multibox.core.model.telegram.TelegramSessionUser
import com.hitarashi.multibox.data.backup.BackupManager
import com.hitarashi.multibox.data.backup.model.BackupTransferProgressUpdate
import com.hitarashi.multibox.data.backup.model.BackupOperationType
import com.hitarashi.multibox.data.backup.model.BackupSection
import com.hitarashi.multibox.data.backup.model.RestorePlan
import com.hitarashi.multibox.data.backup.model.RestoreResult
import com.hitarashi.multibox.data.preferences.AppThemeMode
import com.hitarashi.multibox.data.preferences.ThemePreferencesRepository
import com.hitarashi.multibox.data.preferences.UserPreferencesRepository
import com.hitarashi.multibox.data.repository.MusicRepository
import com.hitarashi.multibox.data.worker.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

data class SetupUiState(
    val mediaPermissionGranted: Boolean = false,
    val notificationsPermissionGranted: Boolean = false,
    val isLoadingDirectories: Boolean = false,
    val blockedDirectories: Set<String> = emptySet(),
    val libraryNavigationMode: String = "tab_row",
    val navBarStyle: String = "default",
    val navBarCornerRadius: Int = 28,
    val externalLyricsEnabled: Boolean = false,
    val externalArtistImagesEnabled: Boolean = false,
    val alarmsPermissionGranted: Boolean = false,
    val appThemeMode: String = AppThemeMode.DARK,
    val isInspectingBackup: Boolean = false,
    val isRestoringBackup: Boolean = false,
    val restorePlan: RestorePlan? = null,
    val backupTransferProgress: BackupTransferProgressUpdate? = null,
    val telegramAuthState: TelegramAuthState = TelegramAuthState.Initializing,
    val telegramUser: TelegramSessionUser? = null,
    val telegramApiIdInput: String = "",
    val telegramApiHashInput: String = "",
    val telegramPhoneInput: String = "",
    val telegramCodeInput: String = "",
    val telegramPasswordInput: String = "",
    val telegramEditingPhone: Boolean = false,
    val telegramIsBusy: Boolean = false,
    val telegramInlineError: String? = null
) {
    val allPermissionsGranted: Boolean
        get() {
            val mediaOk = mediaPermissionGranted
            val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationsPermissionGranted else true
            return mediaOk && notificationsOk
        }

    val telegramAuthenticated: Boolean
        get() = telegramAuthState is TelegramAuthState.Ready

    val allSetupGatesSatisfied: Boolean
        get() = allPermissionsGranted && telegramAuthenticated
}

sealed interface SetupEvent {
    data class Message(val value: String) : SetupEvent
    data class RestoreCompleted(val message: String) : SetupEvent
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val syncManager: SyncManager,
    private val backupManager: BackupManager,
    private val musicRepository: MusicRepository,
    private val telegramAuthGateway: TelegramAuthGateway,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = Channel<SetupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    /**
     * Expose sync progress for UI to show during initial setup
     */
    /**
     * Expose sync progress for UI to show during initial setup
     */
    val isSyncing = syncManager.isSyncing

    private val fileExplorerStateHolder = FileExplorerStateHolder(userPreferencesRepository, viewModelScope, context)

    val currentPath = fileExplorerStateHolder.currentPath
    val currentDirectoryChildren = fileExplorerStateHolder.currentDirectoryChildren
    val blockedDirectories = fileExplorerStateHolder.blockedDirectories
    val availableStorages = fileExplorerStateHolder.availableStorages
    val selectedStorageIndex = fileExplorerStateHolder.selectedStorageIndex
    val isLoadingDirectories = fileExplorerStateHolder.isLoading
    val isExplorerPriming = fileExplorerStateHolder.isPrimingExplorer
    val isExplorerReady = fileExplorerStateHolder.isExplorerReady
    val isCurrentDirectoryResolved = fileExplorerStateHolder.isCurrentDirectoryResolved
    private var hasPendingDirectoryRuleChanges = false
    private var latestDirectoryRuleUpdateJob: Job? = null

    init {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
                themePreferencesRepository.initializeAppThemeMode(AppThemeMode.DARK)
            }
        }

        viewModelScope.launch {
            combine<Any?, SetupPrefsUpdate>(
                userPreferencesRepository.blockedDirectoriesFlow,
                userPreferencesRepository.libraryNavigationModeFlow,
                userPreferencesRepository.navBarStyleFlow,
                userPreferencesRepository.navBarCornerRadiusFlow,
                userPreferencesRepository.externalLyricsEnabledFlow,
                userPreferencesRepository.externalArtistImagesEnabledFlow,
                themePreferencesRepository.appThemeModeFlow
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val blockedDirectories = values[0] as Set<String>
                SetupPrefsUpdate(
                    blocked = blockedDirectories,
                    mode = values[1] as String,
                    style = values[2] as String,
                    radius = values[3] as Int,
                    externalLyricsEnabled = values[4] as Boolean,
                    externalArtistImagesEnabled = values[5] as Boolean,
                    appThemeMode = values[6] as String
                )
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        blockedDirectories = update.blocked,
                        libraryNavigationMode = update.mode,
                        navBarStyle = update.style,
                        navBarCornerRadius = update.radius,
                        externalLyricsEnabled = update.externalLyricsEnabled,
                        externalArtistImagesEnabled = update.externalArtistImagesEnabled,
                        appThemeMode = update.appThemeMode
                    )
                }
            }
        }

        viewModelScope.launch {
            fileExplorerStateHolder.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoadingDirectories = loading) }
            }
        }

        viewModelScope.launch {
            telegramAuthGateway.ensureInitialized()
        }

        viewModelScope.launch {
            telegramAuthGateway.authState.collect { authState ->
                _uiState.update { state ->
                    state.copy(
                        telegramAuthState = authState,
                        telegramIsBusy = authState is TelegramAuthState.LoggingOut,
                        telegramEditingPhone = if (authState is TelegramAuthState.WaitingForPhoneNumber ||
                            authState is TelegramAuthState.Ready
                        ) {
                            false
                        } else {
                            state.telegramEditingPhone
                        },
                        telegramCodeInput = if (authState is TelegramAuthState.Ready ||
                            authState is TelegramAuthState.WaitingForPhoneNumber
                        ) {
                            ""
                        } else {
                            state.telegramCodeInput
                        },
                        telegramPasswordInput = if (authState is TelegramAuthState.Ready ||
                            authState is TelegramAuthState.WaitingForPhoneNumber
                        ) {
                            ""
                        } else {
                            state.telegramPasswordInput
                        },
                        telegramInlineError = if (authState is TelegramAuthState.Ready) {
                            null
                        } else {
                            state.telegramInlineError
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            telegramAuthGateway.currentUser.collect { user ->
                _uiState.update { it.copy(telegramUser = user) }
            }
        }

        viewModelScope.launch {
            telegramAuthGateway.authErrors.collect { errorMessage ->
                _uiState.update {
                    it.copy(
                        telegramIsBusy = false,
                        telegramInlineError = errorMessage
                    )
                }
            }
        }
    }
    
    private data class SetupPrefsUpdate(
        val blocked: Set<String>,
        val mode: String,
        val style: String,
        val radius: Int,
        val externalLyricsEnabled: Boolean,
        val externalArtistImagesEnabled: Boolean,
        val appThemeMode: String
    )

    fun checkPermissions(context: Context) {
        val mediaPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val notificationsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val alarmsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        _uiState.update {
            it.copy(
                mediaPermissionGranted = mediaPermissionGranted,
                notificationsPermissionGranted = notificationsPermissionGranted,
                alarmsPermissionGranted = alarmsPermissionGranted
            )
        }
    }

    fun loadMusicDirectories() {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
            }

            userPreferencesRepository.blockedDirectoriesFlow.first().let { blocked ->
                _uiState.update { it.copy(blockedDirectories = blocked) }
            }
            fileExplorerStateHolder.primeExplorerRoot()?.join()
        }
    }

    fun toggleDirectoryAllowed(file: File) {
        hasPendingDirectoryRuleChanges = true
        latestDirectoryRuleUpdateJob = viewModelScope.launch {
            fileExplorerStateHolder.toggleDirectoryAllowed(file)
        }
    }

    fun applyPendingDirectoryRuleChanges() {
        if (!hasPendingDirectoryRuleChanges) return
        hasPendingDirectoryRuleChanges = false
        viewModelScope.launch {
            latestDirectoryRuleUpdateJob?.join()
            syncManager.forceRefresh()
        }
    }

    fun loadDirectory(file: File) {
        fileExplorerStateHolder.loadDirectory(file)
    }

    fun selectStorage(index: Int) {
        fileExplorerStateHolder.selectStorage(index)
    }

    fun refreshAvailableStorages() {
        fileExplorerStateHolder.refreshAvailableStorages()
    }

    fun refreshCurrentDirectory() {
        fileExplorerStateHolder.refreshCurrentDirectory()
    }

    fun primeExplorer() {
        fileExplorerStateHolder.primeExplorerRoot()
    }

    fun openExplorer() {
        fileExplorerStateHolder.openExplorerRoot()
    }

    fun navigateUp() {
        fileExplorerStateHolder.navigateUp()
    }

    fun isAtRoot(): Boolean = fileExplorerStateHolder.isAtRoot()

    fun explorerRoot(): File = fileExplorerStateHolder.rootDirectory()

    fun setLibraryNavigationMode(mode: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLibraryNavigationMode(mode)
        }
    }

    fun setNavBarStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarStyle(style)
        }
    }

    fun setNavBarCornerRadius(radius: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarCornerRadius(radius)
        }
    }

    fun setExternalLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setExternalLyricsEnabled(enabled)
        }
    }

    fun setExternalArtistImagesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setExternalArtistImagesEnabled(enabled)
        }
    }

    fun setAppThemeMode(mode: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppThemeMode(mode)
        }
    }

    fun setSetupComplete() {
        viewModelScope.launch {
            completeSetup(syncAfter = true)
        }
    }
    
    /**
     * Retry the initial sync if it failed.
     * Can be called from UI when user wants to retry after a failure.
     */
    fun retrySync() {
        viewModelScope.launch {
            syncManager.fullSync(deepScan = false)
        }
    }

    fun inspectBackupFile(uri: Uri) {
        if (_uiState.value.isInspectingBackup || _uiState.value.isRestoringBackup) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspectingBackup = true,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
            val result = backupManager.inspectBackup(uri)
            result.fold(
                onSuccess = { plan ->
                    _uiState.update {
                        it.copy(
                            isInspectingBackup = false,
                            restorePlan = plan
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isInspectingBackup = false) }
                    _events.send(
                        SetupEvent.Message(
                            context.getString(
                                R.string.backup_invalid_format,
                                error.localizedMessage ?: context.getString(R.string.error_unknown),
                            )
                        )
                    )
                }
            )
        }
    }

    fun updateRestorePlanSelection(selectedModules: Set<BackupSection>) {
        _uiState.update { state ->
            state.restorePlan?.let { plan ->
                state.copy(restorePlan = plan.copy(selectedModules = selectedModules))
            } ?: state
        }
    }

    fun clearRestorePlan() {
        _uiState.update {
            it.copy(
                restorePlan = null,
                isInspectingBackup = false,
                isRestoringBackup = false,
                backupTransferProgress = null
            )
        }
    }

    fun restoreFromPlan(uri: Uri) {
        val plan = _uiState.value.restorePlan ?: return
        if (plan.selectedModules.isEmpty() || _uiState.value.isRestoringBackup) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRestoringBackup = true,
                    backupTransferProgress = BackupTransferProgressUpdate(
                        operation = BackupOperationType.IMPORT,
                        step = 0,
                        totalSteps = 1,
                        title = context.getString(R.string.backup_progress_preparing_restore),
                        detail = context.getString(R.string.backup_progress_starting_task),
                    )
                )
            }

            val result = backupManager.restore(uri, plan) { progress ->
                _uiState.update { state -> state.copy(backupTransferProgress = progress) }
            }

            when (result) {
                is RestoreResult.Success -> {
                    _events.send(SetupEvent.RestoreCompleted(context.getString(R.string.restore_completed_success)))
                }
                is RestoreResult.PartialFailure -> {
                    val canFinishSetup = result.succeeded.isNotEmpty() || !result.rolledBack
                    if (canFinishSetup) {
                        _events.send(
                            SetupEvent.RestoreCompleted(
                                context.getString(R.string.restore_completed_partial_issues),
                            )
                        )
                    } else {
                        _events.send(
                            SetupEvent.Message(
                                context.getString(
                                    R.string.restore_could_not_complete,
                                    result.failed.values.joinToString(),
                                ),
                            )
                        )
                    }
                }
                is RestoreResult.TotalFailure -> {
                    _events.send(SetupEvent.Message(context.getString(R.string.restore_failed_format, result.error)))
                }
            }

            _uiState.update {
                it.copy(
                    isRestoringBackup = false,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
        }
    }

    private suspend fun completeSetup(syncAfter: Boolean) {
        userPreferencesRepository.setInitialSetupDone(true)
        if (syncAfter) {
            syncManager.fullSync(deepScan = false)
        }
    }

    fun onTelegramApiIdChanged(value: String) {
        _uiState.update {
            it.copy(
                telegramApiIdInput = value.filter(Char::isDigit).take(12),
                telegramInlineError = null
            )
        }
    }

    fun onTelegramApiHashChanged(value: String) {
        _uiState.update {
            it.copy(
                telegramApiHashInput = value.trim(),
                telegramInlineError = null
            )
        }
    }

    fun onTelegramPhoneChanged(value: String) {
        val trimmed = value.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter(Char::isDigit)
        val normalized = if (hasPlus) "+$digits" else digits
        _uiState.update {
            it.copy(
                telegramPhoneInput = normalized,
                telegramInlineError = null
            )
        }
    }

    fun onTelegramCodeChanged(value: String) {
        _uiState.update {
            it.copy(
                telegramCodeInput = value.filter(Char::isDigit).take(8),
                telegramInlineError = null
            )
        }
    }

    fun onTelegramPasswordChanged(value: String) {
        _uiState.update {
            it.copy(
                telegramPasswordInput = value,
                telegramInlineError = null
            )
        }
    }

    fun enableTelegramPhoneEditMode() {
        _uiState.update {
            it.copy(
                telegramEditingPhone = true,
                telegramCodeInput = "",
                telegramPasswordInput = "",
                telegramInlineError = null
            )
        }
    }

    fun clearTelegramError() {
        _uiState.update { it.copy(telegramInlineError = null) }
    }

    fun retryTelegramInitialization() {
        viewModelScope.launch {
            _uiState.update { it.copy(telegramIsBusy = true, telegramInlineError = null) }
            telegramAuthGateway.ensureInitialized()
            _uiState.update { it.copy(telegramIsBusy = false) }
        }
    }

    fun submitTelegramApiCredentials() {
        val state = _uiState.value
        val apiId = state.telegramApiIdInput.toIntOrNull() ?: 0
        val creds = TelegramApiCredentials(apiId = apiId, apiHash = state.telegramApiHashInput)
        runTelegramAction {
            telegramAuthGateway.saveRuntimeCredentials(creds)
        }
    }

    fun submitTelegramPhoneNumber() {
        val phone = _uiState.value.telegramPhoneInput
        runTelegramAction {
            telegramAuthGateway.sendPhoneNumber(phone)
        }
    }

    fun submitTelegramCode() {
        val code = _uiState.value.telegramCodeInput
        runTelegramAction {
            telegramAuthGateway.checkAuthenticationCode(code)
        }
    }

    fun submitTelegramPassword() {
        val password = _uiState.value.telegramPasswordInput
        runTelegramAction {
            telegramAuthGateway.checkAuthenticationPassword(password)
        }
    }

    fun logoutTelegram() {
        runTelegramAction {
            telegramAuthGateway.logout()
        }
    }

    private fun runTelegramAction(action: suspend () -> Result<Unit>) {
        if (_uiState.value.telegramIsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(telegramIsBusy = true, telegramInlineError = null) }
            val result = action()
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            telegramIsBusy = false,
                            telegramEditingPhone = false,
                            telegramInlineError = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            telegramIsBusy = false,
                            telegramInlineError = error.message ?: "Telegram login failed."
                        )
                    }
                }
            )
        }
    }
}
