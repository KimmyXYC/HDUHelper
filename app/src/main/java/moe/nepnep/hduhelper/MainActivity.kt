package moe.nepnep.hduhelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.nepnep.hduhelper.data.settings.ThemeMode
import moe.nepnep.hduhelper.ui.AppViewModel
import moe.nepnep.hduhelper.ui.HDUHelperApp
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HDUHelperApplication).container
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            val campusModel: moe.nepnep.hduhelper.ui.CampusCodeViewModel = viewModel(factory = moe.nepnep.hduhelper.ui.CampusCodeViewModel.factory(container))
            val settings by model.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            HDUHelperTheme(darkTheme = dark) { HDUHelperApp(model, campusModel) }
        }
    }
}
