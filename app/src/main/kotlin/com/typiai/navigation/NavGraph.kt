package com.typiai.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.typiai.ui.dashboard.DashboardScreen
import com.typiai.ui.usage.UsageScreen

sealed class Screen(val route: String, val title: String, val iconName: String) {
    object Dashboard : Screen("dashboard", "Dashboard", "dashboard")
    object Usage : Screen("usage", "Usage", "bar_chart")
}

val bottomNavItems = listOf(Screen.Dashboard, Screen.Usage)

@Composable
fun TypiNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(200)) + slideInHorizontally(
                animationSpec = tween(200),
                initialOffsetX = { it / 8 }
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200)) + slideOutHorizontally(
                animationSpec = tween(200),
                targetOffsetX = { -it / 8 }
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(200)) + slideInHorizontally(
                animationSpec = tween(200),
                initialOffsetX = { -it / 8 }
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200)) + slideOutHorizontally(
                animationSpec = tween(200),
                targetOffsetX = { it / 8 }
            )
        }
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen()
        }
        composable(Screen.Usage.route) {
            UsageScreen()
        }
    }
}
