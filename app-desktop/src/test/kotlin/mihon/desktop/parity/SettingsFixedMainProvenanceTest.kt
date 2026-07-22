package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SettingsFixedMainProvenanceTest {
    private val fixedRef = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
    private val root = repositoryRoot()
    private val fixturePath = root.resolve("app-desktop/src/test/resources/parity/fixed-main-settings-fixtures.json")
    private val inventoryPath = root.resolve("app-desktop/src/test/resources/parity/fixed-main-path-inventory.json")
    private val expectedPaths = mapOf(
        90 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt", "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt"),
        91 to setOf("core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt", "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt", "app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt", "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt", "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt", "app/src/main/java/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt"),
        94 to setOf("app/build.gradle.kts", "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt"),
        88 to setOf("presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt", "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt", "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt"),
    )
    private val expectedBlobs = mapOf(
        "core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt" to "2016f3d442c73947e75463c7149c97373f9364fd",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt" to "b5a9ac937af81a5eb1feb13ca7963bec64cc72bc",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt" to "5652ace76c752bce59d4ee82f436c63f8d436d58",
        "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt" to "e0938738fb6191234a283c7ffb82d52ffccad26d",
        "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt" to "84e405dee827b4d9fd47f2ca6cba7938c31b5cb4",
        "app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt" to "2394c5a429031312d4192989dc5bc3920133acc7",
        "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt" to "5e3f76efe6e106e108eb7bf1598d78a52a41c07b",
        "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt" to "71ee3d988c388d35676de91607dd43225e4aeffe",
        "app/src/main/java/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt" to "4ad2bfb807563c6d10c46b51bc02fa72d2fe4005",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt" to "b59b26acaccce2612ce34904c704a4930ec99dc3",
        "app/build.gradle.kts" to "cdaa6f9604cd9bdada2c1e0359aba2e60ac156a1",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt" to "01e35c1ecccc8b86d41d24137fd6ec0b94dfb063",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt" to "3385f7430b005c021f9c8469964755557a7da7a4",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt" to "725ed640788b217d6d71a731d798d7b5f1c25f01",
        "presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt" to "857674a5c94065a2065f2140418fe9463f8d7a12",
        "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt" to "a66bf0d184acbac6f80bfc4fcce0a33a60f62aac",
        "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt" to "0e857ef75c4c420f69af3c9b21ea0c61bcb56aa1",
    )
    private val expectedSymbols = mapOf(
        "core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt" to setOf("inline fun <reified T : Enum<T>> PreferenceStore.getEnum("),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt" to setOf("private val settingScreens = listOf(", ".take(10)", "SearchableSettings.highlightKey = result.highlightKey", "navigator.replace(result.route)"),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt" to setOf("interface SearchableSettings", "var highlightKey: String? = null"),
        "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt" to setOf("findHighlightedIndex", "delay(0.5.seconds)", "state.animateScrollToItem(i)"),
        "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt" to setOf("pref_theme_mode_key", "pref_app_theme", "pref_theme_dark_amoled_key"),
        "app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt" to setOf("enum class AppTheme", "DARK_BLUE(null)", "HOT_PINK(null)", "BLUE(null)"),
        "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt" to setOf(".filterNot { it.titleRes == null || (it == AppTheme.MONET && !DeviceUtil.isDynamicColorAvailable) }"),
        "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt" to setOf("getThemeColorScheme", "colorSchemes.getOrDefault"),
        "app/src/main/java/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt" to setOf("if (!isDark) return lightScheme", "background = Color.Black"),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt" to setOf("LocaleListCompat.getEmptyLocaleList()", "AppCompatDelegate.setApplicationLocales(locale)"),
        "app/build.gradle.kts" to setOf("alias(libs.plugins.aboutLibraries)", "implementation(libs.aboutLibraries.compose)"),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt" to setOf("navigator.push(OpenSourceLicensesScreen())", "fun getVersionName(withBuildDate: Boolean)"),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt" to setOf("produceLibraries(R.raw.aboutlibraries)", "it.licenses.firstOrNull()"),
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt" to setOf("if (!website.isNullOrEmpty())", "HtmlCompat.FROM_HTML_MODE_COMPACT"),
        "presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt" to setOf("fun Modifier.runOnEnterKeyPressed", "Key.Enter, Key.NumPadEnter", "KeyEventType.KeyDown"),
        "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt" to setOf("role = Role.Checkbox", ".heightIn(min = 48.dp)"),
        "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt" to setOf(".minimumInteractiveComponentSize()", "role = Role.Button"),
    )

    @Test
    fun `settings fixtures bind exact fixed main blobs symbols and behavior`() {
        validate(Files.readString(fixturePath))
    }

    @Test
    fun `authority mutations reject wrong ref blob shim cross id and invented accessibility screen`() {
        val fixture = Files.readString(fixturePath)
        listOf(
            fixture.replace(fixedRef, "main@${"0".repeat(40)}"),
            fixture.replaceFirst("b5a9ac937af81a5eb1feb13ca7963bec64cc72bc", "0".repeat(40)),
            fixture.replaceFirst(expectedPaths.getValue(90).first(), "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAppearanceScreen.kt"),
            fixture.replaceFirst(expectedPaths.getValue(90).first(), "app-desktop/src/main/kotlin/android/content/Context.kt"),
            fixture.replaceFirst(expectedPaths.getValue(90).first(), expectedPaths.getValue(91).first()),
            fixture.replaceFirst("\"dedicatedScreenPresent\": false,", "\"dedicatedScreenPresent\": false, \"dedicatedScreenPaths\": [\"app/src/main/java/eu/kanade/presentation/more/settings/screen/AccessibilityScreen.kt\"],"),
        ).forEach { mutation -> assertThrows(AssertionError::class.java) { validate(mutation) } }
    }

    @Test
    fun `contract mutations reject order filtering defaults and license selection drift`() {
        val fixture = Files.readString(fixturePath)
        listOf(
            fixture.replaceFirst("\"appearance\", \"library\"", "\"library\", \"appearance\""),
            fixture.replaceFirst("\"disabled\": true", "\"disabled\": false"),
            fixture.replaceFirst("\"dynamicAvailable\": \"MONET\"", "\"dynamicAvailable\": \"DEFAULT\""),
            fixture.replaceFirst("\"firstLicenseOnly\": true", "\"firstLicenseOnly\": false"),
            fixture.replaceFirst("\"matchFields\":", "\"keywords\": [], \"matchFields\":"),
            fixture.replaceFirst("\"limit\": 10", "\"ranking\": \"score\", \"limit\": 10"),
            fixture.replaceFirst("\"identity\": \"localized-title\"", "\"stableId\": \"appearance.theme\", \"identity\": \"localized-title\""),
        ).forEach { mutation -> assertThrows(AssertionError::class.java) { validate(mutation) } }
    }

    @ParameterizedTest
    @ValueSource(strings = ["blankTitle", "infoPreference", "disabledGroup"])
    fun `search filter key removal is rejected`(key: String) {
        val mutation = Files.readString(fixturePath).replaceFirst(", \"$key\": true", "")
        assertThrows(AssertionError::class.java) { validate(mutation) }
    }

    private fun validate(text: String) {
        val fixture = Json.parseToJsonElement(text).jsonObject
        fixture.assertKeys("upstreamRef", "capabilities")
        assertEquals(fixedRef, fixture.text("upstreamRef"))
        val inventory = inventory()
        val capabilities = fixture["capabilities"]!!.jsonArray.associate { it.jsonObject.int("id") to it.jsonObject }
        assertEquals(expectedPaths.keys, capabilities.keys)
        capabilities.forEach { (id, capability) ->
            capability.assertKeys(*(if (id == 88) arrayOf("id", "dedicatedScreenPresent", "authorities", "behavior") else arrayOf("id", "authorities", "behavior")))
            val authorities = capability["authorities"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expectedPaths.getValue(id), authorities.map { it.text("path") }.toSet())
            authorities.forEach { authority ->
                authority.assertKeys("path", "blobId", "symbols")
                val path = authority.text("path")
                val blob = authority.text("blobId")
                assertEquals(expectedBlobs[path], blob, "Fixture has the wrong fixed-main blob for $path")
                assertEquals(blob, inventory[path], "Inventory missing or wrong for $path")
                assertEquals(expectedSymbols[path], authority["symbols"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), "Fixture has wrong symbols for $path")
            }
        }
        assertSearch(capabilities.getValue(90)["behavior"]!!.jsonObject)
        assertTheme(capabilities.getValue(91)["behavior"]!!.jsonObject)
        assertLicense(capabilities.getValue(94)["behavior"]!!.jsonObject)
        assertAccessibility(capabilities.getValue(88))
    }

    private fun assertSearch(value: JsonObject) {
        value.assertKeys("screenOrder", "matchFields", "ignoreCase", "excluded", "limit", "breadcrumbs", "anchor")
        assertEquals(listOf("appearance", "library", "reader", "download", "tracking", "browse", "data", "security", "advanced"), value.strings("screenOrder"))
        assertEquals(listOf("title", "subtitle"), value.strings("matchFields"))
        assertTrue(value["ignoreCase"]!!.jsonPrimitive.boolean)
        value["excluded"]!!.jsonObject.also { it.assertKeys("disabled", "blankTitle", "infoPreference", "disabledGroup") }.values.forEach { assertTrue(it.jsonPrimitive.boolean) }
        assertEquals(10, value["limit"]!!.jsonPrimitive.int)
        assertEquals(listOf("screen > group", "group < screen"), value["breadcrumbs"]!!.jsonObject.also { it.assertKeys("ltr", "rtl") }.let { listOf(it.text("ltr"), it.text("rtl")) })
        assertEquals(listOf("localized-title", "replace", "true"), value["anchor"]!!.jsonObject.also { it.assertKeys("identity", "routeAction", "consumeOnce") }.let { listOf(it.text("identity"), it.text("routeAction"), it["consumeOnce"]!!.jsonPrimitive.content) })
    }

    private fun assertTheme(value: JsonObject) {
        value.assertKeys("preferences", "unknownTheme", "deprecatedTheme", "amoled", "languageDefault")
        val preferences = value["preferences"]!!.jsonObject.also { it.assertKeys("pref_theme_mode_key", "pref_app_theme", "pref_theme_dark_amoled_key") }
        assertEquals("SYSTEM", preferences.text("pref_theme_mode_key"))
        assertEquals(listOf("MONET", "DEFAULT"), preferences["pref_app_theme"]!!.jsonObject.also { it.assertKeys("dynamicAvailable", "dynamicUnavailable") }.let { listOf(it.text("dynamicAvailable"), it.text("dynamicUnavailable")) })
        assertFalse(preferences["pref_theme_dark_amoled_key"]!!.jsonPrimitive.boolean)
        assertEquals("capability-default", value.text("unknownTheme"))
        assertEquals(listOf("false", "DEFAULT"), value["deprecatedTheme"]!!.jsonObject.also { it.assertKeys("pickerVisible", "renderedPalette") }.let { listOf(it["pickerVisible"]!!.jsonPrimitive.content, it.text("renderedPalette")) })
        assertEquals(listOf("false", "BLACK", "BLACK"), value["amoled"]!!.jsonObject.also { it.assertKeys("lightApplies", "darkBackground", "darkSurface") }.let { listOf(it["lightApplies"]!!.jsonPrimitive.content, it.text("darkBackground"), it.text("darkSurface")) })
        assertEquals("system", value.text("languageDefault"))
    }

    private fun assertLicense(value: JsonObject) {
        value.assertKeys("metadata", "firstLicenseOnly", "emptyWebsiteHasAction", "emptyLicense", "versionRules")
        assertEquals("generated-R.raw.aboutlibraries", value.text("metadata"))
        assertTrue(value["firstLicenseOnly"]!!.jsonPrimitive.boolean)
        assertFalse(value["emptyWebsiteHasAction"]!!.jsonPrimitive.boolean)
        assertEquals("", value.text("emptyLicense"))
        assertEquals(listOf("Debug <sha> (<date>)", "Beta r<count> (<sha>, <date>)", "Stable <version> (<date>)"), value["versionRules"]!!.jsonObject.also { it.assertKeys("debug", "preview", "stable") }.let { listOf(it.text("debug"), it.text("preview"), it.text("stable")) })
    }

    private fun assertAccessibility(value: JsonObject) {
        assertFalse(value["dedicatedScreenPresent"]!!.jsonPrimitive.boolean)
        val behavior = value["behavior"]!!.jsonObject.also { it.assertKeys("enterKeys", "triggerEvent", "invocationsPerPress", "roles", "minimumTouchDp") }
        assertEquals(listOf("Enter", "NumPadEnter"), behavior.strings("enterKeys"))
        assertEquals(listOf("KeyDown", "1", "Checkbox", "Button", "48"), listOf(behavior.text("triggerEvent"), behavior["invocationsPerPress"]!!.jsonPrimitive.content, *behavior.strings("roles").toTypedArray(), behavior["minimumTouchDp"]!!.jsonPrimitive.content))
    }

    private fun inventory() = Json.parseToJsonElement(Files.readString(inventoryPath)).jsonObject.also { assertEquals(fixedRef, it.text("upstreamRef")) }["paths"]!!.jsonArray.associate { it.jsonObject.let { entry -> entry.text("path") to entry.text("blobId") } }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.assertKeys(vararg expected: String) = assertEquals(expected.toSet(), keys)
    private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }
}
