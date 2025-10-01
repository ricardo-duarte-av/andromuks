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
import net.vrkknn.andromuks.database.DatabaseInitializer
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Enhanced MainActivity with database integration
 * 
 * This version initializes the database and provides migration support
 * while maintaining backward compatibility with the existing UI.
 */
class MainActivityWithDatabase : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private lateinit var databaseInitializer: DatabaseInitializer
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize database
        databaseInitializer = DatabaseInitializer(this, CoroutineScope(Dispatchers.Main))
        
        setContent {
            AndromuksTheme {
                AppNavigationWithDatabase(
                    modifier = Modifier.fillMaxSize(),
                    onViewModelCreated = { viewModel ->
                        appViewModel = viewModel
                        // Initialize database integration
                        initializeDatabaseIntegration()
                    }
                )
            }
        }
    }
    
    /**
     * Initialize database integration
     */
    private fun initializeDatabaseIntegration() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Initialize database
                databaseInitializer.initialize()
                
                // Load cached user profiles on app startup
                appViewModel.loadCachedProfiles(this@MainActivityWithDatabase)
                
                android.util.Log.d("MainActivityWithDatabase", "Database integration initialized")
            } catch (e: Exception) {
                android.util.Log.e("MainActivityWithDatabase", "Failed to initialize database integration", e)
            }
        }
    }
    
    /**
     * Perform migration from existing data
     */
    fun performMigration(
        rooms: List<RoomItem>,
        events: Map<String, List<TimelineEvent>>,
        runId: String?,
        lastReceivedId: Long,
        userId: String?,
        deviceId: String?,
        homeserverUrl: String?,
        imageAuthToken: String?
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                databaseInitializer.performMigration(
                    rooms = rooms,
                    events = events,
                    runId = runId,
                    lastReceivedId = lastReceivedId,
                    userId = userId,
                    deviceId = deviceId,
                    homeserverUrl = homeserverUrl,
                    imageAuthToken = imageAuthToken
                )
                
                android.util.Log.d("MainActivityWithDatabase", "Migration completed")
            } catch (e: Exception) {
                android.util.Log.e("MainActivityWithDatabase", "Failed to perform migration", e)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameVisible()
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameInvisible()
        }
    }
}

@Composable
fun AppNavigationWithDatabase(
    modifier: Modifier,
    onViewModelCreated: (AppViewModel) -> Unit = {}
) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    
    // Notify the parent about the ViewModel creation
    onViewModelCreated(appViewModel)
    
    NavHost(
        navController = navController,
        startDestination = "auth_check",
        modifier = modifier
    ) {
        composable("login") { 
            LoginScreen(
                navController = navController, 
                modifier = modifier, 
                appViewModel = appViewModel
            ) 
        }
        composable("auth_check") { 
            AuthCheckScreen(
                navController = navController, 
                modifier = modifier, 
                appViewModel = appViewModel
            ) 
        }
        composable("room_list") { 
            RoomListScreen(
                navController = navController, 
                modifier = modifier, 
                appViewModel = appViewModel
            ) 
        }
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
        composable(
            route = "invite_detail/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            InviteDetailScreen(
                roomId = roomId,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
    }
}
