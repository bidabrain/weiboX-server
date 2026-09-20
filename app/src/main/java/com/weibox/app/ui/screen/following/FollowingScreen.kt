package com.weibox.app.ui.screen.following

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import com.weibox.app.ui.components.SearchEntryBar
import com.weibox.app.ui.components.UserCard
import com.weibox.app.ui.components.WeiboTopBar

@Composable
fun FollowingScreen(
    onNavigateToProfile: (String) -> Unit,
    onNavigateToSearch: () -> Unit = {},
    hazeState: HazeState,
    bottomInset: Dp = 0.dp,
    vm: FollowingViewModel = hiltViewModel()
) {
    val users by vm.users.collectAsState()

    Scaffold(
        topBar = {
            WeiboTopBar(
                title = "关注",
                below = {
                    SearchEntryBar(onClick = onNavigateToSearch, hint = "搜索并关注新用户")
                },
                modifier = Modifier.hazeChild(hazeState),
                containerColor = Color.Transparent
            )
        }
    ) { padding ->
        if (users.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有关注任何用户", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .haze(hazeState, HazeDefaults.style(backgroundColor = MaterialTheme.colorScheme.surface)),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = bottomInset
                )
            ) {
                items(users, key = { it.id }) { user ->
                    UserCard(
                        user = user,
                        isFollowed = true,
                        onClick = { onNavigateToProfile(user.id) },
                        onFollowToggle = { vm.unfollow(user.id) },
                        isSpecial = user.special,
                        onSpecialToggle = { vm.toggleSpecial(user) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
