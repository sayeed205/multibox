package com.hitarashi.multibox.core.telegram

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hitarashi.multibox.core.model.telegram.TelegramApiCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.telegramCredentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "multibox_telegram_credentials"
)

@Singleton
internal class TelegramCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val API_ID = intPreferencesKey("tg_api_id_override")
        val API_HASH = stringPreferencesKey("tg_api_hash_override")
    }

    val hasBuildCredentials: Boolean
        get() = BuildConfig.TG_API_ID > 0 && BuildConfig.TG_API_HASH.isNotBlank()

    suspend fun resolveCredentials(
        override: TelegramApiCredentials? = null
    ): TelegramApiCredentials? {
        if (override != null && override.isValid) {
            return override
        }
        if (hasBuildCredentials) {
            return TelegramApiCredentials(
                apiId = BuildConfig.TG_API_ID,
                apiHash = BuildConfig.TG_API_HASH
            )
        }
        return context.telegramCredentialsDataStore.data.map { prefs ->
            val savedId = prefs[Keys.API_ID] ?: 0
            val savedHash = prefs[Keys.API_HASH].orEmpty()
            TelegramApiCredentials(savedId, savedHash).takeIf { it.isValid }
        }.first()
    }

    suspend fun saveCredentials(credentials: TelegramApiCredentials) {
        context.telegramCredentialsDataStore.edit { prefs ->
            prefs[Keys.API_ID] = credentials.apiId
            prefs[Keys.API_HASH] = credentials.apiHash.trim()
        }
    }

    suspend fun clearRuntimeCredentials() {
        context.telegramCredentialsDataStore.edit { prefs ->
            prefs.remove(Keys.API_ID)
            prefs.remove(Keys.API_HASH)
        }
    }
}
