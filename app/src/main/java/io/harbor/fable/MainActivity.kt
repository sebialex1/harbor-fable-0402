package io.harbor.fable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.harbor.fable.ui.FableRoot
import io.harbor.fable.ui.theme.FableTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FableTheme {
                FableRoot()
            }
        }
    }
}
