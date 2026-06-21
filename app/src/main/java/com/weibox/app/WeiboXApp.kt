package com.weibox.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient

@HiltAndroidApp
class WeiboXApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 图片直连微博加载（方案 A）：带 Referer / UA 绕过 sinaimg 防盗链
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("Referer", "https://m.weibo.cn/")
                                    .header(
                                        "User-Agent",
                                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15"
                                    )
                                    .build()
                            )
                        }
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(100L * 1024 * 1024)
                        .build()
                }
                .memoryCache {
                    coil.memory.MemoryCache.Builder(this)
                        .maxSizePercent(0.20)
                        .build()
                }
                .crossfade(true)
                .build()
        )
    }
}
