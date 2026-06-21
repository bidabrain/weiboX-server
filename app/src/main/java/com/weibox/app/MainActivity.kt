package com.weibox.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weibox.app.data.prefs.AppPreferences
import com.weibox.app.data.prefs.ThemeMode
import com.weibox.app.data.repository.WeiboRepository
import com.weibox.app.fcm.FcmRegistrar
import com.weibox.app.navigation.AppNavGraph
import com.weibox.app.ui.theme.WeiboXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var repository: WeiboRepository

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var lastBackPressedTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ 通知权限（用于验证码提醒）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 上报 FCM 设备 token（server 已配置时生效）
        FcmRegistrar.registerCurrentToken(repository, lifecycleScope)

        // 注册在 setContent 之前，使 NavHost 的回调优先级更高（后注册先执行）
        // 只有在根页面回退栈为空时才触发此回调
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime < 2000L) {
                    // 彻底关闭：结束并从最近任务移除（app 无任何后台服务，退出即停）
                    finishAndRemoveTask()
                } else {
                    lastBackPressedTime = now
                    Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setContent {
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT  -> false
                ThemeMode.DARK   -> true
            }
            WeiboXTheme(darkTheme = darkTheme) {
                AppNavGraph()
            }
        }
    }
}
