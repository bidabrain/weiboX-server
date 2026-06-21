package com.weibox.app.data.repository

import com.weibox.app.data.api.ServerApi
import com.weibox.app.data.db.AppDatabase
import com.weibox.app.data.db.entity.toEntity
import com.weibox.app.data.model.WeiboPost
import com.weibox.app.data.model.WeiboUser
import com.weibox.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据层统一入口。所有数据均来自 WeiboX Server 的 /api/v1，
 * 本地 Room 仅作时间线 + 关注列表的镜像缓存（响应式 UI）。
 */
@Singleton
class WeiboRepository @Inject constructor(
    private val db: AppDatabase,
    private val prefs: AppPreferences
) {
    private suspend fun api(): ServerApi =
        ServerApi(prefs.serverUrl.first(), prefs.apiToken.first())

    // ── 关注列表 ──────────────────────────────────────────────────
    fun getFollowedUsers(): Flow<List<WeiboUser>> =
        db.userDao().getAll().map { list -> list.map { it.toModel() } }

    fun isFollowed(userId: String): Flow<Boolean> = db.userDao().isFollowed(userId)

    // ── 特别关注 ──────────────────────────────────────────────────
    fun getSpecialUsers(): Flow<List<WeiboUser>> =
        db.userDao().getSpecialUsers().map { list -> list.map { it.toModel() } }

    fun isSpecial(userId: String): Flow<Boolean> = db.userDao().isSpecial(userId)

    /** 设置/取消特别关注。特关隐含关注，server 会自动加关注，这里同步回本地。 */
    suspend fun setSpecial(userId: String, special: Boolean) {
        api().setSpecial(userId, special)
        runCatching { syncFollowing() }     // 拉回最新关注列表（含自动新增的关注 + special 状态）
        db.userDao().setSpecial(userId, special)   // 立即本地反映
    }

    suspend fun followUser(user: WeiboUser) {
        api().follow(user.id)
        db.userDao().insert(user.toEntity())
    }

    suspend fun unfollowUser(userId: String) {
        runCatching { api().unfollow(userId) }
        db.userDao().delete(userId)
        db.postDao().deleteByUser(userId)
    }

    /** 用 server 的关注列表覆盖本地镜像（保留已有用户的本地状态，只增删差异）。 */
    suspend fun syncFollowing() {
        val remote = api().getFollowedUsers()
        val localIds = db.userDao().getAllIds().toSet()
        val remoteIds = remote.map { it.id }.toSet()
        localIds.filter { it !in remoteIds }.forEach {
            db.userDao().delete(it)
            db.postDao().deleteByUser(it)
        }
        remote.filter { it.id !in localIds }.forEach { db.userDao().insert(it.toEntity()) }
        // 同步特别关注状态（server 为准），含已有用户
        remote.forEach { db.userDao().setSpecial(it.id, it.special) }
    }

    // ── 搜索 / 用户信息（实时）────────────────────────────────────
    suspend fun fetchUser(userId: String): WeiboUser = api().getUserInfo(userId)

    suspend fun fetchFollowingList(userId: String, page: Int): List<WeiboUser> =
        api().getFollowingList(userId, page)

    suspend fun fetchComments(postId: String, maxId: String? = null, page: Int = 1) =
        api().getComments(postId, maxId)

    suspend fun searchUsers(query: String, page: Int = 1): List<WeiboUser> =
        api().searchUsers(query, page)

    /** 上报 FCM 设备 token（server 未配置时静默跳过）。 */
    suspend fun registerDevice(fcmToken: String) {
        if (prefs.serverUrl.first().isBlank() || prefs.apiToken.first().isBlank()) return
        runCatching { api().registerDevice(fcmToken, android.os.Build.MODEL ?: "") }
    }

    // ── 时间线（缓存）─────────────────────────────────────────────
    fun getCachedTimeline(): Flow<List<WeiboPost>> =
        db.postDao().getTimeline().map { list -> list.map { it.toModel() } }

    suspend fun refreshTimeline(): List<WeiboPost> {
        runCatching { syncFollowing() }
        val posts = api().getTimeline(limit = PAGE_SIZE, offset = 0)
        db.postDao().insertAll(posts.map { it.toEntity() })
        trimCache()
        return posts
    }

    suspend fun loadMoreTimeline(page: Int): List<WeiboPost> {
        val posts = api().getTimeline(limit = PAGE_SIZE, offset = (page - 1) * PAGE_SIZE)
        db.postDao().insertAll(posts.map { it.toEntity() })
        return posts
    }

    /** 用户主页的微博，实时拉取，不写时间线缓存（避免污染聚合时间线）。 */
    suspend fun refreshUserPosts(userId: String, page: Int = 1): List<WeiboPost> =
        api().getUserPosts(userId, page)

    // ── 特别关注时间线（复用 cached_posts，按特关用户过滤；离线可看）────
    fun getCachedSpecialTimeline(): Flow<List<WeiboPost>> =
        db.postDao().getSpecialTimeline().map { list -> list.map { it.toModel() } }

    suspend fun refreshSpecialTimeline(): List<WeiboPost> {
        runCatching { syncFollowing() }
        val posts = api().getSpecialTimeline(limit = PAGE_SIZE, offset = 0)
        db.postDao().insertAll(posts.map { it.toEntity() })
        trimCache()
        return posts
    }

    suspend fun loadMoreSpecialTimeline(page: Int): List<WeiboPost> {
        val posts = api().getSpecialTimeline(limit = PAGE_SIZE, offset = (page - 1) * PAGE_SIZE)
        db.postDao().insertAll(posts.map { it.toEntity() })
        return posts
    }

    // ── 热门流（直接读 server 缓存，不落 Room）────────────────────
    /** 首屏：拉最新一页热门。 */
    suspend fun refreshHot(): List<WeiboPost> =
        api().getHot(limit = PAGE_SIZE, offset = 0)

    /** 下拉加载：继续从 server 缓存翻页。 */
    suspend fun loadMoreHot(page: Int): List<WeiboPost> =
        api().getHot(limit = PAGE_SIZE, offset = (page - 1) * PAGE_SIZE)

    private suspend fun trimCache() {
        db.postDao().deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)
        val count = db.postDao().count()
        if (count > MAX_CACHED_POSTS) {
            db.postDao().deleteOldest(count - MAX_CACHED_POSTS)
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_CACHED_POSTS = 500
    }
}
