package mihon.desktop.js

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DesktopJsEngineTest {

    private lateinit var engine: DesktopJsEngine

    @BeforeEach
    fun setUp() { engine = DesktopJsEngine() }

    @AfterEach
    fun tearDown() { engine.close() }

    @Test
    fun `evaluate returns numeric result`() {
        val result = engine.evaluate("1 + 2")
        assertEquals(3, (result as Number).toInt())
    }

    @Test
    fun `evaluate returns string result`() {
        val result = engine.evaluate("'hello' + ' world'")
        assertEquals("hello world", result)
    }

    @Test
    fun `evaluate supports var declaration`() {
        val result = engine.evaluate("var x = 42; x")
        assertEquals(42, (result as Number).toInt())
    }

    @Test
    fun `evaluate supports function definition and call`() {
        val result = engine.evaluate(
            """
            function add(a, b) { return a + b; }
            add(10, 20)
            """.trimIndent(),
        )
        assertEquals(30, (result as Number).toInt())
    }

    @Test
    fun `evaluate supports JSON parse`() {
        val result = engine.evaluate(
            """
            var obj = JSON.parse('{"key":"value"}');
            obj.key
            """.trimIndent(),
        )
        assertEquals("value", result)
    }

    @Test
    fun `evaluate supports btoa`() {
        // btoa("hello") = "aGVsbG8="
        val result = engine.evaluate("btoa('hello')")
        assertEquals("aGVsbG8=", result)
    }

    @Test
    fun `close is idempotent`() {
        engine.close()
        engine.close() // should not throw
    }
}
