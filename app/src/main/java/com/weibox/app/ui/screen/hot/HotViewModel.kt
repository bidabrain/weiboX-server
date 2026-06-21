package com.weibox.app.ui.screen.hot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weibox.app.data.model.WeiboPost
import com.weibox.app.data.repository.WeiboRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HotUiState(
    val posts: List<WeiboPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = true
)

/**
 * 热门流：直接读 server 缓存（server 端和关注列表同轮定时抓取）。
 * 与时间线不同，热门流是临时内容，不落 Room，纯内存 + API 分页。
 */
@HiltViewModel
class HotViewModel @Inject constructor(
    val repo: WeiboRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HotUiState(isLoading = true))
    val state: StateFlow<HotUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    /** 挂起版刷新，下拉手势用它等本次刷新真正结束再收起指示器。 */
    suspend fun doRefresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, error = null) }
        runCatching { repo.refreshHot() }
            .onSuccess { posts ->
                _state.update {
                    it.copy(
                        posts = posts,
                        isLoading = false,
                        isEmpty = posts.isEmpty(),
                        currentPage = 1,
                        hasMore = posts.isNotEmpty()
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message, isLoading = false) } }
        _state.update { it.copy(isRefreshing = false) }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || s.isRefreshing || !s.hasMore) return
        viewModelScope.launch {
            val nextPage = s.currentPage + 1
            _state.update { it.copy(isLoadingMore = true) }
            runCatching { repo.loadMoreHot(nextPage) }
                .onSuccess { newPosts ->
                    val existing = _state.value.posts.map { it.id }.toSet()
                    val merged = _state.value.posts + newPosts.filter { it.id !in existing }
                    _state.update {
                        it.copy(
                            posts = merged,
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
