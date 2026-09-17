package com.faceswap.bulk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.faceswap.bulk.ui.BulkSwapScreen
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Since OpenCV 4.9's Maven Central distribution, the native libs are
        // packaged in the AAR and loaded like any other JNI lib — no
        // OpenCV Manager app or async init callback needed.
        OpenCVLoader.initLocal()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BulkSwapScreen()
                }
            }
        }
    }
}
