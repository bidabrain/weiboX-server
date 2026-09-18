package com.weibox.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** GitHub 上查到的最新发布。 */
data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val pageUrl: String
)

/**
 * 查询 GitHub Releases 里的最新 APK。
 *
 * 注意：CI 用的是固定 tag `latest`（每次覆盖），所以 `tag_name` 永远是 "latest"、
 * 拿不到版本号。版本号从 APK 资源名 `weibox-<version>.apk` 解析——该命名由
 * .github/workflows/android-release.yml 生成，改那边的命名要同步改这里的正则。
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/bidabrain/weiboX-server/releases/latest"

    private val APK_NAME = Regex("""weibox-(.+)\.apk""", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            // GitHub API 不带 User-Agent 会直接 403
            .header("User-Agent", "WeiboX-App")
            .get()
            .build()

        val body = try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> text
                    resp.code == 403 || resp.code == 429 ->
                        throw ServerException("GitHub 接口访问过于频繁，请稍后再试")
                    resp.code == 404 ->
                        throw ServerException("尚未发布任何版本")
                    else -> throw ServerException("GitHub 返回错误 ${resp.code}")
                }
            }
        } catch (e: ServerException) {
            throw e
        } catch (e: Exception) {
            throw ServerException("无法连接 GitHub：${e.message}")
        }

        val json = JSONObject(body)
        val assets = json.optJSONArray("assets")
        var version: String? = null
        var downloadUrl: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.getJSONObject(i)
            val match = APK_NAME.matchEntire(asset.optString("name"))
            if (match != null) {
                version = match.groupValues[1]
                downloadUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (version == null || downloadUrl.isNullOrBlank()) {
            throw ServerException("最新发布里没有找到 APK 文件")
        }
        ReleaseInfo(
            version = version,
            downloadUrl = downloadUrl,
            pageUrl = json.optString("html_url")
        )
    }

    /**
     * 逐段比较版本号，缺失的段按 0 处理（"1.1" 等价于 "1.1.0"）。
     * 返回负数表示 [a] 更旧，0 表示相同，正数表示 [a] 更新。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
