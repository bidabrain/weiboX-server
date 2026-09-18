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
private val KEY_DARK_MODE     = booleanPreferencesKey("dark_mode")   // 旧版开关，仅用于迁移
private val KEY_THEME_MODE    = stringPreferencesKey("theme_mode")
private val KEY_LAST_REFRESH  = longPreferencesKey("last_refresh")
private val KEY_NOTIF_CAPTCHA = booleanPreferencesKey("notif_captcha")  // 验证码通知开关
private val KEY_NOTIF_SPECIAL = booleanPreferencesKey("notif_special")  // 特别关注通知开关
private val KEY_FONT_SCALE    = stringPreferencesKey("font_scale")

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 字体大小档位，倍率作用在 [com.weibox.app.ui.theme.weiboXTypography] 上。
 * 这是叠加在系统字体大小之上的倍率，不是绝对字号。
 */
enum class FontScale(val scale: Float) {
    SMALL(0.875f), NORMAL(1f), LARGE(1.15f), XLARGE(1.3f)
}

class AppPreferences(private val context: Context) {

    val serverUrl: Flow<String>     = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val apiToken: Flow<String>      = context.dataStore.data.map { it[KEY_API_TOKEN] ?: "" }
    val lastRefreshTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_REFRESH] ?: 0L }

    /** 验证码通知开关，默认开启。 */
    val captchaNotifEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_CAPTCHA] ?: true }
    /** 特别关注通知开关，默认开启。 */
    val specialNotifEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_SPECIAL] ?: true }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        when (p[KEY_THEME_MODE]) {
            "light"  -> ThemeMode.LIGHT
            "dark"   -> ThemeMode.DARK
            "system" -> ThemeMode.SYSTEM
            // 旧版只有 dark_mode 布尔值：true→深色，其余→跟随系统
            else     -> if (p[KEY_DARK_MODE] == true) ThemeMode.DARK else ThemeMode.SYSTEM
        }
    }

    /** 字体大小档位，旧版本没有此项时落到 NORMAL（与改动前完全一致）。 */
    val fontScale: Flow<FontScale> = context.dataStore.data.map { p ->
        when (p[KEY_FONT_SCALE]) {
            "small"  -> FontScale.SMALL
            "large"  -> FontScale.LARGE
            "xlarge" -> FontScale.XLARGE
            else     -> FontScale.NORMAL
        }
    }

    suspend fun saveServer(url: String, token: String) =
        context.dataStore.edit {
            it[KEY_SERVER_URL] = url.trim().trimEnd('/')
            it[KEY_API_TOKEN]  = token.trim()
        }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.dataStore.edit {
            it[KEY_THEME_MODE] = when (mode) {
                ThemeMode.LIGHT  -> "light"
                ThemeMode.DARK   -> "dark"
                ThemeMode.SYSTEM -> "system"
            }
        }

    suspend fun setFontScale(value: FontScale) =
        context.dataStore.edit {
            it[KEY_FONT_SCALE] = when (value) {
                FontScale.SMALL  -> "small"
                FontScale.NORMAL -> "normal"
                FontScale.LARGE  -> "large"
                FontScale.XLARGE -> "xlarge"
            }
        }

    suspend fun saveLastRefreshTime(time: Long) =
        context.dataStore.edit { it[KEY_LAST_REFRESH] = time }

    suspend fun setCaptchaNotifEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_NOTIF_CAPTCHA] = enabled }

    suspend fun setSpecialNotifEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_NOTIF_SPECIAL] = enabled }
}
