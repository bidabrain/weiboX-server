package com.weibox.app.data.model

import org.json.JSONObject

/**
 * 帖子里的视频。url 是微博带签名的直链（会过期），
 * 播放一律走服务端 /api/v1/video?mid=…，过期由服务端按 mid 换新地址。
 */
data class WeiboVideo(
    val mid: String,
    val url: String,
    val cover: String,
    val duration: Int
) {
    fun toJson(): String = JSONObject()
        .put("mid", mid).put("url", url).put("cover", cover).put("duration", duration)
        .toString()

    companion object {
        fun fromJson(o: JSONObject?): WeiboVideo? {
            o ?: return null
            val mid = o.optString("mid")
            if (mid.isBlank()) return null
            return WeiboVideo(mid, o.optString("url"), o.optString("cover"), o.optInt("duration"))
        }

        fun fromJsonString(s: String): WeiboVideo? =
            if (s.isBlank()) null else runCatching { fromJson(JSONObject(s)) }.getOrNull()
    }
}
