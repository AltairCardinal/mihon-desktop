package mihon.desktop.license

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedDependencyNoticesResourceTest {

    @Test
    fun `packaged metadata contains real resolved dependencies in stable order`() {
        val stream = javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)
        assertNotNull(stream, "missing packaged resource: $RESOURCE_PATH")
        val root = requireNotNull(stream).bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val libraries = requireNotNull(root["libraries"]).jsonArray
        val uniqueIds = libraries.map { library ->
            requireNotNull(library.jsonObject["uniqueId"]).jsonPrimitive.content
        }

        assertTrue(uniqueIds.isNotEmpty())
        assertEquals(uniqueIds.sorted(), uniqueIds)
        assertTrue(
            uniqueIds.any { "kotlinx-coroutines-core" in it },
            "real Desktop dependency missing: $uniqueIds",
        )
    }

    private companion object {
        const val RESOURCE_PATH = "META-INF/mihon/dependencies.json"
    }
}
