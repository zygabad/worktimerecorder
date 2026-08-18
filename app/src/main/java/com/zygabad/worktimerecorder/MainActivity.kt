package com.zygabad.worktimerecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zygabad.worktimerecorder.ui.MainScreen
import com.zygabad.worktimerecorder.ui.SettingsScreen
import com.zygabad.worktimerecorder.ui.theme.WorkTimeRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkTimeRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "main") {
                        composable("main") { MainScreen(nav) }
                        composable("settings") { SettingsScreen(nav) }
                    }
                }
            }
        }
    }
}
