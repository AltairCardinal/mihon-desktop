package tachiyomi.core.common.preference

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InMemoryStringSetTest {
    @Test
    fun `string sets obey the local copy preference contract`() = runTest {
        InMemoryStringSetContract.verify()
    }
}
