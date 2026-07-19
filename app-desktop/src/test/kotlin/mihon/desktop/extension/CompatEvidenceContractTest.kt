package mihon.desktop.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

class CompatEvidenceContractTest {
    @Test
    fun `inventory covers the complete public compat adapter surface`() {
        val root = repositoryRoot()
        val surface = scanSurface(root)
        val inventory = inventory(root)
        val symbols = inventory.entries.map(Entry::symbol)

        assertEquals(36, surface.files.size, "compat source file count drifted")
        assertEquals(46, surface.symbols.size, "compat public symbol count drifted")
        assertEquals(AUTHORITY, inventory.authorityRef)
        assertEquals(ADAPTER_ROOTS, inventory.adapterRoots)
        assertEquals(symbols.size, symbols.toSet().size, "inventory symbols must be unique")
        assertEquals(
            mapOf("required" to 44, "unsupported" to 1, "unverified" to 1),
            inventory.entries.groupingBy(Entry::status).eachCount(),
            "compat status counts drifted",
        )
        assertEquals(
            setOf("android.graphics.Color"),
            inventory.entries.filter { it.status == "unverified" }.map(Entry::symbol).toSet(),
            "the unverified set must remain exact",
        )
        assertEquals(
            surface.symbols,
            symbols.toSet(),
            "compat inventory missing ${surface.files.size} files/${surface.symbols.size} symbols: " +
                (surface.symbols - symbols.toSet()).sorted().joinToString(),
        )
        inventory.entries.forEach {
            assertTrue(it.status in STATUSES, "${it.symbol}: invalid status ${it.status}")
            assertTrue(it.nextEvidence.isNotBlank(), "${it.symbol}: nextEvidence must not be blank")
        }
        validateResolved(root, inventory.entries, evidence(root))
    }

    @Test
    fun `resolved status rejects synthetic and missing evidence`() {
        val root = repositoryRoot()
        val resolved = Entry("android.fake.Api", "required", "replace with real evidence")
        assertThrows(IllegalArgumentException::class.java) { validateResolved(root, listOf(resolved), emptyList()) }
        listOf(
            Evidence(resolved.symbol, "https://repo.example/fake.apk", "production.kt"),
            Evidence(resolved.symbol, "parent-classpath@1", "production.kt"),
            Evidence(resolved.symbol, "fixture.apk@1", "AndroidCompatTest.kt"),
            Evidence(resolved.symbol, "fixture.jar@1", "MinimalTestSource.kt"),
        ).forEach {
            assertFalse(isRealEvidence(root, it), "${it.fixture}/${it.test} must not resolve compat evidence")
            assertThrows(IllegalArgumentException::class.java) { validateResolved(root, listOf(resolved), listOf(it)) }
        }
    }

    @Test
    fun `ComicFury text evidence resolves only the seven executed compat types`() {
        val root = repositoryRoot()
        val entries = inventory(root).entries.associateBy(Entry::symbol)
        val evidence = evidence(root)

        assertEquals(
            COMIC_FURY_TEXT_STATUSES.keys,
            entries.keys.intersect(COMIC_FURY_TEXT_STATUSES.keys),
            "all seven ComicFury text compat types must be inventoried",
        )
        COMIC_FURY_TEXT_STATUSES.forEach { (symbol, status) ->
            assertEquals(status, entries.getValue(symbol).status, "$symbol must reflect the executed text path")
            val matching = evidence.filter { it.symbol == symbol && it.test == COMIC_FURY_TEST }
            assertEquals(1, matching.size, "$symbol must have exactly one evidence item")
            assertEquals(COMIC_FURY_FIXTURE, matching.single().fixture, "$symbol must bind to tracked ComicFury")
            assertEquals(COMIC_FURY_TEST, matching.single().test, "$symbol must bind to the real ComicFury test")
            val boundary = COMIC_FURY_TEXT_BOUNDARIES.getValue(symbol)
            assertTrue(matching.single().removalCondition.contains(boundary), "$symbol must record `$boundary`")
        }
        assertEquals(7, COMIC_FURY_TEXT_STATUSES.size, "the ComicFury text reverse evidence set must stay exact")
        assertEquals(
            COMIC_FURY_TEXT_STATUSES.keys,
            evidence.filter { it.test == COMIC_FURY_TEST }.map(Evidence::symbol).toSet(),
            "the ComicFury test must not resolve compat APIs outside its executed path",
        )
        assertEquals(
            setOf(COMIX_WEBVIEW_TEST, COMIC_FURY_TEST),
            evidence.filter { it.symbol == "android.net.Uri" }.map(Evidence::test).toSet(),
            "Uri must retain the Comix token and ComicFury encode evidence separately",
        )
        val htmlBoundary = evidence.single { it.symbol == "android.text.Html" }.removalCondition
        assertTrue(htmlBoundary.contains("one-argument descriptor is protected by AndroidCompatPhase2Test"))
        assertTrue(htmlBoundary.contains("not executed by this Desktop SDK 28 path"))
        val uriBoundary = evidence
            .single { it.symbol == "android.net.Uri" && it.test == COMIC_FURY_TEST }
            .removalCondition
        assertTrue(
            uriBoundary.contains("does not evidence parsing, decoding, Builder/construction, or general Uri behavior"),
        )
    }

    @Test
    fun `Comix WebView evidence resolves only the compat surface linked or executed before fail fast`() {
        val root = repositoryRoot()
        val entries = inventory(root).entries.associateBy(Entry::symbol)
        val evidence = evidence(root)

        COMIX_WEBVIEW_STATUSES.forEach { (symbol, status) ->
            assertEquals(status, entries.getValue(symbol).status, "$symbol must reflect the real Comix verifier path")
            val matching = evidence.filter { it.symbol == symbol && it.test == COMIX_WEBVIEW_TEST }
            assertEquals(1, matching.size, "$symbol must have exactly one evidence item")
            assertEquals(COMIX_WEBVIEW_FIXTURE, matching.single().fixture, "$symbol must bind to tracked Comix")
            assertEquals(COMIX_WEBVIEW_TEST, matching.single().test, "$symbol must bind to the real Comix WebView test")
            COMIX_VERIFIER_BOUNDARIES[symbol]?.let { boundary ->
                assertTrue(matching.single().removalCondition.contains(boundary), "$symbol must record `$boundary`")
            }
        }
        assertEquals(9, COMIX_WEBVIEW_STATUSES.size, "the Comix WebView reverse evidence set must stay exact")
        assertEquals(
            COMIX_WEBVIEW_STATUSES.keys,
            evidence.filter { it.test == COMIX_WEBVIEW_TEST }.map(Evidence::symbol).toSet(),
            "the Comix WebView test must not resolve compat APIs it does not execute",
        )
    }

    @Test
    fun `MangaDex Build evidence resolves only the release header ABI`() {
        val root = repositoryRoot()
        val entries = inventory(root).entries.associateBy(Entry::symbol)
        val evidence = evidence(root)

        MANGADEX_BUILD_STATUSES.forEach { (symbol, status) ->
            assertEquals(
                status,
                entries.getValue(symbol).status,
                "$symbol must reflect the real MangaDex header invocation",
            )
            val matching = evidence.filter { it.symbol == symbol }
            assertEquals(1, matching.size, "$symbol must have exactly one evidence item")
            assertEquals(MANGADEX_BUILD_TEST, matching.single().test, "$symbol must bind to the real MangaDex test")
            assertTrue(matching.single().removalCondition.contains("Build.VERSION.RELEASE"))
            assertTrue(matching.single().removalCondition.contains("does not evidence SDK_INT"))
        }
        assertEquals(
            MANGADEX_BUILD_STATUSES.keys,
            evidence.filter { it.test == MANGADEX_BUILD_TEST }.map(Evidence::symbol).toSet(),
            "the MangaDex Build test must not resolve compat APIs it does not execute",
        )
    }

    @Test
    fun `MangaDex validator evidence distinguishes executed text semantics from the Button token`() {
        val root = repositoryRoot()
        val entries = inventory(root).entries.associateBy(Entry::symbol)
        val evidence = evidence(root)

        MANGADEX_VALIDATOR_STATUSES.forEach { (symbol, status) ->
            assertEquals(status, entries.getValue(symbol).status, "$symbol must reflect the real validator path")
            val matching = evidence.filter { it.symbol == symbol }
            assertEquals(1, matching.size, "$symbol must have exactly one evidence item")
            assertEquals(MANGADEX_VALIDATOR_FIXTURE, matching.single().fixture, "$symbol must bind to tracked MangaDex")
            assertEquals(MANGADEX_VALIDATOR_TEST, matching.single().test, "$symbol must bind to the real validator test")
            val boundary = MANGADEX_VALIDATOR_BOUNDARIES.getValue(symbol)
            assertTrue(matching.single().removalCondition.contains(boundary), "$symbol must record `$boundary`")
        }
        assertEquals(6, MANGADEX_VALIDATOR_STATUSES.size, "the MangaDex validator reverse evidence set must stay exact")
        assertEquals(
            MANGADEX_VALIDATOR_STATUSES.keys,
            evidence.filter { it.test == MANGADEX_VALIDATOR_TEST }.map(Evidence::symbol).toSet(),
            "the MangaDex validator test must not resolve compat APIs outside its executed/token boundary",
        )
    }

    private fun scanSurface(root: Path): Surface {
        val files = ADAPTER_ROOTS.flatMap { adapter ->
            Files.walk(root.resolve(adapter)).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        }
        val symbols = files.flatMap { file ->
            val packageName = file.readLines().first { it.startsWith("package ") }.removePrefix("package ")
            file.readLines().mapNotNull { line ->
                if (
                    line != line.trimStart() || line.startsWith("private ") || line.startsWith("internal ")
                ) return@mapNotNull null
                PUBLIC_TYPE.matchEntire(line.substringBefore('{').trim())
                    ?.groupValues?.get(1)?.let { "$packageName.$it" }
            }
        }
        return Surface(files.map { root.relativize(it).toString().replace('\\', '/') }.toSet(), symbols.toSet())
    }

    private fun inventory(root: Path): Inventory {
        val json = objectResource(root, "compat-inventory.json")
        return Inventory(
            json.getValue("authorityRef").jsonPrimitive.content,
            json.getValue("adapterRoots").jsonArray.map { it.jsonPrimitive.content },
            json.getValue("entries").jsonArray.map {
                val item = it.jsonObject
                assertEquals(ENTRY_FIELDS, item.keys, "inventory entry schema drifted")
                Entry(
                    item.getValue("symbol").jsonPrimitive.content,
                    item.getValue("status").jsonPrimitive.content,
                    item.getValue("nextEvidence").jsonPrimitive.content,
                )
            },
        )
    }

    private fun evidence(root: Path) = arrayResource(root, "compat-evidence.json").map {
        val item = it.jsonObject
        Evidence(
            item.getValue("symbol").jsonPrimitive.content,
            item.getValue("fixture").jsonPrimitive.content,
            item.getValue("test").jsonPrimitive.content,
            item.getValue("status").jsonPrimitive.content,
            item.getValue("removalCondition").jsonPrimitive.content,
        )
    }

    private fun validateResolved(root: Path, entries: List<Entry>, evidence: List<Evidence>) {
        entries.filter { it.status != "unverified" }.forEach { entry ->
            val matching = evidence.filter { it.symbol == entry.symbol }
            val expectedCount = if (entry.symbol == "android.net.Uri") 2 else 1
            require(matching.size == expectedCount) {
                "${entry.symbol}: resolved status requires exactly $expectedCount evidence item(s)"
            }
            matching.forEach {
                require(it.status == entry.status && it.removalCondition.isNotBlank()) {
                    "${entry.symbol}: evidence status/provenance mismatch"
                }
                require(isRealEvidence(root, it)) {
                    "${entry.symbol}: evidence lacks real local artifact/test provenance"
                }
            }
        }
    }

    private fun isRealEvidence(root: Path, evidence: Evidence): Boolean {
        if (BANNED.any { evidence.fixture.contains(it, true) || evidence.test.contains(it, true) }) return false
        val artifact = root.resolve(evidence.fixture.substringBeforeLast('@')).normalize()
        val test = root.resolve(evidence.test).normalize()
        return artifact.startsWith(root) && test.startsWith(root) && artifact.isRegularFile() && test.isRegularFile()
    }

    private fun objectResource(root: Path, name: String) =
        Json.parseToJsonElement(Files.readString(resource(root, name))).jsonObject
    private fun arrayResource(root: Path, name: String) =
        Json.parseToJsonElement(Files.readString(resource(root, name))).jsonArray
    private fun resource(root: Path, name: String) = root.resolve("app-desktop/src/test/resources/extensions/$name")
    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private data class Surface(val files: Set<String>, val symbols: Set<String>)
    private data class Inventory(val authorityRef: String, val adapterRoots: List<String>, val entries: List<Entry>)
    private data class Entry(val symbol: String, val status: String, val nextEvidence: String)
    private data class Evidence(
        val symbol: String,
        val fixture: String,
        val test: String,
        val status: String = "required",
        val removalCondition: String = "replace with recorder evidence",
    )

    private companion object {
        const val AUTHORITY = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        val ADAPTER_ROOTS = listOf("app-desktop/src/main/kotlin/android", "app-desktop/src/main/kotlin/androidx")
        val STATUSES = setOf("unverified", "required", "unsupported")
        val ENTRY_FIELDS = setOf("symbol", "status", "nextEvidence")
        val BANNED = listOf("http://", "https://", "parent-classpath", "AndroidCompat", "MinimalTestSource")
        const val COMIC_FURY_TEST =
            "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionComicFuryTextCompatTest.kt"
        const val COMIC_FURY_FIXTURE =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comicfury-1.4.8.apk@" +
                "sha256:9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7"
        val COMIC_FURY_TEXT_STATUSES = setOf(
            "android.graphics.Typeface",
            "android.net.Uri",
            "android.text.Html",
            "android.text.Layout",
            "android.text.Spanned",
            "android.text.StaticLayout",
            "android.text.TextPaint",
        ).associateWith { "required" }
        val COMIC_FURY_TEXT_BOUNDARIES = mapOf(
            "android.graphics.Typeface" to "DEFAULT_BOLD for the author title and DEFAULT for body text",
            "android.net.Uri" to "exact static Uri.encode(String) with Android UTF-8 percent encoding, including emoji",
            "android.text.Html" to "fromHtml(String, int flags=0): Spanned in the real author-note text chain",
            "android.text.Layout" to "Alignment.ALIGN_NORMAL token passed to StaticLayout",
            "android.text.Spanned" to "Html.fromHtml return descriptor and runtime instance",
            "android.text.StaticLayout" to "fixed constructor, getHeight, and draw through the Desktop Skia adapter",
            "android.text.TextPaint" to "no-arg construction and color/textSize/typeface/antiAlias setters",
        )
        const val COMIX_WEBVIEW_TEST =
            "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionWebViewUnsupportedCompatTest.kt"
        const val COMIX_WEBVIEW_FIXTURE =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comix-1.4.34.apk@" +
                "sha256:5d46a6ef98c1ac4f2ab22a29347748a36eb32b6995fb8a08e092446424e366d8"
        val COMIX_WEBVIEW_STATUSES = mapOf(
            "android.os.Handler" to "required",
            "android.os.Looper" to "required",
            "android.net.Uri" to "required",
            "android.view.ViewGroup" to "required",
            "android.webkit.WebResourceRequest" to "required",
            "android.webkit.WebResourceResponse" to "required",
            "android.webkit.WebSettings" to "required",
            "android.webkit.WebView" to "unsupported",
            "android.webkit.WebViewClient" to "required",
        )
        val COMIX_VERIFIER_BOUNDARIES = mapOf(
            "android.net.Uri" to "does not evidence Uri parsing, construction, or behavior",
            "android.view.ViewGroup" to "layout operations remain unsupported and the extension swallows that failure",
            "android.webkit.WebResourceRequest" to "does not evidence callback execution or a request engine",
            "android.webkit.WebSettings" to
                "does not evidence WebSettings instantiation, setters, or a WebView engine",
            "android.webkit.WebViewClient" to "does not evidence callback execution or a WebView engine",
        )
        const val MANGADEX_BUILD_TEST =
            "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionBuildCompatTest.kt"
        val MANGADEX_BUILD_STATUSES = mapOf("android.os.Build" to "required")
        const val MANGADEX_VALIDATOR_TEST =
            "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionMangaDexFactoryCompatTest.kt"
        const val MANGADEX_VALIDATOR_FIXTURE =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.apk@" +
                "sha256:eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236"
        val MANGADEX_VALIDATOR_STATUSES = mapOf(
            "android.text.Editable" to "required",
            "android.text.TextWatcher" to "required",
            "android.view.View" to "required",
            "android.widget.Button" to "required",
            "android.widget.EditText" to "required",
            "android.widget.TextView" to "required",
        )
        val MANGADEX_VALIDATOR_BOUNDARIES = mapOf(
            "android.text.Editable" to "executes an Editable value",
            "android.text.TextWatcher" to "executes beforeTextChanged, onTextChanged, and afterTextChanged",
            "android.view.View" to "executes getRootView and findViewById",
            "android.widget.Button" to "does not evidence Button construction, setEnabled, or rendering",
            "android.widget.EditText" to "constructs a fresh EditText for each validation",
            "android.widget.TextView" to "executes watcher storage and error getter/setter",
        )
        val PUBLIC_TYPE = Regex(
            "(?:(?:public|data|enum|sealed|open|abstract|value|fun)\\s+)*" +
                "(?:class|interface|object)\\s+([A-Za-z_]\\w*).*?",
        )
    }
}
