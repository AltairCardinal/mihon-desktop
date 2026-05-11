package android.util

/**
 * Desktop stub for android.util.Log.
 * All log calls delegate to stderr so they appear in the IDE console.
 */
object Log {
    fun v(tag: String, msg: String): Int { System.err.println("V/$tag: $msg"); return 0 }
    fun d(tag: String, msg: String): Int { System.err.println("D/$tag: $msg"); return 0 }
    fun i(tag: String, msg: String): Int { System.err.println("I/$tag: $msg"); return 0 }
    fun w(tag: String, msg: String): Int { System.err.println("W/$tag: $msg"); return 0 }
    fun e(tag: String, msg: String): Int { System.err.println("E/$tag: $msg"); return 0 }
    fun e(tag: String, msg: String, tr: Throwable): Int {
        System.err.println("E/$tag: $msg")
        tr.printStackTrace(System.err)
        return 0
    }
    fun w(tag: String, tr: Throwable): Int {
        System.err.println("W/$tag: ${tr.message}")
        return 0
    }
    fun wtf(tag: String, msg: String): Int { System.err.println("WTF/$tag: $msg"); return 0 }
    fun getStackTraceString(tr: Throwable): String = tr.stackTraceToString()
}
