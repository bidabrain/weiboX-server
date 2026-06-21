package com.weibox.app.data.db.dao

import androidx.room.*
import com.weibox.app.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

data class UserFetchInfo(val id: String, val lastFetchedAt: Long)

@Dao
interface UserDao {
    @Query("SELECT * FROM followed_users ORDER BY followedAt DESC")
    fun getAll(): Flow<List<UserEntity>>

    @Query("SELECT id FROM followed_users")
    suspend fun getAllIds(): List<String>

    @Query("SELECT id, lastFetchedAt FROM followed_users")
    suspend fun getAllWithFetchInfo(): List<UserFetchInfo>

    @Query("SELECT * FROM followed_users WHERE id = :userId")
    suspend fun getById(userId: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Query("DELETE FROM followed_users WHERE id = :userId")
    suspend fun delete(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_users WHERE id = :userId)")
    fun isFollowed(userId: String): Flow<Boolean>

    @Query("UPDATE followed_users SET lastFetchedAt = :time WHERE id = :userId")
    suspend fun updateLastFetchedAt(userId: String, time: Long)

    // ── 特别关注 ──────────────────────────────────────────────────
    @Query("SELECT * FROM followed_users WHERE special = 1 ORDER BY followedAt DESC")
    fun getSpecialUsers(): Flow<List<UserEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_users WHERE id = :userId AND special = 1)")
    fun isSpecial(userId: String): Flow<Boolean>

    @Query("UPDATE followed_users SET special = :special WHERE id = :userId")
    suspend fun setSpecial(userId: String, special: Boolean)
}
