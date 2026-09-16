package mihon.data.sync.crypto

import mihon.domain.sync.crypto.SyncAeadEngine

expect object SyncAeadEngineFactory {
    fun create(): SyncAeadEngine
}
