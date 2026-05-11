package android.os

/**
 * Desktop stub for android.os.Bundle.
 * Simple key-value container.
 */
open class Bundle {
    private val data = mutableMapOf<String, Any?>()

    val isEmpty: Boolean get() = data.isEmpty()

    fun size(): Int = data.size

    fun containsKey(key: String): Boolean = data.containsKey(key)

    fun remove(key: String) { data.remove(key) }

    fun clear() { data.clear() }

    fun keySet(): Set<String> = data.keys.toSet()

    // String
    fun putString(key: String, value: String?) { data[key] = value }
    fun getString(key: String): String? = data[key] as? String
    fun getString(key: String, defaultValue: String): String = data[key] as? String ?: defaultValue

    // Int
    fun putInt(key: String, value: Int) { data[key] = value }
    fun getInt(key: String): Int = data[key] as? Int ?: 0
    fun getInt(key: String, defaultValue: Int): Int = data[key] as? Int ?: defaultValue

    // Long
    fun putLong(key: String, value: Long) { data[key] = value }
    fun getLong(key: String): Long = data[key] as? Long ?: 0L
    fun getLong(key: String, defaultValue: Long): Long = data[key] as? Long ?: defaultValue

    // Boolean
    fun putBoolean(key: String, value: Boolean) { data[key] = value }
    fun getBoolean(key: String): Boolean = data[key] as? Boolean ?: false
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = data[key] as? Boolean ?: defaultValue

    // Float
    fun putFloat(key: String, value: Float) { data[key] = value }
    fun getFloat(key: String): Float = data[key] as? Float ?: 0f
    fun getFloat(key: String, defaultValue: Float): Float = data[key] as? Float ?: defaultValue

    // Double
    fun putDouble(key: String, value: Double) { data[key] = value }
    fun getDouble(key: String): Double = data[key] as? Double ?: 0.0

    // Bundle
    fun putBundle(key: String, value: Bundle?) { data[key] = value }
    fun getBundle(key: String): Bundle? = data[key] as? Bundle

    // Generic
    operator fun get(key: String): Any? = data[key]
}
