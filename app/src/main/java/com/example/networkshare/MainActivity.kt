package com.example.networkshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent // Added missing import
import androidx.activity.compose.rememberLauncherForActivityResult // Added missing import
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.networkshare.ui.theme.NetworkShareTheme

/**
 * Checks if the MANAGE_EXTERNAL_STORAGE permission is granted.
 * This check is only relevant for Android 11 (API 30) and above.
 */
fun isManageAllFilesPermissionGranted(context: Context): Boolean {
    // MANAGE_EXTERNAL_STORAGE check is only valid on Android 11 (R) and higher
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // Fallback check for older APIs (API 29 and below)
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetworkShareTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // The main UI is now a composable that handles permission checks
                    PermissionCheckScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PermissionCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // State to hold the current permission status
    var isGranted by remember { 
        mutableStateOf(isManageAllFilesPermissionGranted(context)) 
    }
    
    // Use DisposableEffect to re-check permission status when the activity resumes (e.g., after returning from Settings)
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Update the state variable when the app resumes
                isGranted = isManageAllFilesPermissionGranted(context)
                if (isGranted) {
                    Log.d("FileShareApp", "MANAGE_EXTERNAL_STORAGE granted on resume.")
                }
            }
        }
        // Attach the observer to the activity's lifecycle
        (context as? LifecycleOwner)?.lifecycle?.addObserver(observer)
        
        // Cleanup: remove the observer when the composable is removed from the screen
        onDispose {
            (context as? LifecycleOwner)?.lifecycle?.removeObserver(observer)
        }
    }
    
    // 2. Define the ActivityResultLauncher using Compose's API
    val storageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // This callback runs immediately after the user returns from settings (before onResume). 
        // The DisposableEffect onResume check will confirm the final status.
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isGranted) {
                    Text(
                        text = "Access Granted",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Your File Sharing server can now access all files. Ready to start network sharing.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Button(
                        onClick = { /* TODO: Start your network file server here */ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Start File Server")
                    }
                } else {
                    Text(
                        text = "Access Required",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "This app requires 'Allow access to manage all files' for network sharing functionality.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Button(onClick = { 
                        // Launch the Intent to request the permission
                        requestManageAllFilesPermission(context, storageSettingsLauncher)
                    }) {
                        Text("Grant All Files Access in Settings")
                    }
                }
            }
        }
    }
}

/**
 * Launches the Intent to prompt the user to grant "All Files Access".
 * This redirects the user to the specific Android Settings page for the app.
 */
fun requestManageAllFilesPermission(
    context: Context,
    launcher: ActivityResultLauncher<Intent>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            // Intent action to go directly to the app's 'Manage all files' setting
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:" + context.packageName)
            launcher.launch(intent)

        } catch (e: Exception) {
            // Fallback: If the specific Intent fails, launch the general management screen
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            launcher.launch(intent)
            Log.e("FileShareApp", "Failed to launch specific settings page: ${e.message}")
        }
    }
}