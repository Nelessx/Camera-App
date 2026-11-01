package com.nelessx.cameraapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nelessx.cameraapp.ui.CameraScreen
import com.nelessx.cameraapp.ui.theme.CameraAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Always dark: the camera UI sits on black regardless of the system theme
            CameraAppTheme(darkTheme = true) {
                CameraScreen()
            }
        }
    }
}
