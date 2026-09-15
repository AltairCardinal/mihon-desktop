package mihon.data.sync.crypto

import mihon.domain.sync.crypto.SyncRecoveryBundle

expect object SyncRecoveryFactory {
    fun generate(
        spaceId: String,
        generation: Long,
        keyId: String,
        createdAtMillis: Long,
    ): SyncRecoveryBundle
}
