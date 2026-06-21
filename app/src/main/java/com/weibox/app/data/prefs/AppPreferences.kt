package com.weibox.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("weibox_prefs")

private val KEY_SERVER_URL    = stringPreferencesKey("server_url")
private val KEY_API_TOKEN     = stringPreferencesKey("api_token")
private val KEY_DARK_MODE     = booleanPreferencesKey("dark_mode")
private val KEY_LAST_REFRESH  = longPreferencesKey("last_refresh")

class AppPreferences(private val context: Context) {

    val serverUrl: Flow<String>     = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val apiToken: Flow<String>      = context.dataStore.data.map { it[KEY_API_TOKEN] ?: "" }
    val darkMode: Flow<Boolean>     = context.dataStore.data.map { it[KEY_DARK_MODE] ?: false }
    val lastRefreshTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_REFRESH] ?: 0L }

    suspend fun saveServer(url: String, token: String) =
        context.dataStore.edit {
            it[KEY_SERVER_URL] = url.trim().trimEnd('/')
            it[KEY_API_TOKEN]  = token.trim()
        }

    suspend fun setDarkMode(enabled: Boolean) =
        context.dataStore.edit { it[KEY_DARK_MODE] = enabled }

    suspend fun saveLastRefreshTime(time: Long) =
        context.dataStore.edit { it[KEY_LAST_REFRESH] = time }
}
