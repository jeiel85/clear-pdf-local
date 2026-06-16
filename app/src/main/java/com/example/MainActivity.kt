package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PdfViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Instantiate PdfViewModel via our Factory cleanly
    val viewModel: PdfViewModel by viewModels {
      PdfViewModel.Factory(applicationContext as Application)
    }

    setContent {
      val appTheme by viewModel.currentTheme.collectAsState()
      val darkTheme = when (appTheme) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
      }

      MyApplicationTheme(darkTheme = darkTheme) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }

        // Observe ViewModel generic notifications as toast bubbles
        LaunchedEffect(Unit) {
          viewModel.operationMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
          }
        }

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
          // Android NavHost binds all compiled routes together
          NavHost(
            navController = navController,
            startDestination = "home"
          ) {
            composable("home") {
              HomeScreen(
                viewModel = viewModel,
                onNavigateToViewer = { encodedUri ->
                  navController.navigate("viewer/$encodedUri")
                },
                onNavigateToImageToPdf = {
                  navController.navigate("image_to_pdf")
                },
                onNavigateToScan = {
                  navController.navigate("scan")
                },
                onNavigateToMerge = {
                  navController.navigate("merge")
                },
                onNavigateToSplit = {
                  navController.navigate("split")
                },
                onNavigateToSettings = {
                  navController.navigate("settings")
                }
              )
            }
            
            composable(
              route = "viewer/{uri}",
              arguments = listOf(navArgument("uri") { type = NavType.StringType })
            ) { backStackEntry ->
              val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
              ViewerScreen(
                viewModel = viewModel,
                encodedUriString = encodedUri,
                onNavigateBack = { navController.popBackStack() }
              )
            }

            composable("image_to_pdf") {
              ImageToPdfScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
              )
            }

            composable("scan") {
              ScanScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
              )
            }

            composable("merge") {
              MergeScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
              )
            }

            composable("split") {
              SplitScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
              )
            }

            composable("settings") {
              SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
              )
            }
          }
        }
      }
    }
  }
}

@androidx.compose.runtime.Composable
fun Greeting(name: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
  androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}

