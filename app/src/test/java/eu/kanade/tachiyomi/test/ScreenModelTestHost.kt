package eu.kanade.tachiyomi.test

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.ScreenModelStore
import java.util.UUID

/**
 * Gives production screen models the same scoped ownership they receive from Voyager navigation.
 * Directly constructed models can share Voyager's fallback scope, including a previous test's cancelled Job.
 */
@OptIn(InternalVoyagerApi::class)
internal class ScreenModelTestHost : AutoCloseable {
    @PublishedApi
    internal val holderKey = "test-screen-model-${UUID.randomUUID()}"

    @PublishedApi
    internal var sequence = 0

    inline fun <reified T : ScreenModel> create(noinline factory: () -> T): T =
        ScreenModelStore.getOrPut(holderKey, "model-${sequence++}", factory)

    override fun close() {
        ScreenModelStore.onDisposeNavigator(holderKey)
    }
}
