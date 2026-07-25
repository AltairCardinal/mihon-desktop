package mihon.desktop.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.core.common.preference.Preference
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class DesktopLanguageOption(
    val languageTag: String,
    val displayName: String,
    val localizedDisplayName: String?,
)

sealed interface DesktopLocaleApplyResult {
    data class Applied(val languageTag: String, val locale: Locale) : DesktopLocaleApplyResult
    data class Fallback(val rejectedTag: String, val locale: Locale) : DesktopLocaleApplyResult
    data class Failed(val previousLanguageTag: String, val cause: Throwable) : DesktopLocaleApplyResult
}

data class DesktopLocaleFeedback(
    val id: Long,
    val result: DesktopLocaleApplyResult,
)

/**
 * Desktop boundary for the application locale.
 *
 * The empty language tag follows the JVM's locale as captured before Mihon applies an app override.
 * Supported tags mirror fixed-main's generated Android `locales_config`; source language preferences
 * deliberately do not participate in this adapter.
 */
class DesktopLocaleAdapter(
    private val preference: Preference<String>,
    private val systemLocale: Locale = DesktopSystemLocale.value,
    private val setJvmDefault: (Locale) -> Unit = { locale -> Locale.setDefault(locale) },
) {
    private val mutableActiveLanguageTag = MutableStateFlow(normalizeSupported(preference.get()) ?: "")
    private val mutablePendingFeedback = MutableStateFlow<DesktopLocaleFeedback?>(null)
    private val feedbackIds = AtomicLong()

    val activeLanguageTag: StateFlow<String> = mutableActiveLanguageTag.asStateFlow()
    val pendingFeedback: StateFlow<DesktopLocaleFeedback?> = mutablePendingFeedback.asStateFlow()
    val authoritativeLanguageTags: List<String> get() = fixedMainLanguageTags

    /** Recreates remembered Desktop UI resources whenever the active app locale changes. */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val activeTag by activeLanguageTag.collectAsState()
        key(activeTag) { content() }
    }

    @Synchronized
    fun applyPersisted(): DesktopLocaleApplyResult {
        val stored = preference.get()
        if (stored.isEmpty()) return applyWithoutWrite("", systemLocale)
        val normalized = normalizeSupported(stored)
        if (normalized == null) {
            val repairFailure = runCatching { preference.set("") }.exceptionOrNull()
            val applyResult = applyWithoutWrite("", systemLocale)
            if (repairFailure == null && applyResult is DesktopLocaleApplyResult.Applied) {
                return DesktopLocaleApplyResult.Fallback(stored, systemLocale)
            }
            val failure = repairFailure ?: (applyResult as DesktopLocaleApplyResult.Failed).cause
            if (repairFailure != null && applyResult is DesktopLocaleApplyResult.Failed) {
                repairFailure.addSuppressed(applyResult.cause)
            }
            return DesktopLocaleApplyResult.Failed(mutableActiveLanguageTag.value, failure)
        }
        if (stored != normalized) {
            val repairFailure = runCatching { preference.set(normalized) }.exceptionOrNull()
            if (repairFailure != null) {
                val applyResult = applyWithoutWrite(normalized, localeForTag(normalized))
                if (applyResult is DesktopLocaleApplyResult.Failed) repairFailure.addSuppressed(applyResult.cause)
                return DesktopLocaleApplyResult.Failed(mutableActiveLanguageTag.value, repairFailure)
            }
        }
        return applyWithoutWrite(normalized, localeForTag(normalized))
    }

    @Synchronized
    fun select(languageTag: String): DesktopLocaleApplyResult {
        val normalized = if (languageTag.isEmpty()) "" else normalizeSupported(languageTag)
            ?: return failed(
                IllegalArgumentException("Unsupported application locale: $languageTag"),
            )
        val previous = mutableActiveLanguageTag.value
        val previousStored = preference.get()
        val preferenceWasSet = preference.isSet()
        try {
            preference.set(normalized)
        } catch (failure: Throwable) {
            restorePreference(preferenceWasSet, previousStored, failure)
            reconcileWithPersisted(previous, failure)
            return failed(failure)
        }

        val locale = localeForSelection(normalized)
        return try {
            setJvmDefault(locale)
            mutableActiveLanguageTag.value = normalized
            applied(normalized, locale)
        } catch (failure: Throwable) {
            restorePreference(preferenceWasSet, previousStored, failure)
            reconcileWithPersisted(previous, failure)
            failed(failure)
        }
    }

    fun availableLanguages(displayLocale: Locale = Locale.getDefault()): List<DesktopLanguageOption> {
        return fixedMainLanguageTags
            .map { languageTag ->
                val nameLocale = fixedMainNameLocale(languageTag)
                DesktopLanguageOption(
                    languageTag = languageTag,
                    displayName = displayName(nameLocale, nameLocale),
                    localizedDisplayName = displayName(nameLocale, displayLocale),
                )
            }
            .sortedBy(DesktopLanguageOption::displayName)
    }

    fun consumeFeedback(id: Long) {
        if (mutablePendingFeedback.value?.id == id) mutablePendingFeedback.value = null
    }

    private fun applyWithoutWrite(languageTag: String, locale: Locale): DesktopLocaleApplyResult {
        return try {
            setJvmDefault(locale)
            mutableActiveLanguageTag.value = languageTag
            DesktopLocaleApplyResult.Applied(languageTag, locale)
        } catch (failure: Throwable) {
            activeTagForLocale(Locale.getDefault())?.let { mutableActiveLanguageTag.value = it }
            DesktopLocaleApplyResult.Failed(mutableActiveLanguageTag.value, failure)
        }
    }

    private fun restorePreference(wasSet: Boolean, stored: String, primary: Throwable) {
        runCatching {
            if (wasSet) preference.set(stored) else preference.delete()
        }.exceptionOrNull()?.let(primary::addSuppressed)
    }

    private fun reconcileWithPersisted(previous: String, primary: Throwable) {
        val persisted = runCatching { preference.get() }.getOrElse {
            primary.addSuppressed(it)
            previous
        }
        val persistedTag = if (persisted.isEmpty()) "" else normalizeSupported(persisted)
        if (persistedTag == null) {
            val repairFailure = runCatching { preference.set("") }.exceptionOrNull()
            if (repairFailure != null) primary.addSuppressed(repairFailure)
            coordinateRuntime("", primary)
        } else {
            coordinateRuntime(persistedTag, primary)
        }
    }

    private fun coordinateRuntime(languageTag: String, primary: Throwable) {
        try {
            setJvmDefault(localeForSelection(languageTag))
            mutableActiveLanguageTag.value = languageTag
        } catch (failure: Throwable) {
            primary.addSuppressed(failure)
            activeTagForLocale(Locale.getDefault())?.let { mutableActiveLanguageTag.value = it }
        }
    }

    private fun localeForSelection(languageTag: String): Locale =
        if (languageTag.isEmpty()) systemLocale else localeForTag(languageTag)

    private fun activeTagForLocale(locale: Locale): String? {
        if (locale == systemLocale) return ""
        return normalizeSupported(locale.toLanguageTag())
    }

    private fun applied(languageTag: String, locale: Locale): DesktopLocaleApplyResult.Applied {
        return DesktopLocaleApplyResult.Applied(languageTag, locale).also(::publishFeedback)
    }

    private fun failed(cause: Throwable): DesktopLocaleApplyResult.Failed {
        return DesktopLocaleApplyResult.Failed(mutableActiveLanguageTag.value, cause).also(::publishFeedback)
    }

    private fun publishFeedback(result: DesktopLocaleApplyResult) {
        mutablePendingFeedback.value = DesktopLocaleFeedback(feedbackIds.incrementAndGet(), result)
    }

    private companion object {
        val fixedMainLanguageTags = listOf(
            "am", "ar", "as", "be", "bg", "bn", "ca", "ceb", "cs", "cv", "da", "de", "el", "en",
            "eo", "es", "eu", "fa", "fi", "fil", "fr", "gl", "he", "hi", "hr", "hu", "in", "it",
            "ja", "jv", "ka-GE", "kk", "km", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "my",
            "nb-NO", "ne", "nl", "nn", "pl", "pt", "pt-BR", "ro", "ru", "sa", "sah", "sc", "sdh",
            "sk", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "uz", "vi", "zh-CN", "zh-TW",
        )

        val authorityTagLookup = fixedMainLanguageTags.associateBy { it.lowercase(Locale.ROOT) }
        val canonicalAuthorityLookup = fixedMainLanguageTags.associateBy {
            requireNotNull(canonicalLanguageTag(it)).lowercase(Locale.ROOT)
        }

        fun normalizeSupported(languageTag: String): String? {
            authorityTagLookup[languageTag.lowercase(Locale.ROOT)]?.let { return it }
            val canonical = canonicalLanguageTag(languageTag) ?: return null
            return canonicalAuthorityLookup[canonical.lowercase(Locale.ROOT)]
        }

        fun canonicalLanguageTag(languageTag: String): String? {
            if (languageTag.isBlank() || '_' in languageTag) return null
            val locale = try {
                Locale.Builder().setLanguageTag(languageTag).build()
            } catch (_: IllformedLocaleException) {
                return null
            }
            val canonical = locale.toLanguageTag()
            return canonical.takeUnless { it == "und" || locale.language.isBlank() }
        }

        fun localeForTag(languageTag: String): Locale = Locale.forLanguageTag(languageTag)

        fun fixedMainNameLocale(languageTag: String): Locale = when (languageTag) {
            "zh-CN" -> Locale.forLanguageTag("zh-Hans")
            "zh-TW" -> Locale.forLanguageTag("zh-Hant")
            else -> localeForTag(languageTag)
        }

        fun displayName(locale: Locale, displayLocale: Locale): String =
            locale.getDisplayName(displayLocale).replaceFirstChar { it.uppercase(locale) }
    }
}

private object DesktopSystemLocale {
    val value: Locale = Locale.getDefault()
}
