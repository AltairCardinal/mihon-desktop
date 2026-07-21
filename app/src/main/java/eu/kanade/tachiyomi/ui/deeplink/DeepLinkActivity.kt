package eu.kanade.tachiyomi.ui.deeplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.main.MainActivity

class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(intent.forwardToMainActivity(applicationContext))
        finish()
    }
}

internal fun Intent.forwardToMainActivity(context: Context): Intent = apply {
    flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    setClass(context, MainActivity::class.java)
}
