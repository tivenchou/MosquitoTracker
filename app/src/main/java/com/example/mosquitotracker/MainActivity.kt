package com.example.mosquitotracker

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.example.mosquitotracker.ui.theme.MosquitoTrackerTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在 MainActivity.kt 的 onCreate 中添加 try-catch
        try {
            System.loadLibrary("magtsync")
        } catch (e: UnsatisfiedLinkError) {
            Log.w("Camera", "Optional libmagtsync not found")
        }
        setContent {
            MosquitoTrackerTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                    )
                )

                // 在 MainActivity.kt 中增加權限檢查
                LaunchedEffect(permissionsState) {
                    if (!permissionsState.allPermissionsGranted &&
                        !permissionsState.shouldShowRationale) {
                        delay(1000) // 避免立即彈出權限請求
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsState.allPermissionsGranted) {
                        CameraScreen(viewModel)
                    } else {
                        PermissionRequestScreen(permissionsState)
                    }
                }
            }
        }
    }
}