package com.weibox.app.data.api

import com.weibox.app.data.model.WeiboComment
import com.weibox.app.data.model.WeiboPost
import com.weibox.app.data.model.WeiboUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 调用 WeiboX Server 的 /api/v1 接口。app 的唯一数据源。 */
class ServerException(message: String) : RuntimeException(message)

class ServerApi(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun urlBuilder(path: String) =
        ("$base/api/v1$path").toHttpUrlOrNull()?.newBuilder()
            ?: throw ServerException("服务器地址无效：$base")

    private fun authed(builder: Request.Builder) =
        builder.header("Authorization", "Bearer $token")

    private fun execute(request: Request): String {
        if (base.isBlank()) throw ServerException("未配置服务器地址")
        if (token.isBlank()) throw ServerException("未配置 API Token")
        val resp = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw ServerException("无法连接服务器：${e.message}")
        }
        resp.use {
            val body = it.body?.string() ?: ""
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw ServerException(
                    when (it.code) {
                        401 -> "鉴权失败，请检查服务器地址和 API Token"
                        else -> detail?.takeIf { d -> d.isNotBlank() } ?: "服务器错误 ${it.code}"
                    }
                )
            }
            return body
        }
    }

    private fun getObject(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val url = urlBuilder(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return JSONObject(execute(authed(Request.Builder().url(url)).get().build()))
    }

    // ── 关注列表 ──────────────────────────────────────────────────
    suspend fun getFollowedUsers(): List<WeiboUser> = withContext(Dispatchers.IO) {
        parseUsers(getObject("/users"))
    }

    suspend fun follow(userId: String): Unit = withContext(Dispatchers.IO) {
        val url = urlBuilder("/users").build()
        val body = JSONObject().put("id", userId).toString().toRequestBody(jsonMedia)
        execute(authed(Request.Builder().url(url)).post(body).build())
    }

    suspend fun unfollow(userId: String): Unit = withContext(Dispatchers.IO) {
        val url = urlBuilder("/users/$userId").build()
        execute(authed(Request.Builder().url(url)).delete().build())
    }

    // ── 时间线（缓存聚合）─────────────────────────────────────────
    suspend fun getTimeline(limit: Int, offset: Int): List<WeiboPost> = withContext(Dispatchers.IO) {
        parsePosts(getObject("/timeline", mapOf("limit" to "$limit", "offset" to "$offset")))
    }

    // ── 热门流（缓存）─────────────────────────────────────────────
    suspend fun getHot(limit: Int, offset: Int): List<WeiboPost> = withContext(Dispatchers.IO) {
        parsePosts(getObject("/hot", mapOf("limit" to "$limit", "offset" to "$offset")))
    }

    // ── 用户主页 / 微博 / 关注列表（实时）─────────────────────────
    suspend fun getUserInfo(userId: String): WeiboUser = withContext(Dispatchers.IO) {
        parseUser(getObject("/users/$userId"))
    }

    suspend fun getUserPosts(userId: String, page: Int, count: Int = 20): List<WeiboPost> =
        withContext(Dispatchers.IO) {
            parsePosts(getObject("/users/$userId/posts", mapOf("page" to "$page", "count" to "$count")))
        }

    suspend fun getFollowingList(userId: String, page: Int): List<WeiboUser> =
        withContext(Dispatchers.IO) {
            parseUsers(getObject("/users/$userId/following", mapOf("page" to "$page")))
        }

    // ── 评论（实时）───────────────────────────────────────────────
    suspend fun getComments(postId: String, maxId: String?): Pair<List<WeiboComment>, String?> =
        withContext(Dispatchers.IO) {
            val q = if (maxId.isNullOrBlank()) emptyMap() else mapOf("max_id" to maxId)
            val root = getObject("/posts/$postId/comments", q)
            val arr = root.optJSONArray("comments") ?: JSONArray()
            val comments = (0 until arr.length()).map { parseComment(arr.getJSONObject(it)) }
            val next = root.optString("next_max_id").takeIf { it.isNotBlank() && it != "null" }
            comments to next
        }

    // ── 搜索 ──────────────────────────────────────────────────────
    suspend fun searchUsers(query: String, page: Int): List<WeiboUser> = withContext(Dispatchers.IO) {
        parseUsers(getObject("/search", mapOf("q" to query, "page" to "$page")))
    }

    // ── FCM 设备注册 ──────────────────────────────────────────────
    suspend fun registerDevice(deviceToken: String, label: String = ""): Unit = withContext(Dispatchers.IO) {
        val url = urlBuilder("/devices").build()
        val body = JSONObject().put("token", deviceToken).put("label", label)
            .toString().toRequestBody(jsonMedia)
        execute(authed(Request.Builder().url(url)).post(body).build())
    }

    suspend fun unregisterDevice(deviceToken: String): Unit = withContext(Dispatchers.IO) {
        val url = urlBuilder("/devices/$deviceToken").build()
        execute(authed(Request.Builder().url(url)).delete().build())
    }

    // ── 解析（server 的 snake_case JSON → app 模型）───────────────
    private fun parseUsers(root: JSONObject): List<WeiboUser> {
        val arr = root.optJSONArray("users") ?: return emptyList()
        return (0 until arr.length()).map { parseUser(arr.getJSONObject(it)) }
    }

    private fun parseUser(o: JSONObject) = WeiboUser(
        id = o.optString("id"),
        screenName = o.optString("screen_name"),
        description = o.optString("description"),
        avatarUrl = o.optString("avatar_url"),
        coverUrl = o.optString("cover_url"),
        followersCount = o.optString("followers_count"),
        followCount = o.optInt("follow_count"),
        statusesCount = o.optInt("statuses_count"),
        verified = o.optBoolean("verified"),
        verifiedReason = o.optString("verified_reason")
    )

    private fun parsePosts(root: JSONObject): List<WeiboPost> {
        val arr = root.optJSONArray("posts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            runCatching { parsePost(arr.getJSONObject(it)) }.getOrNull()
        }
    }

    private fun parsePost(o: JSONObject): WeiboPost = WeiboPost(
        id = o.optString("id"),
        userId = o.optString("user_id"),
        userName = o.optString("user_name"),
        userAvatar = o.optString("user_avatar"),
        text = o.optString("text"),
        pics = jsonStrings(o.optJSONArray("pics")),
        createdAt = o.optString("created_at"),
        createdAtTimestamp = o.optLong("created_at_ts"),
        likesCount = o.optInt("likes_count"),
        commentsCount = o.optInt("comments_count"),
        repostsCount = o.optInt("reposts_count"),
        source = o.optString("source"),
        isRetweet = o.optBoolean("is_retweet"),
        retweetPost = o.optJSONObject("retweet")?.let { runCatching { parsePost(it) }.getOrNull() }
    )

    private fun parseComment(o: JSONObject) = WeiboComment(
        id = o.optString("id"),
        text = o.optString("text"),
        createdAt = o.optString("created_at"),
        createdAtTimestamp = o.optLong("created_at_ts"),
        userName = o.optString("user_name"),
        userAvatar = o.optString("user_avatar"),
        likeCount = o.optInt("like_count")
    )

    private fun jsonStrings(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotEmpty() }
    }
}
