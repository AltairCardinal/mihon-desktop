package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.preference.CheckBoxPreference
import eu.kanade.tachiyomi.source.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.JvmPreferenceItem
import eu.kanade.tachiyomi.source.preference.ListPreference
import eu.kanade.tachiyomi.source.preference.MultiSelectListPreference
import eu.kanade.tachiyomi.source.preference.PreferenceCategoryItem
import eu.kanade.tachiyomi.source.preference.SwitchPreference

/**
 * Desktop implementation of PreferenceScreen.
 *
 * Acts as a descriptor collector. Accepts both:
 * - [JvmPreferenceItem] subclasses (desktop-native extensions)
 * - `androidx.preference.Preference` instances (Android-compat extensions compiled for JVM),
 *   converted via reflection so that Mihon does not need a compile-time dependency on
 *   the android-compat library.
 *
 * The [context] property is initially null. It can be set by calling [setContext] before
 * invoking [ConfigurableSource.setupPreferenceScreen]; this allows extensions that create
 * preference objects via `SwitchPreferenceCompat(context)` to work without NPE.
 */
actual class PreferenceScreen {

    private val _preferences = mutableListOf<JvmPreferenceItem>()

    val preferences: List<JvmPreferenceItem> get() = _preferences.toList()

    /** Null on desktop unless set via [setContext]. */
    var context: Any? = null
        private set

    /** Injects a context object (typically an android.content.Context instance from the extension's
     *  classloader) so that extensions using `SwitchPreferenceCompat(context)` do not NPE. */
    fun setContext(ctx: Any?) {
        context = ctx
    }

    fun addPreference(preference: JvmPreferenceItem) {
        _preferences.add(preference)
    }

    /**
     * Accepts [androidx.preference.Preference] objects (from android-compat JARs)
     * and converts them to [JvmPreferenceItem] via reflection.
     * Falls back silently if conversion is not possible.
     *
     * PreferenceCategory objects are flattened: the category header is added first,
     * followed by all sub-preferences extracted from the category.
     */
    fun addPreference(preference: Any) {
        when (preference) {
            is JvmPreferenceItem -> _preferences.add(preference)
            else -> reflectConvertAll(preference).forEach { _preferences.add(it) }
        }
    }

    /**
     * Converts a preference object (possibly a category) to one or more [JvmPreferenceItem]s.
     */
    @Suppress("SwallowedException")
    private fun reflectConvertAll(pref: Any): List<JvmPreferenceItem> = try {
        val items = mutableListOf<JvmPreferenceItem>()
        val cls = pref::class.java
        val simpleName = cls.simpleName ?: ""

        if (simpleName == "PreferenceCategory") {
            val key = cls.method("getKey")?.invoke(pref) as? String ?: ""
            val title = cls.method("getTitle")?.invoke(pref)?.toString() ?: ""
            items.add(PreferenceCategoryItem(key = key, title = title))
            // Extract sub-preferences stored in the private _prefs field
            try {
                val field = cls.getDeclaredField("_prefs").also { it.isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                val subPrefs = field.get(pref) as? List<*> ?: emptyList<Any>()
                subPrefs.forEach { sub ->
                    if (sub != null) items.addAll(reflectConvertAll(sub))
                }
            } catch (_: Exception) {
                // _prefs field not accessible; skip sub-preferences
            }
        } else {
            reflectConvertSingle(pref, cls)?.let { items.add(it) }
        }
        items
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("SwallowedException")
    private fun reflectConvertSingle(pref: Any, cls: Class<*>): JvmPreferenceItem? = try {
        val key = cls.method("getKey")?.invoke(pref) as? String ?: ""
        val title = cls.method("getTitle")?.invoke(pref)?.toString() ?: ""
        val summary = cls.method("getSummary")?.invoke(pref)?.toString()
        val isEnabled = cls.method("isEnabled")?.invoke(pref) as? Boolean ?: true
        val isVisible = cls.method("isVisible")?.invoke(pref) as? Boolean ?: true

        val item: JvmPreferenceItem? = when (cls.simpleName) {
            "SwitchPreferenceCompat", "SwitchPreference", "TwoStatePreference" -> {
                val checked = cls.method("isChecked")?.invoke(pref) as? Boolean ?: false
                val defaultValue = cls.field("defaultValue")?.get(pref) as? Boolean ?: checked
                SwitchPreference(key = key, title = title, defaultValue = defaultValue)
            }
            "CheckBoxPreference" -> {
                val checked = cls.method("isChecked")?.invoke(pref) as? Boolean ?: false
                CheckBoxPreference(key = key, title = title, defaultValue = checked)
            }
            "EditTextPreference" -> {
                val text = cls.method("getText")?.invoke(pref) as? String
                val defaultValue = text ?: cls.field("defaultValue")?.get(pref) as? String ?: ""
                val dialogTitle = cls.method("getDialogTitle")?.invoke(pref)?.toString()
                EditTextPreference(
                    key = key,
                    title = title,
                    defaultValue = defaultValue,
                    dialogTitle = dialogTitle,
                )
            }
            "ListPreference" -> {
                val entries = (cls.method("getEntries")?.invoke(pref) as? Array<*>)
                    ?.map { it.toString() } ?: emptyList()
                val values = (cls.method("getEntryValues")?.invoke(pref) as? Array<*>)
                    ?.map { it.toString() } ?: emptyList()
                val value = cls.method("getValue")?.invoke(pref) as? String
                val defaultValue = value ?: cls.field("defaultValue")?.get(pref)?.toString()
                ListPreference(
                    key = key,
                    title = title,
                    entries = entries,
                    entryValues = values,
                    defaultValue = defaultValue,
                )
            }
            "MultiSelectListPreference" -> {
                val entries = (cls.method("getEntries")?.invoke(pref) as? Array<*>)
                    ?.map { it.toString() } ?: emptyList()
                val values = (cls.method("getEntryValues")?.invoke(pref) as? Array<*>)
                    ?.map { it.toString() } ?: emptyList()

                @Suppress("UNCHECKED_CAST")
                val selected = cls.method("getValues")?.invoke(pref) as? Set<String> ?: emptySet()

                @Suppress("UNCHECKED_CAST")
                val defaultValue = cls.field("defaultValue")?.get(pref) as? Set<String> ?: selected
                MultiSelectListPreference(
                    key = key,
                    title = title,
                    entries = entries,
                    entryValues = values,
                    defaultValue = defaultValue,
                )
            }
            else -> null
        }
        item?.also {
            it.summary = summary
            it.isEnabled = isEnabled
            it.isVisible = isVisible
        }
    } catch (_: Exception) {
        null
    }

    private fun Class<*>.method(name: String) = try {
        getMethod(name)
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun Class<*>.field(name: String) = try {
        getField(name)
    } catch (_: NoSuchFieldException) {
        null
    }
}
