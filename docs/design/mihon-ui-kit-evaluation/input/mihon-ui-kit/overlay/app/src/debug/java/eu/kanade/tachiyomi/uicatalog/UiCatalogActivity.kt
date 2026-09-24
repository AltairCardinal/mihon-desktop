package eu.kanade.tachiyomi.uicatalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.BuildConfig

/** A second launcher entry compiled only from src/debug. No external intent parameters are consumed. */
class UiCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        setContent {
            // Preview theme has no preference writes. Test real theme and locale through device configuration.
            TachiyomiPreviewTheme { UiCatalog() }
        }
    }
}
