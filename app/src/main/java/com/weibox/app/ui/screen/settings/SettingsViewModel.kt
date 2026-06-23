package com.weibox.app.ui.screen.settings

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weibox.app.R
import com.weibox.app.data.api.ServerApi
import com.weibox.app.data.prefs.AppPreferences
import com.weibox.app.data.prefs.ThemeMode
import com.weibox.app.data.repository.WeiboRepository
import com.weibox.app.fcm.FcmRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val apiToken: String = "",
    val serverUrlInput: String = "",
    val apiTokenInput: String = "",
    val saved: Boolean = false,
    val testing: Boolean = false,
    val connectionMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val captchaNotifEnabled: Boolean = true,
    val specialNotifEnabled: Boolean = true,
    val donateMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val repo: WeiboRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        prefs.serverUrl.onEach { v -> _state.update { it.copy(serverUrl = v, serverUrlInput = v) } }.launchIn(viewModelScope)
        prefs.apiToken.onEach { v -> _state.update { it.copy(apiToken = v, apiTokenInput = v) } }.launchIn(viewModelScope)
        prefs.themeMode.onEach { m -> _state.update { it.copy(themeMode = m) } }.launchIn(viewModelScope)
        prefs.captchaNotifEnabled.onEach { v -> _state.update { it.copy(captchaNotifEnabled = v) } }.launchIn(viewModelScope)
        prefs.specialNotifEnabled.onEach { v -> _state.update { it.copy(specialNotifEnabled = v) } }.launchIn(viewModelScope)
    }

    // ── 服务器配置 ───────────────────────────────────────────────
    fun onServerUrlChange(v: String) = _state.update { it.copy(serverUrlInput = v, saved = false, connectionMessage = null) }
    fun onApiTokenChange(v: String) = _state.update { it.copy(apiTokenInput = v, saved = false, connectionMessage = null) }

    fun saveServer() = viewModelScope.launch {
        prefs.saveServer(_state.value.serverUrlInput.trim(), _state.value.apiTokenInput.trim())
        _state.update { it.copy(saved = true) }
        // 配置好后立即上报 FCM 设备 token，启用验证码推送
        FcmRegistrar.registerCurrentToken(repo, viewModelScope)
    }

    /** 用当前输入测试连接（调 /api/v1/status）。 */
    fun testConnection() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(testing = true, connectionMessage = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ServerApi(s.serverUrlInput.trim(), s.apiTokenInput.trim()).getFollowedUsers()
            }
        }
        _state.update {
            it.copy(
                testing = false,
                connectionMessage = result.fold(
                    onSuccess = { users -> "连接成功：已关注 ${users.size} 位用户" },
                    onFailure = { e -> "连接失败：${e.message}" }
                )
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }

    // ── 通知开关 ─────────────────────────────────────────────────
    // 写入本地后再把最新开关上报 server，让 server 据此决定是否对本设备发送
    fun setCaptchaNotifEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setCaptchaNotifEnabled(enabled)
        FcmRegistrar.registerCurrentToken(repo, viewModelScope)
    }
    fun setSpecialNotifEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setSpecialNotifEnabled(enabled)
        FcmRegistrar.registerCurrentToken(repo, viewModelScope)
    }

    // ── 支持开发者 ────────────────────────────────────────────────
    fun savePayQrCode() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.payme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "payme_qr.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: error("无法创建图片")
                context.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it)
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                dir.mkdirs()
                val file = File(dir, "payme_qr.jpg")
                FileOutputStream(file).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it)
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        }.fold(
            onSuccess = { _state.update { it.copy(donateMessage = "二维码已保存到相册") } },
            onFailure = { e -> _state.update { it.copy(donateMessage = "保存失败：${e.message}") } }
        )
    }
}
