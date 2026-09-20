package com.weibox.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weibox.app.ui.screen.following.FollowingScreen
import com.weibox.app.ui.screen.followinglist.FollowingListScreen
import com.weibox.app.ui.screen.home.HomeScreen
import com.weibox.app.ui.screen.hot.HotScreen
import com.weibox.app.ui.screen.profile.ProfileScreen
import com.weibox.app.ui.screen.search.SearchScreen
import com.weibox.app.ui.screen.settings.SettingsScreen

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    object Home      : Tab("home",      "时间线",   Icons.Filled.Home)
    object Hot       : Tab("hot",       "热门",     Icons.Filled.Whatshot)
    object Following : Tab("following", "关注",     Icons.Filled.People)
    object Settings  : Tab("settings",  "设置",     Icons.Filled.Settings)
}

private val tabs = listOf(Tab.Home, Tab.Hot, Tab.Following, Tab.Settings)

// 搜索不再占底栏位置，改由各页顶部的 SearchEntryBar 压栈进入（自带返回）
private const val ROUTE_SEARCH = "search"

@Composable
fun AppNavGraph(
    pendingProfileUserId: String? = null,
    onPendingProfileConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val showBottomBar = tabs.any { it.route == currentDest?.route }

    // 毛玻璃：内容整体注册为 haze 源，顶栏/底栏作为 hazeChild 取其模糊结果。
    // Android 12L(API 32) 以下 haze 自动退回半透明遮罩，不会崩。
    val hazeState = remember { HazeState() }

    var homeScrollToTopTrigger by remember { mutableIntStateOf(0) }
    var hotScrollToTopTrigger by remember { mutableIntStateOf(0) }

    // 点击特别关注通知后跳转到该用户详情页
    LaunchedEffect(pendingProfileUserId) {
        pendingProfileUserId?.let { uid ->
            navController.navigate("profile/$uid")
            onPendingProfileConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    modifier = Modifier.hazeChild(hazeState),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected && tab is Tab.Home) {
                                    homeScrollToTopTrigger++
                                } else if (selected && tab is Tab.Hot) {
                                    hotScrollToTopTrigger++
                                } else {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // 内容不再被底栏挤压，而是铺满、从底栏下面穿过去——否则底栏背后是空的，
        // 毛玻璃无从模糊。各页把这个高度加到自己列表的 contentPadding 里。
        val bottomInset = innerPadding.calculateBottomPadding()
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    onNavigateToProfile = { uid -> navController.navigate("profile/$uid") },
                    onNavigateToSearch = { navController.navigate(ROUTE_SEARCH) },
                    scrollToTopTrigger = homeScrollToTopTrigger,
                    hazeState = hazeState,
                    bottomInset = bottomInset
                )
            }
            composable(Tab.Hot.route) {
                HotScreen(
                    onNavigateToProfile = { uid -> navController.navigate("profile/$uid") },
                    onNavigateToSearch = { navController.navigate(ROUTE_SEARCH) },
                    scrollToTopTrigger = hotScrollToTopTrigger,
                    hazeState = hazeState,
                    bottomInset = bottomInset
                )
            }
            composable(ROUTE_SEARCH) {
                SearchScreen(
                    onNavigateToProfile = { uid -> navController.navigate("profile/$uid") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Tab.Following.route) {
                FollowingScreen(
                    onNavigateToProfile = { uid -> navController.navigate("profile/$uid") },
                    onNavigateToSearch = { navController.navigate(ROUTE_SEARCH) },
                    hazeState = hazeState,
                    bottomInset = bottomInset
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(hazeState = hazeState, bottomInset = bottomInset)
            }
            composable(
                "profile/{userId}",
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { back ->
                val uid = back.arguments?.getString("userId") ?: return@composable
                ProfileScreen(
                    userId = uid,
                    onBack = { navController.popBackStack() },
                    onNavigateToFollowingList = { id, name ->
                        navController.navigate(
                            "followinglist/$id/${java.net.URLEncoder.encode(name, "UTF-8")}"
                        )
                    }
                )
            }
            composable(
                "followinglist/{userId}/{userName}",
                arguments = listOf(
                    navArgument("userId") { type = NavType.StringType },
                    navArgument("userName") { type = NavType.StringType }
                )
            ) { back ->
                val uid  = back.arguments?.getString("userId") ?: return@composable
                val name = back.arguments?.getString("userName")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: ""
                FollowingListScreen(
                    userId = uid,
                    userName = name,
                    onBack = { navController.popBackStack() },
                    onNavigateToProfile = { id -> navController.navigate("profile/$id") }
                )
            }
        }
    }
}
