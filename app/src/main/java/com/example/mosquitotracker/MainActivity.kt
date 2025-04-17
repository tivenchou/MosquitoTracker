package com.example.mosquitotracker

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.example.mosquitotracker.ui.theme.MosquitoTrackerTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val performanceTracker = PerformanceTracker()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        try {
            System.loadLibrary("magtsync")
        } catch (e: UnsatisfiedLinkError) {
            Log.w("NativeLib", "Optional library not found")
        }

        setContent {
            MosquitoTrackerTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                    )
                )

                LaunchedEffect(permissionsState) {
                    if (!permissionsState.allPermissionsGranted &&
                        !permissionsState.shouldShowRationale) {
                        delay(1000)
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsState.allPermissionsGranted) {
                        performanceTracker.logFrame()
                        CameraScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PermissionRequestScreen(permissionsState)
                    }
                }
            }
        }
    }
}

class PerformanceTracker {
    private var frameCount = 0
    private var lastLogTime = System.currentTimeMillis()

    fun logFrame() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 1000) {
            Log.d("Performance", "FPS: $frameCount")
            frameCount = 0
            lastLogTime = currentTime
        }
    }
}