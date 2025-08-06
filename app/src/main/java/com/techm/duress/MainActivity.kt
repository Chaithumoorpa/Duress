package com.techm.duress

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.apitest.ui.theme.APITestTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationServices
import com.techm.duress.core.permissions.PermissionManager
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.views.screens.HomeScreen
import com.techm.duress.views.screens.LoginScreen
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var updateJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (!PermissionManager.hasCameraPermission(this)) {
            PermissionManager.requestCameraPermission(this, 1001)
        }
        setContent {
            APITestTheme {
                AppNavigation(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        updateJob?.cancel()
    }
}


@Composable
fun AppNavigation(context: Context) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    NavHost(navController = navController, startDestination = "login_screen") {
        composable("login_screen") {
            LoginScreen(navController, viewModel)
        }
        composable(
            "home_screen/{userName}",
            arguments = listOf(
                navArgument("userName") { type = NavType.StringType },
                //navArgument("userZone") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val userName = backStackEntry.arguments?.getString("userName") ?: ""
            //val userZone = backStackEntry.arguments?.getString("userZone") ?: ""
            HomeScreen(context,viewModel, userName)
        }
    }
}
