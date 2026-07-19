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

        assertEquals(34, surface.files.size, "compat source file count drifted")
        assertEquals(43, surface.symbols.size, "compat public symbol count drifted")
        assertEquals(AUTHORITY, inventory.authorityRef)
        assertEquals(ADAPTER_ROOTS, inventory.adapterRoots)
        assertEquals(symbols.size, symbols.toSet().size, "inventory symbols must be unique")
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
    fun `Comix WebView evidence resolves only the compat surface executed before fail fast`() {
        val root = repositoryRoot()
        val entries = inventory(root).entries.associateBy(Entry::symbol)
        val evidence = evidence(root)

        COMIX_WEBVIEW_STATUSES.forEach { (symbol, status) ->
            assertEquals(status, entries.getValue(symbol).status, "$symbol must reflect the real Comix invocation")
            val matching = evidence.filter { it.symbol == symbol }
            assertEquals(1, matching.size, "$symbol must have exactly one evidence item")
            assertEquals(COMIX_WEBVIEW_TEST, matching.single().test, "$symbol must bind to the real Comix WebView test")
        }
        assertEquals(
            COMIX_WEBVIEW_STATUSES.keys,
            evidence.filter { it.test == COMIX_WEBVIEW_TEST }.map(Evidence::symbol).toSet(),
            "the Comix WebView test must not resolve compat APIs it does not execute",
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
            require(matching.size == 1) { "${entry.symbol}: resolved status requires exactly one evidence item" }
            require(matching.single().status == entry.status && matching.single().removalCondition.isNotBlank()) {
                "${entry.symbol}: evidence status/provenance mismatch"
            }
            require(isRealEvidence(root, matching.single())) {
                "${entry.symbol}: evidence lacks real local artifact/test provenance"
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
        const val COMIX_WEBVIEW_TEST =
            "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionWebViewUnsupportedCompatTest.kt"
        val COMIX_WEBVIEW_STATUSES = mapOf(
            "android.os.Handler" to "required",
            "android.os.Looper" to "required",
            "android.webkit.WebResourceResponse" to "required",
            "android.webkit.WebView" to "unsupported",
        )
        val PUBLIC_TYPE = Regex(
            "(?:(?:public|data|enum|sealed|open|abstract|value|fun)\\s+)*" +
                "(?:class|interface|object)\\s+([A-Za-z_]\\w*).*?",
        )
    }
}
