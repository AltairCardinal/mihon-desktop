package mihon.domain.sync.security

/**
 * Device-local secret records. Values are bounded to 64 KiB of UTF-8.
 * Implementations serialize all instances and processes using the same backing directory.
 * Null means absent or delete; storage, decryption and permission failures must throw.
 */
interface SyncSecureStore {
    suspend fun read(key: String): String?
    suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean
}

class SyncSecureStoreException : IllegalStateException("Secure sync storage is unavailable or invalid")
