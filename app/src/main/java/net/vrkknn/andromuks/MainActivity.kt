package net.vrkknn.andromuks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndromuksTheme {
                AppNavigation(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun AppNavigation(modifier: Modifier) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    NavHost(
        navController = navController,
        startDestination = "auth_check",
        modifier = modifier
    ) {
        composable("login") { LoginScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("auth_check") { AuthCheckScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("room_list") { RoomListScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable(
            route = "room_timeline/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            val roomName = appViewModel.getRoomById(roomId)?.name ?: ""
            RoomTimelineScreen(
                roomId = roomId,
                roomName = roomName,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
    }
}