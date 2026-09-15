@file:Suppress("ktlint:standard:filename")

package mihon.data.sync.crypto

import mihon.domain.sync.crypto.SyncRecoveryBundle
import mihon.domain.sync.crypto.SyncRecoveryCodec
import java.security.SecureRandom

actual object SyncRecoveryFactory {
    private val random = SecureRandom()

    actual fun generate(
        spaceId: String,
        generation: Long,
        keyId: String,
        createdAtMillis: Long,
    ): SyncRecoveryBundle = SyncRecoveryCodec.generate(
        spaceId,
        generation,
        keyId,
        { ByteArray(32).also(random::nextBytes) },
        createdAtMillis,
    )
}
