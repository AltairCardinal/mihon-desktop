package eu.kanade.tachiyomi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppProcessTest {
    @Test
    fun `error handler process skips business initialization`() {
        assertTrue(isErrorHandlerProcess("app.mihon.desktop.fork:error_handler"))
        assertFalse(isErrorHandlerProcess("app.mihon.desktop.fork"))
        assertFalse(isErrorHandlerProcess(null))
    }
}
