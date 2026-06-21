package com.weibox.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weibox.app.data.model.WeiboPost
import com.weibox.app.data.prefs.AppPreferences
import com.weibox.app.data.repository.WeiboRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FeedMode { TIMELINE, SPECIAL, RANDOM }

data class HomeUiState(
    val posts: List<WeiboPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val lastRefreshTime: Long = 0L,
    val feedMode: FeedMode = FeedMode.TIMELINE
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    val repo: WeiboRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(isLoading = true))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var allPosts: List<WeiboPost> = emptyList()
    private var specialPosts: List<WeiboPost> = emptyList()
    private var randomPosts: List<WeiboPost> = emptyList()

    init {
        repo.getCachedTimeline()
            .onEach { posts ->
                allPosts = posts
                if (_state.value.feedMode == FeedMode.RANDOM) randomPosts = posts.shuffled()
                recompute()
            }
            .launchIn(viewModelScope)

        repo.getCachedSpecialTimeline()
            .onEach { posts ->
                specialPosts = posts
                recompute()
            }
            .launchIn(viewModelScope)

        prefs.lastRefreshTime
            .onEach { t -> _state.update { it.copy(lastRefreshTime = t) } }
            .launchIn(viewModelScope)

        // 数据都在 server 上（server 扛防爬），app 拉的是已缓存数据，无防爬顾虑。
        // 因此每次冷启动直接从 server 同步最新（顺带对齐关注列表），不再做 15 分钟节流。
        refresh()
    }

    /** 按当前模式重算展示列表。 */
    private fun recompute() {
        _state.update { s ->
            val list = when (s.feedMode) {
                FeedMode.TIMELINE -> allPosts
                FeedMode.SPECIAL  -> specialPosts
                FeedMode.RANDOM   -> randomPosts
            }
            s.copy(posts = list, isLoading = false, isEmpty = list.isEmpty())
        }
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    /** 挂起版刷新，下拉手势用它等本次刷新真正结束再收起指示器。 */
    suspend fun doRefresh() {
        if (_state.value.isRefreshing) return  // 防止并发重复刷新
        _state.update { it.copy(isRefreshing = true, error = null) }
        val special = _state.value.feedMode == FeedMode.SPECIAL
        runCatching { if (special) repo.refreshSpecialTimeline() else repo.refreshTimeline() }
            .onSuccess { posts ->
                prefs.saveLastRefreshTime(System.currentTimeMillis())
                _state.update { it.copy(currentPage = 1, hasMore = posts.isNotEmpty()) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(isRefreshing = false) }
    }

    /** 点标题循环：时间线 → 特别关注 → 随机浏览 → 时间线。 */
    fun toggleMode() {
        val next = when (_state.value.feedMode) {
            FeedMode.TIMELINE -> FeedMode.SPECIAL
            FeedMode.SPECIAL  -> FeedMode.RANDOM
            FeedMode.RANDOM   -> FeedMode.TIMELINE
        }
        if (next == FeedMode.RANDOM) randomPosts = allPosts.shuffled()
        _state.update { it.copy(feedMode = next) }
        recompute()
        if (next == FeedMode.SPECIAL) refresh()   // 切到特关顺带拉一次最新
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || s.isRefreshing || !s.hasMore) return
        viewModelScope.launch {
            val nextPage = s.currentPage + 1
            val special = s.feedMode == FeedMode.SPECIAL
            _state.update { it.copy(isLoadingMore = true) }
            runCatching { if (special) repo.loadMoreSpecialTimeline(nextPage) else repo.loadMoreTimeline(nextPage) }
                .onSuccess { newPosts ->
                    _state.update {
                        it.copy(
                            currentPage = nextPage,
                            hasMore = newPosts.isNotEmpty(),
                            isLoadingMore = false
                        )
                    }
                }
                .onFailure { _state.update { it.copy(isLoadingMore = false) } }
        }
    }
}
