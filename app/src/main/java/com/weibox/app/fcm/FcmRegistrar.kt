package com.weibox.app.fcm

import com.google.firebase.messaging.FirebaseMessaging
import com.weibox.app.data.repository.WeiboRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 取当前 FCM 设备 token 并上报 server（server 未配置时 repo 内部静默跳过）。 */
object FcmRegistrar {
    fun registerCurrentToken(repo: WeiboRepository, scope: CoroutineScope) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) scope.launch { repo.registerDevice(token) }
        }
    }
}
