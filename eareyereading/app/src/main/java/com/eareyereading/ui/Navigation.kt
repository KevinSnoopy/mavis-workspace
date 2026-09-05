package com.eareyereading.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eareyereading.ui.screens.home.HomeScreen
import com.eareyereading.ui.screens.library.LibraryScreen
import com.eareyereading.ui.screens.reader.ReaderScreen
import com.eareyereading.ui.screens.review.ReviewScreen
import com.eareyereading.ui.screens.settings.SettingsScreen
import com.eareyereading.ui.screens.vocabulary.VocabularyScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Library : Screen("library")
    data object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long) = "reader/$bookId"
    }
    data object Vocabulary : Screen("vocabulary")
    data object Review : Screen("review")
    data object Settings : Screen("settings")
    data object DictionaryManager : Screen("dictionary_manager")
}

/**
 * 顶层页面统一导航：不堆叠重复路由、保留/恢复各标签状态。
 * 底部导航栏与首页/书库的快捷入口共用，行为保持一致。
 */
fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem(Screen.Library, "书库", Icons.Filled.LibraryBooks, Icons.Outlined.LibraryBooks),
    BottomNavItem(Screen.Vocabulary, "词汇", Icons.Filled.School, Icons.Outlined.School),
    BottomNavItem(Screen.Review, "复习", Icons.Filled.Replay, Icons.Outlined.Replay),
    BottomNavItem(Screen.Settings, "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

// 转场 lambda 提升为顶层常量：不捕获任何状态，旧实现写在 NavHost 参数里，
// AppNavigation 每次重组（每次导航都会）都新建 4 个实例传给 AnimatedContent。
// 类型必须显式声明为 NavHost 期望的 AnimatedContentTransitionScope 扩展 lambda
private val NavEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(220)) +
        slideInVertically(
            animationSpec = tween(220),
            initialOffsetY = { it / 12 },
        )
}
private val NavExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(150))
}
private val NavPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(220))
}
private val NavPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(150)) +
        slideOutVertically(
            animationSpec = tween(220),
            targetOffsetY = { it / 12 },
        )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 是否显示底部导航（阅读器页面隐藏）
    val showBottomBar = currentDestination?.route?.startsWith("reader") != true

    // 选中路由集合按 currentDestination 记忆：旧实现 5 个导航项 × 每次重组
    // 都遍历一遍 hierarchy 链
    val selectedRoutes = remember(currentDestination) {
        currentDestination?.hierarchy?.mapNotNull { it.route }?.toSet() ?: emptySet()
    }

    run {
        // M3 expanded 断点（≥840dp）：平板/横屏/折叠屏展开态换 NavigationRail，
        // 5 个标签的底部栏在宽屏上挤压内容，侧栏是 M3 自适应规范做法。
        // 用 LocalConfiguration 判断：BoxWithConstraints 会把整个子树推迟到
        // 首次布局后组合（还随断点翻转全子树重组），只为一个 840dp 判断不值
        val isExpanded = LocalConfiguration.current.screenWidthDp >= 840

        Scaffold(
            // issue 3.9：不要在这里额外预留系统栏 Insets（enableEdgeToEdge 后系统栏
            // 由各页面自己的 TopAppBar 处理 statusBars 单一叠加）。根层再叠加会把
            // 状态栏高度算两遍（88-94dp）。
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBottomBar && !isExpanded) {
                    // 标准 M3 NavigationBarItem：图标药丸指示器 + 主题色令牌。
                    // 原实现是自绘 Box（选中整块填充主色 + 白字），是 web 标签页
                    // 风格，且绕过了涟漪/无障碍/状态层的默认行为
                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            val selected = item.screen.route in selectedRoutes
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(item.screen.route) },
                                icon = {
                                    Icon(
                                        if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (showBottomBar && isExpanded) {
                    NavigationRail {
                        bottomNavItems.forEach { item ->
                            val selected = item.screen.route in selectedRoutes
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(item.screen.route) },
                                icon = {
                                    Icon(
                                        if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }

            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.weight(1f),
                // M3 SharedAxis 风格转场：淡入 + 轻微上移（Material Motion 的
                // Z 轴共享位移，微信读书/Keep 的页面切换质感），替代原先的瞬切。
                // 进入 220ms / 退出 150ms：新页面快速就位，旧页面不拖沓。
                // lambda 为顶层常量（见文件头注释）
                enterTransition = NavEnterTransition,
                exitTransition = NavExitTransition,
                popEnterTransition = NavPopEnterTransition,
                popExitTransition = NavPopExitTransition,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        // 与底部导航栏同一套导航选项：连续点击不再堆叠重复路由，
                        // 返回键不会穿过一串相同页面
                        onNavigateToLibrary = { navController.navigateToTopLevel(Screen.Library.route) },
                        onNavigateToReview = { navController.navigateToTopLevel(Screen.Review.route) },
                        onNavigateToSettings = { navController.navigateToTopLevel(Screen.Settings.route) },
                        onBookClick = { bookId ->
                            // launchSingleTop：快速双击同一本书不再堆叠两个相同的
                            // reader 栈项（两个存活 VM 共享 TTS 单例会互相打架）
                            navController.navigate(Screen.Reader.createRoute(bookId)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(Screen.Library.route) {
                    LibraryScreen(
                        onBookClick = { bookId ->
                            navController.navigate(Screen.Reader.createRoute(bookId)) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = { navController.navigateToTopLevel(Screen.Settings.route) },
                    )
                }

                composable(
                    route = Screen.Reader.route,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
                    ReaderScreen(
                        bookId = bookId,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Screen.Vocabulary.route) {
                    VocabularyScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Review.route) {
                    ReviewScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToDictionaryManager = { navController.navigate(Screen.DictionaryManager.route) },
                    )
                }

                composable(Screen.DictionaryManager.route) {
                    com.eareyereading.ui.screens.dictionary.DictionaryManagerScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            }
        }
    }
}
