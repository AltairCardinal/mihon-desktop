package eu.kanade.tachiyomi.source.preference

/**
 * Base class for all JVM/Desktop preference item descriptors.
 * Extensions that wish to support the desktop platform use these classes
 * instead of Android's androidx.preference.* classes.
 */
sealed class JvmPreferenceItem {
    abstract val key: String
    abstract val title: String
    var summary: String? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true
}

data class SwitchPreference(
    override val key: String,
    override val title: String,
    val defaultValue: Boolean = false,
) : JvmPreferenceItem()

/** Alias matching Android's class name for easier cross-platform porting. */
typealias SwitchPreferenceCompat = SwitchPreference

data class CheckBoxPreference(
    override val key: String,
    override val title: String,
    val defaultValue: Boolean = false,
) : JvmPreferenceItem()

data class EditTextPreference(
    override val key: String,
    override val title: String,
    val defaultValue: String = "",
) : JvmPreferenceItem() {
    var dialogTitle: String? = null
    var validator: ((String) -> String?)? = null
}

data class ListPreference(
    override val key: String,
    override val title: String,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val defaultValue: String? = null,
) : JvmPreferenceItem()

data class MultiSelectListPreference(
    override val key: String,
    override val title: String,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val defaultValue: Set<String> = emptySet(),
) : JvmPreferenceItem()

/**
 * Visual section header rendered as a divider + label between groups of preferences.
 * Corresponds to Android's [androidx.preference.PreferenceCategory].
 */
data class PreferenceCategoryItem(
    override val key: String = "",
    override val title: String,
) : JvmPreferenceItem()
