package android.content

import android.os.Bundle

/**
 * Desktop stub for android.content.Intent.
 * Minimal implementation for extensions that create or inspect Intents.
 */
open class Intent {
    var action: String? = null
    private val extras = Bundle()

    constructor()
    constructor(action: String) { this.action = action }
    constructor(action: String, uri: android.net.Uri?) { this.action = action }

    fun putExtra(name: String, value: String?): Intent = apply { extras.putString(name, value) }
    fun putExtra(name: String, value: Int): Intent = apply { extras.putInt(name, value) }
    fun putExtra(name: String, value: Long): Intent = apply { extras.putLong(name, value) }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras.putBoolean(name, value) }
    fun putExtra(name: String, value: Float): Intent = apply { extras.putFloat(name, value) }
    fun putExtra(name: String, value: Double): Intent = apply { extras.putDouble(name, value) }

    fun getStringExtra(name: String): String? = extras.getString(name)
    fun getIntExtra(name: String, defaultValue: Int): Int = extras.getInt(name, defaultValue)
    fun getLongExtra(name: String, defaultValue: Long): Long = extras.getLong(name, defaultValue)
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = extras.getBoolean(name, defaultValue)
    fun getFloatExtra(name: String, defaultValue: Float): Float = extras.getFloat(name, defaultValue)
    fun getDoubleExtra(name: String, defaultValue: Double): Double = extras.getDouble(name)

    fun getExtras(): Bundle = extras
    fun hasExtra(name: String): Boolean = extras.containsKey(name)

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }
}
