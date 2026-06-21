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
import com.weibox.app.R
import com.weibox.app.data.repository.WeiboRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

const val CAPTCHA_CHANNEL_ID = "weibox_captcha"
private const val CAPTCHA_NOTIF_ID = 1001

@AndroidEntryPoint
class WeiboMessagingService : FirebaseMessagingService() {

    @Inject lateinit var repository: WeiboRepository

    private val scope = CoroutineScope(Dispatchers.IO)

    /** token 首次生成或轮换时上报 server。 */
    override fun onNewToken(token: String) {
        scope.launch { repository.registerDevice(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "captcha" -> showCaptchaNotification(
                data["title"] ?: "微博验证码",
                data["body"] ?: "点击在 app 内完成验证"
            )
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
