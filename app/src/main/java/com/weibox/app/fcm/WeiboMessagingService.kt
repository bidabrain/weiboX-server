package com.weibox.app.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.weibox.app.CaptchaActivity
import com.weibox.app.MainActivity
import com.weibox.app.R
import com.weibox.app.data.prefs.AppPreferences
import com.weibox.app.data.repository.WeiboRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

const val CAPTCHA_CHANNEL_ID = "weibox_captcha"
const val SPECIAL_CHANNEL_ID = "weibox_special"
private const val CAPTCHA_NOTIF_ID = 1001

@AndroidEntryPoint
class WeiboMessagingService : FirebaseMessagingService() {

    @Inject lateinit var repository: WeiboRepository
    @Inject lateinit var prefs: AppPreferences

    private val scope = CoroutineScope(Dispatchers.IO)

    /** token 首次生成或轮换时上报 server。 */
    override fun onNewToken(token: String) {
        scope.launch { repository.registerDevice(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "captcha" -> scope.launch {
                // 用户关闭了验证码通知则不弹
                if (!prefs.captchaNotifEnabled.first()) return@launch
                showCaptchaNotification(
                    data["title"] ?: "微博验证码",
                    data["body"] ?: "点击在 app 内完成验证"
                )
            }
            "new_posts" -> scope.launch {
                // 用户关闭了特别关注通知则不弹
                if (!prefs.specialNotifEnabled.first()) return@launch
                showNewPostsNotification(
                    data["title"] ?: "特别关注更新",
                    data["body"] ?: "特别关注的用户发布了新微博",
                    data["user_id"] ?: ""
                )
            }
        }
    }

    private fun showNewPostsNotification(title: String, body: String, userId: String) {
        ensureSpecialChannel()
        // 点击打开 app 并跳转到该特别关注用户的详情页。同一用户多条更新合并到同一通知。
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_PROFILE_USER_ID, userId)
        val pending = PendingIntent.getActivity(
            this, userId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, SPECIAL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            // 通知 id 按用户区分，不同用户的更新各自成条
            val notifId = if (userId.isNotEmpty()) 2000 + (userId.hashCode() and 0xFFFF) else 2000
            NotificationManagerCompat.from(this).notify(notifId, notif)
        }
    }

    private fun ensureSpecialChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SPECIAL_CHANNEL_ID, "特别关注更新", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "特别关注的用户发布新微博时提醒" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showCaptchaNotification(title: String, body: String) {
        ensureChannel()
        val intent = Intent(this, CaptchaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CAPTCHA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(CAPTCHA_NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTCHA_CHANNEL_ID, "验证码提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "微博抓取触发验证码时提醒你手动处理" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
