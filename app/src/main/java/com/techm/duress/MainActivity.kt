package com.techm.duress

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.apitest.ui.theme.APITestTheme
import com.techm.duress.core.permissions.PermissionManager
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.views.screens.HomeScreen
import com.techm.duress.views.screens.LoginScreen

private const val TAG = "DU/MainActivity"

class MainActivity : ComponentActivity() {

    // Modern permission launcher (no need to override onRequestPermissionsResult)
    private val streamingPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.filterValues { it }.keys
            val denied  = result.filterValues { !it }.keys
            Log.d(TAG, "STREAMING perms → granted=$granted denied=$denied")

            if (denied.isNotEmpty()) {
                // Check if any denied permission is permanently denied (Don't ask again)
                val anyPermanentlyDenied = denied.any { p ->
                    !PermissionManager.shouldShowRationale(this, p)
                }
                if (anyPermanentlyDenied) {
                    Log.w(TAG, "Some permissions permanently denied. Opening app settings…")
                    PermissionManager.openAppSettings(this)
                } else {
                    Log.w(TAG, "Some permissions denied. App will have limited functionality.")
                    // You can re-request later from feature entry points if desired.
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for the minimal set your app needs (camera+mic+fine/coarse+notifications on 13+).
        val streamingPerms: Array<String> = PermissionManager.streamingRequiredPermissions(this)
        if (!PermissionManager.hasAll(this, streamingPerms)) {
            val showRationale = PermissionManager.shouldShowAnyRationale(this, streamingPerms)
            if (showRationale) {
                Log.d(TAG, "User previously denied; consider showing a rationale UI here.")
                // For now, we proceed to request again.
            }
            Log.d(TAG, "Requesting streaming bundle permissions…")
            streamingPermLauncher.launch(streamingPerms)
        }

        setContent {
            APITestTheme {
                AppNavigation()
            }
        }
    }
}

/**
 * App navigation graph. Creates a single shared MainViewModel scoped to the activity.
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    NavHost(navController = navController, startDestination = "login_screen") {
        composable("login_screen") {
            LoginScreen(navController = navController, viewModel = viewModel)
        }
        composable(
            route = "home_screen/{userName}",
            arguments = listOf(navArgument("userName") { type = NavType.StringType })
        ) { backStackEntry ->
            val userName = backStackEntry.arguments?.getString("userName").orEmpty()
            HomeScreen(
                context2 = navController.context, // matches your current HomeScreen signature
                viewModel = viewModel,
                userName = userName
            )
        }
    }
}
