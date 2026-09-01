package mihon.desktop.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.CopyOption
import java.nio.file.StandardCopyOption

class PartialDownloadIndexSupportTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `one hundred eighty page publications retain one mutable ordinal index`() {
        val index = MutableCommittedPageIndex<String>()
        val storage = index.storageIdentity

        repeat(180) { ordinal ->
            index.put(ordinal, "page-$ordinal")
            assertSame(storage, index.storageIdentity)
        }

        assertEquals(180, index.valuesSnapshot().size)
        assertEquals("page-179", index.get(179))
    }

    @Test
    fun `default page publication requests replace existing and atomic move together`() {
        var observedOptions = emptySet<CopyOption>()
        val operations = DefaultDownloadFileOperations(
            AtomicPageFileMove { _, _, options ->
                observedOptions = options.toSet()
            },
        )
        val staging = directory.resolve("001.7.tmp").apply { writeText("page") }
        val destination = directory.resolve("001.jpg")

        operations.renamePage(staging, destination)

        assertTrue(StandardCopyOption.REPLACE_EXISTING in observedOptions)
        assertTrue(StandardCopyOption.ATOMIC_MOVE in observedOptions)
    }
}
