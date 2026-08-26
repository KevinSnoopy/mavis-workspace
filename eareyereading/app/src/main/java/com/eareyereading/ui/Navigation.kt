package com.eareyereading.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eareyereading.ui.screens.library.LibraryScreen
import com.eareyereading.ui.screens.reader.ReaderScreen
import com.eareyereading.ui.screens.review.ReviewScreen
import com.eareyereading.ui.screens.settings.SettingsScreen
import com.eareyereading.ui.screens.vocabulary.VocabularyScreen

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long) = "reader/$bookId"
    }
    data object Vocabulary : Screen("vocabulary")
    data object Review : Screen("review")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Library.route,
    ) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onNavigateToVocabulary = {
                    navController.navigate(Screen.Vocabulary.route)
                },
                onNavigateToReview = {
                    navController.navigate(Screen.Review.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
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
