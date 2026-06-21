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
    val isRandomMode: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    val repo: WeiboRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(isLoading = true))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var allPosts: List<WeiboPost> = emptyList()

    init {
        repo.getCachedTimeline()
            .onEach { posts ->
                allPosts = posts
                _state.update { s ->
                    s.copy(
                        posts = if (s.isRandomMode) posts.shuffled() else posts,
                        isLoading = false,
                        isEmpty = posts.isEmpty()
                    )
                }
            }
            .launchIn(viewModelScope)

        prefs.lastRefreshTime
            .onEach { t -> _state.update { it.copy(lastRefreshTime = t) } }
            .launchIn(viewModelScope)

        // 数据都在 server 上（server 扛防爬），app 拉的是已缓存数据，无防爬顾虑。
        // 因此每次冷启动直接从 server 同步最新（顺带对齐关注列表），不再做 15 分钟节流。
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    /** 挂起版刷新，下拉手势用它等本次刷新真正结束再收起指示器。 */
    suspend fun doRefresh() {
        if (_state.value.isRefreshing) return  // 防止并发重复刷新
        _state.update { it.copy(isRefreshing = true, error = null) }
        runCatching { repo.refreshTimeline() }
            .onSuccess { posts ->
                prefs.saveLastRefreshTime(System.currentTimeMillis())
                _state.update { it.copy(currentPage = 1, hasMore = posts.isNotEmpty()) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(isRefreshing = false) }
    }

    fun toggleMode() {
        val newRandom = !_state.value.isRandomMode
        _state.update { s ->
            s.copy(
                isRandomMode = newRandom,
                posts = if (newRandom) allPosts.shuffled() else allPosts
            )
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || s.isRefreshing || !s.hasMore) return
        viewModelScope.launch {
            val nextPage = s.currentPage + 1
            _state.update { it.copy(isLoadingMore = true) }
            runCatching { repo.loadMoreTimeline(nextPage) }
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
