package eu.kanade.tachiyomi.crash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import eu.kanade.presentation.crash.CrashScreen
import eu.kanade.tachiyomi.ui.main.MainActivity

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val safeRecovery = intent.getBooleanExtra("sync-safe-recovery", false)
        val exception = if (safeRecovery) null else GlobalExceptionHandler.getThrowableFromIntent(intent)
        setContent {
            MaterialTheme {
                Surface {
                    if (safeRecovery) {
                        eu.kanade.tachiyomi.data.sync.AndroidSafeRecoveryContent()
                    } else {
                        CrashScreen(
                            exception = exception,
                            onRestartClick = {
                                finishAffinity()
                                startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                            },
                        )
                    }
                }
            }
        }
    }
}
