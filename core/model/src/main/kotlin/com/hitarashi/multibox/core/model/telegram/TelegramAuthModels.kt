package com.hitarashi.multibox.core.model.telegram

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TDLib-agnostic authentication states surfaced to onboarding, settings, and offline guards.
 */
sealed interface TelegramAuthState {
    data object Initializing : TelegramAuthState

    /**
     * Build-time TG_API_ID / TG_API_HASH were omitted and no runtime override has been saved yet.
     */
    data class WaitingForApiCredentials(val message: String? = null) : TelegramAuthState

    data object WaitingForPhoneNumber : TelegramAuthState

    data class WaitingForCode(
        val codeHint: String? = null,
        val timeoutSeconds: Int = 0
    ) : TelegramAuthState

    data class WaitingForPassword(
        val passwordHint: String = "",
        val hasRecoveryEmail: Boolean = false
    ) : TelegramAuthState

    data object Ready : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    data object Closed : TelegramAuthState
}

/**
 * API credentials used to initialize TDLib Parameters when build-time keys are absent or overridden.
 */
data class TelegramApiCredentials(
    val apiId: Int,
    val apiHash: String
) {
    val isValid: Boolean
        get() = apiId > 0 && apiHash.isNotBlank()
}

/**
 * Authenticated Telegram account summary for display on Setup and Settings screens.
 */
data class TelegramSessionUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phoneNumber: String?
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { username?.let { "@$it" } ?: phoneNumber ?: "Telegram User" }
}

/**
 * Deep seam hiding TDLib initialization, state machine updates, and session auth from feature UI.
 */
interface TelegramAuthGateway {
    val authState: StateFlow<TelegramAuthState>
    val currentUser: StateFlow<TelegramSessionUser?>
    val authErrors: SharedFlow<String>
    val hasBuildCredentials: Boolean

    suspend fun ensureInitialized(overrideCredentials: TelegramApiCredentials? = null)
    suspend fun saveRuntimeCredentials(credentials: TelegramApiCredentials): Result<Unit>
    suspend fun sendPhoneNumber(phoneNumber: String): Result<Unit>
    suspend fun checkAuthenticationCode(code: String): Result<Unit>
    suspend fun checkAuthenticationPassword(password: String): Result<Unit>
    suspend fun logout(): Result<Unit>
}
