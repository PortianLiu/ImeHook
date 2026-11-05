package lpt.imehook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

class SettingsActivity : ComponentActivity() {

    private lateinit var ui: SettingsUiState

    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ui.importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = SettingsUiState(
            this,
            SchemeStore(getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE))
        )
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
            }
            ImeHookTheme {
                SettingsScreen(ui, onImport = { openDoc.launch(arrayOf("*/*")) })
            }
        }
    }
}
