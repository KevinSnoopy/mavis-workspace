package com.eareyereading.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 是否显示底部导航（阅读器页面隐藏）
    val showBottomBar = currentDestination?.route?.startsWith("reader") != true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = androidx.compose.ui.unit.dp.times(0),
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToLibrary = { navController.navigate(Screen.Library.route) },
                    onNavigateToVocabulary = { navController.navigate(Screen.Vocabulary.route) },
                    onNavigateToReview = { navController.navigate(Screen.Review.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onBookClick = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) },
                )
            }

            composable(Screen.Library.route) {
                LibraryScreen(
                    onBookClick = { bookId ->
                        navController.navigate(Screen.Reader.createRoute(bookId))
                    },
                    onNavigateToVocabulary = { navController.navigate(Screen.Vocabulary.route) },
                    onNavigateToReview = { navController.navigate(Screen.Review.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
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
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
