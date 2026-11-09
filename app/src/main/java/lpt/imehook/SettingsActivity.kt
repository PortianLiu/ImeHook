package lpt.imehook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

class SettingsActivity : ComponentActivity() {

    private lateinit var ui: SettingsUiState

    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ui.importFrom(uri)
        }

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ui.templateContent().toByteArray())
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "导出失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

    private val createLogDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ui.logText().toByteArray())
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "导出失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
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
                SettingsScreen(
                    ui,
                    onImport = { openDoc.launch(arrayOf("*/*")) },
                    onExport = { createDoc.launch(ui.exportTemplateName()) },
                    onExportLog = { createLogDoc.launch(ui.exportFileName()) },
                )
            }
        }
    }
}
