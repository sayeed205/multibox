package com.hitarashi.multibox.core.telegram

import android.content.Context
import android.os.Build
import com.hitarashi.multibox.core.model.di.ApplicationScope
import com.hitarashi.multibox.core.model.telegram.TelegramApiCredentials
import com.hitarashi.multibox.core.model.telegram.TelegramAuthGateway
import com.hitarashi.multibox.core.model.telegram.TelegramAuthState
import com.hitarashi.multibox.core.model.telegram.TelegramSessionUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TdlibAuthGateway @Inject internal constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val credentialStore: TelegramCredentialStore
) : TelegramAuthGateway {

    private companion object {
        private const val AUTH_REQUEST_TIMEOUT_MS = 20_000L

        @Volatile
        private var nativeLoaded = false

        private fun ensureNativeLibraryLoaded(): Boolean {
            if (nativeLoaded) return true
            return try {
                System.loadLibrary("tdjni")
                nativeLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load TDLib native library (tdjni)")
                false
            }
        }
    }

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Initializing)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<TelegramSessionUser?>(null)
    override val currentUser: StateFlow<TelegramSessionUser?> = _currentUser.asStateFlow()

    private val _authErrors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val authErrors: SharedFlow<String> = _authErrors.asSharedFlow()

    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
    internal val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    override val hasBuildCredentials: Boolean
        get() = credentialStore.hasBuildCredentials

    @Volatile
    private var client: Client? = null

    @Volatile
    private var waitingForTdlibParams = false

    @Volatile
    private var recreateClientAfterClose = false

    private val updateHandler = Client.ResultHandler { update ->
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationUpdate(update.authorizationState)
            is TdApi.UpdateUser -> {
                val activeId = _currentUser.value?.id
                if (activeId == null || activeId == update.user.id) {
                    _currentUser.value = update.user.toSessionUser()
                }
                _updates.tryEmit(update)
            }
            is TdApi.Update -> _updates.tryEmit(update)
            is TdApi.Error -> reportError(update.code, update.message)
        }
    }

    override suspend fun ensureInitialized(overrideCredentials: TelegramApiCredentials?) {
        if (!ensureNativeLibraryLoaded()) {
            _authErrors.tryEmit("Native Telegram library could not be loaded on this device.")
            return
        }
        if (overrideCredentials != null && overrideCredentials.isValid) {
            credentialStore.saveCredentials(overrideCredentials)
        }
        val activeClient = ensureClientCreated() ?: return
        if (waitingForTdlibParams) {
            sendTdlibParameters(activeClient, overrideCredentials)
        }
    }

    override suspend fun saveRuntimeCredentials(credentials: TelegramApiCredentials): Result<Unit> {
        if (!credentials.isValid) {
            return Result.failure(IllegalArgumentException("Enter a valid numeric API ID and non-empty API Hash."))
        }
        credentialStore.saveCredentials(credentials)
        ensureInitialized(credentials)
        return Result.success(Unit)
    }

    override suspend fun sendPhoneNumber(phoneNumber: String): Result<Unit> {
        val normalized = TelegramAuthMapper.normalizePhoneNumber(phoneNumber)
        if (!TelegramAuthMapper.isValidPhoneNumber(normalized)) {
            return Result.failure(
                IllegalArgumentException("Include your country code and phone number (e.g. +14155552671).")
            )
        }
        return runAuthRequest {
            val settings = TdApi.PhoneNumberAuthenticationSettings()
            sendRequest<TdApi.Ok>(TdApi.SetAuthenticationPhoneNumber(normalized, settings))
        }
    }

    override suspend fun checkAuthenticationCode(code: String): Result<Unit> {
        val cleaned = code.filter(Char::isDigit)
        if (cleaned.length < 4) {
            return Result.failure(IllegalArgumentException("Enter the verification code sent to your Telegram app."))
        }
        return runAuthRequest {
            sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationCode(cleaned))
        }
    }

    override suspend fun checkAuthenticationPassword(password: String): Result<Unit> {
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter your two-step verification password."))
        }
        return runAuthRequest {
            sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
        }
    }

    override suspend fun logout(): Result<Unit> {
        _currentUser.value = null
        recreateClientAfterClose = true
        return runAuthRequest {
            sendRequest<TdApi.Ok>(TdApi.LogOut())
        }
    }

    internal suspend fun <T : TdApi.Object> sendRequest(function: TdApi.Function<*>): T =
        suspendCancellableCoroutine { continuation ->
            val activeClient = client
            if (activeClient == null) {
                continuation.resumeWith(
                    Result.failure(IllegalStateException("Telegram client is not initialized."))
                )
                return@suspendCancellableCoroutine
            }
            activeClient.send(function) { result ->
                if (result is TdApi.Error) {
                    reportError(result.code, result.message)
                    continuation.resumeWith(
                        Result.failure(TdlibRequestException(result.code, result.message))
                    )
                } else {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resumeWith(Result.success(result as T))
                }
            }
        }

    @Synchronized
    private fun ensureClientCreated(): Client? {
        client?.let { return it }
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Exception) {
            Timber.w(e, "Failed to set TDLib log verbosity")
        }
        return Client.create(updateHandler, null, null).also { client = it }
    }

    private fun handleAuthorizationUpdate(tdState: TdApi.AuthorizationState) {
        when (tdState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                waitingForTdlibParams = true
                appScope.launch {
                    val activeClient = client ?: return@launch
                    sendTdlibParameters(activeClient, override = null)
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                waitingForTdlibParams = false
                _authState.value = TelegramAuthState.WaitingForPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                waitingForTdlibParams = false
                _authState.value = TelegramAuthState.WaitingForCode()
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                waitingForTdlibParams = false
                _authState.value = TelegramAuthState.WaitingForPassword()
            }
            is TdApi.AuthorizationStateReady -> {
                waitingForTdlibParams = false
                _authState.value = TelegramAuthState.Ready
                fetchCurrentUser()
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = TelegramAuthState.LoggingOut
            }
            is TdApi.AuthorizationStateClosing -> {
                _authState.value = TelegramAuthState.Initializing
            }
            is TdApi.AuthorizationStateClosed -> {
                waitingForTdlibParams = false
                client = null
                _currentUser.value = null
                _authState.value = TelegramAuthState.Closed
                if (recreateClientAfterClose) {
                    recreateClientAfterClose = false
                    appScope.launch { ensureInitialized() }
                }
            }
            else -> Unit
        }
    }

    private suspend fun sendTdlibParameters(
        activeClient: Client,
        override: TelegramApiCredentials?
    ) {
        val creds = credentialStore.resolveCredentials(override)
        if (creds == null) {
            _authState.value = TelegramAuthState.WaitingForApiCredentials()
            return
        }
        val databaseDirectory = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
        val filesDirectory = File(context.filesDir, "tdlib_files").apply { mkdirs() }.absolutePath
        _authState.value = TelegramAuthState.Initializing

        activeClient.send(
            TdApi.SetTdlibParameters(
                false,
                databaseDirectory,
                filesDirectory,
                null,
                true,
                true,
                true,
                false,
                creds.apiId,
                creds.apiHash,
                "en",
                "Multibox",
                Build.VERSION.RELEASE ?: "Android",
                BuildConfig.APP_VERSION_NAME
            )
        ) { result ->
            if (result is TdApi.Error) {
                val friendly = TelegramAuthMapper.mapTdLibError(result.code, result.message)
                _authErrors.tryEmit(friendly)
                if (result.message.contains("API_ID", ignoreCase = true) ||
                    result.message.contains("API_HASH", ignoreCase = true)
                ) {
                    appScope.launch { credentialStore.clearRuntimeCredentials() }
                    _authState.value = TelegramAuthState.WaitingForApiCredentials(friendly)
                }
            }
        }
    }

    private fun fetchCurrentUser() {
        val activeClient = client ?: return
        activeClient.send(TdApi.GetMe()) { result ->
            if (result is TdApi.User) {
                _currentUser.value = result.toSessionUser()
            }
        }
    }

    private suspend fun runAuthRequest(block: suspend () -> Unit): Result<Unit> {
        return try {
            withTimeout(AUTH_REQUEST_TIMEOUT_MS) {
                block()
            }
            Result.success(Unit)
        } catch (e: TdlibRequestException) {
            val friendly = TelegramAuthMapper.mapTdLibError(e.code, e.message)
            Result.failure(IllegalStateException(friendly, e))
        } catch (e: Exception) {
            val friendly = e.message?.takeIf { it.isNotBlank() }
                ?: "Telegram request failed. Check your connection and retry."
            Result.failure(IllegalStateException(friendly, e))
        }
    }

    private fun reportError(code: Int, rawMessage: String?) {
        val friendly = TelegramAuthMapper.mapTdLibError(code, rawMessage)
        _authErrors.tryEmit(friendly)
        Timber.e("TDLib error %d: %s", code, rawMessage)
    }

    private fun TdApi.User.toSessionUser(): TelegramSessionUser {
        return TelegramSessionUser(
            id = id,
            firstName = firstName.orEmpty(),
            lastName = lastName.orEmpty(),
            username = null,
            phoneNumber = phoneNumber?.takeIf { it.isNotBlank() }
        )
    }
}

internal class TdlibRequestException(
    val code: Int,
    rawMessage: String?
) : Exception(rawMessage ?: "TDLib error ($code)")
