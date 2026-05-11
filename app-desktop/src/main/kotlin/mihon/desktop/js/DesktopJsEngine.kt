package mihon.desktop.js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * A lightweight JavaScript engine backed by Rhino (JVM-native, no native libs required).
 *
 * Provides a sandbox for evaluating scripts that extensions and deobfuscation
 * utilities require. Each [DesktopJsEngine] instance has its own isolated scope.
 *
 * Usage:
 * ```kotlin
 * val engine = DesktopJsEngine()
 * val result = engine.evaluate("1 + 2")
 * engine.close()
 * ```
 *
 * Thread-safety: NOT thread-safe. Use one instance per thread or synchronize externally.
 */
class DesktopJsEngine {

    private val cx: Context = Context.enter().also { cx ->
        // Optimization level -1 = interpreted mode (no JIT classloaders needed)
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
    }
    private val scope: ScriptableObject = cx.initSafeStandardObjects()
    private var closed = false

    init {
        // Polyfill btoa / atob — commonly used by extensions
        cx.evaluateString(
            scope,
            """
            var btoa = function(s) {
                var bytes = [];
                for (var i = 0; i < s.length; i++) bytes.push(s.charCodeAt(i));
                var b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                var result = "";
                for (var i = 0; i < bytes.length; i += 3) {
                    var b = (bytes[i] << 16) | ((bytes[i+1] || 0) << 8) | (bytes[i+2] || 0);
                    result += b64[(b >> 18) & 0x3f] + b64[(b >> 12) & 0x3f] +
                              (bytes[i+1] !== undefined ? b64[(b >> 6) & 0x3f] : "=") +
                              (bytes[i+2] !== undefined ? b64[b & 0x3f] : "=");
                }
                return result;
            };
            var atob = function(s) {
                var b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                s = s.replace(/=+$/, '');
                var result = "";
                for (var i = 0; i < s.length; i += 4) {
                    var b = (b64.indexOf(s[i]) << 18) | (b64.indexOf(s[i+1]) << 12) |
                            (b64.indexOf(s[i+2]) << 6) | b64.indexOf(s[i+3]);
                    result += String.fromCharCode((b >> 16) & 0xff);
                    if (s[i+2] !== "=") result += String.fromCharCode((b >> 8) & 0xff);
                    if (s[i+3] !== "=") result += String.fromCharCode(b & 0xff);
                }
                return result;
            };
            """.trimIndent(),
            "<init>",
            1,
            null,
        )
    }

    /**
     * Evaluates [script] in the engine's scope and returns the last expression value.
     * Returns `null` for undefined results.
     * @throws org.mozilla.javascript.RhinoException on JS errors
     */
    fun evaluate(script: String): Any? {
        check(!closed) { "DesktopJsEngine has been closed" }
        val result = cx.evaluateString(scope, script, "<script>", 1, null)
        return if (result is Undefined) null else result
    }

    /**
     * Releases the Rhino [Context]. After calling this method, [evaluate] will throw.
     * Calling [close] multiple times is safe (idempotent).
     */
    fun close() {
        if (!closed) {
            closed = true
            Context.exit()
        }
    }
}
