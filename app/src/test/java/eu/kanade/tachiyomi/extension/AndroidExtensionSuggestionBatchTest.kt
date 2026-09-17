package eu.kanade.tachiyomi.extension

import eu.kanade.domain.base.BasePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class AndroidExtensionSuggestionBatchTest {
    @Test
    fun `interruption survives immediate success and old callback cannot pause a new confirmation`() = runTest {
        val artifacts = listOf(artifact("first"), artifact("second"), artifact("third"))
        val arbiter = ExtensionInstallArbiter()
        var oldCallback: ((SuggestionBatchPause) -> Unit)? = null
        val calls = mutableListOf<String>()
        val manager = mockk<ExtensionManager> {
            every { scope } returns backgroundScope
            every { installArbiter } returns arbiter
            every { inventory } returns MutableStateFlow(ExtensionInventory(true))
            every { suggestionCatalog } returns MutableStateFlow(
                ExtensionCatalogResult(
                    artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) },
                    emptyList(),
                    listOf(artifacts.first().repository),
                ),
            )
            coEvery { installReservedObserved(any(), any(), any()) } coAnswers {
                val lease = firstArg<mihon.domain.extension.service.ExtensionInstallLease>()
                calls += lease.artifact.packageName
                if (lease.artifact == artifacts.first()) {
                    oldCallback = thirdArg()
                    oldCallback!!(SuggestionBatchPause.SERVICE)
                } else if (lease.artifact == artifacts[1]) {
                    oldCallback!!(SuggestionBatchPause.PERMISSION)
                }
                SuggestionBatchResult.Installed
            }
        }
        val batch = AndroidExtensionSuggestionBatch(
            manager,
            MutableStateFlow(
                ExtensionSuggestions(
                    false,
                    artifacts.map {
                        ExtensionSuggestion(SuggestionIdentity.of(it), it, emptyList(), false)
                    },
                ),
            ),
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        ) {
            BasePreferences.ExtensionInstaller.SHIZUKU
        }
        runCurrent()
        assertTrue(batch.start(artifacts))
        runCurrent()
        assertEquals(listOf(artifacts.first().packageName), calls)
        assertEquals(SuggestionBatchResult.Installed, batch.state.value.items.first().result)
        assertEquals(SuggestionBatchPause.SERVICE, batch.state.value.pauseReason)
        assertEquals(artifacts.drop(1), batch.state.value.remaining)
        assertTrue(batch.resume(artifacts.drop(1)))
        runCurrent()
        assertEquals(artifacts.map { it.packageName }, calls)
        assertTrue(batch.state.value.items.all { it.result == SuggestionBatchResult.Installed })
        assertTrue(batch.state.value.remaining.isEmpty())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @EnumSource(
        ExtensionInstallInvalidation::class,
        names = ["PRESENT", "INELIGIBLE", "IGNORED", "CATALOG_CHANGED", "INVENTORY_UNKNOWN"],
    )
    fun `live eligibility is rechecked after download before irreversible commit`(
        invalidation: ExtensionInstallInvalidation,
    ) = runTest {
        val artifact = artifact("first")
        val arbiter = ExtensionInstallArbiter()
        val downloaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val commit = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suggestions = MutableStateFlow(
            ExtensionSuggestions(
                false,
                listOf(
                    ExtensionSuggestion(SuggestionIdentity.of(artifact), artifact, emptyList(), false),
                ),
            ),
        )
        val inventory = MutableStateFlow(ExtensionInventory(initialized = true))
        val catalog = MutableStateFlow<ExtensionCatalogResult?>(
            ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
                emptyList(),
                listOf(artifact.repository),
            ),
        )
        val preferences = ExtensionSuggestionPreferences(InMemoryPreferenceStore())
        val manager = mockk<ExtensionManager> {
            every { scope } returns backgroundScope
            every { installArbiter } returns arbiter
            every { this@mockk.inventory } returns inventory
            every { suggestionCatalog } returns catalog
            coEvery { installReservedObserved(any(), any(), any()) } coAnswers {
                val lease = firstArg<mihon.domain.extension.service.ExtensionInstallLease>()
                check(arbiter.activate(lease, lease.artifact))
                downloaded.complete(Unit)
                commit.await()
                arbiter.enterCommit(lease)
                SuggestionBatchResult.Installed
            }
        }
        val batch = AndroidExtensionSuggestionBatch(manager, suggestions, preferences) {
            BasePreferences.ExtensionInstaller.LEGACY
        }
        runCurrent()
        assertTrue(batch.start(listOf(artifact)))
        runCurrent()
        assertTrue(downloaded.isCompleted)
        when (invalidation) {
            ExtensionInstallInvalidation.PRESENT ->
                inventory.value =
                    ExtensionInventory(true, mapOf(artifact.packageName to ExtensionPresence.PRESENT))
            ExtensionInstallInvalidation.INELIGIBLE -> suggestions.value = ExtensionSuggestions(false)
            ExtensionInstallInvalidation.IGNORED -> preferences.ignored.set(
                mihon.domain.extension.suggestion.suggestionIdentityKey(SuggestionIdentity.of(artifact)),
            )
            ExtensionInstallInvalidation.CATALOG_CHANGED -> catalog.value = null
            ExtensionInstallInvalidation.INVENTORY_UNKNOWN -> inventory.value = ExtensionInventory()
            else -> error("Unexpected fixture")
        }
        runCurrent()
        commit.complete(Unit)
        runCurrent()
        assertEquals(SuggestionBatchResult.Invalidated(invalidation), batch.state.value.items.single().result)
        assertFalse(arbiter.isBusy(artifact.packageName))
    }

    @Test
    fun `installer change during download pauses before the reserved commit`() = runTest {
        val artifact = artifact("first")
        val arbiter = ExtensionInstallArbiter()
        var selected = BasePreferences.ExtensionInstaller.LEGACY
        val downloaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val commit = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suggestions = MutableStateFlow(
            ExtensionSuggestions(
                false,
                listOf(
                    ExtensionSuggestion(SuggestionIdentity.of(artifact), artifact, emptyList(), false),
                ),
            ),
        )
        val manager = mockk<ExtensionManager> {
            every { scope } returns backgroundScope
            every { installArbiter } returns arbiter
            every { inventory } returns MutableStateFlow(ExtensionInventory(initialized = true))
            every { suggestionCatalog } returns MutableStateFlow(
                ExtensionCatalogResult(
                    listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
                    emptyList(),
                    listOf(artifact.repository),
                ),
            )
            coEvery { installReservedObserved(any(), any(), any()) } coAnswers {
                val lease = firstArg<mihon.domain.extension.service.ExtensionInstallLease>()
                check(arbiter.activate(lease, lease.artifact))
                downloaded.complete(Unit)
                commit.await()
                arbiter.enterCommit(lease)
                SuggestionBatchResult.Installed
            }
        }
        val batch = AndroidExtensionSuggestionBatch(
            manager,
            suggestions,
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        ) { selected }
        runCurrent()
        assertTrue(batch.start(listOf(artifact)))
        runCurrent()
        assertTrue(downloaded.isCompleted)
        selected = BasePreferences.ExtensionInstaller.PRIVATE
        commit.complete(Unit)
        runCurrent()
        assertEquals(SuggestionBatchPause.INSTALLER_CHANGED, batch.state.value.pauseReason)
        assertEquals(listOf(artifact), batch.state.value.remaining)
        assertFalse(arbiter.isBusy(artifact.packageName))
    }

    @Test
    fun `application batch pauses after system cancel and resumes only explicitly confirmed remainder`() = runTest {
        val first = artifact("first")
        val second = artifact("second")
        val arbiter = ExtensionInstallArbiter()
        val suggestions = MutableStateFlow(
            ExtensionSuggestions(
                false,
                listOf(first, second).map {
                    ExtensionSuggestion(SuggestionIdentity.of(it), it, emptyList(), false)
                },
            ),
        )
        val manager = mockk<ExtensionManager> {
            every { scope } returns backgroundScope
            every { installArbiter } returns arbiter
            every { inventory } returns MutableStateFlow(ExtensionInventory(initialized = true))
            every { suggestionCatalog } returns MutableStateFlow(
                ExtensionCatalogResult(
                    listOf(first, second).map {
                        ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
                    },
                    emptyList(),
                    listOf(first.repository),
                ),
            )
            coEvery {
                installReservedObserved(
                    match { it.artifact == first },
                    any(),
                    any(),
                )
            } returns SuggestionBatchResult.Cancelled
            coEvery {
                installReservedObserved(
                    match { it.artifact == second },
                    any(),
                    any(),
                )
            } returns SuggestionBatchResult.Installed
        }
        val batch = AndroidExtensionSuggestionBatch(
            manager,
            suggestions,
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        ) { BasePreferences.ExtensionInstaller.LEGACY }
        runCurrent()
        assertTrue(batch.start(listOf(first, second)))
        runCurrent()
        assertFalse(batch.state.value.running)
        assertEquals(SuggestionBatchPause.CONFIRMATION_CANCELLED, batch.state.value.pauseReason)
        assertEquals(listOf(second), batch.state.value.remaining)
        coVerify(exactly = 0) { manager.installReservedObserved(match { it.artifact == second }, any(), any()) }
        assertTrue(batch.resume(listOf(second)))
        runCurrent()
        assertEquals(
            listOf(SuggestionBatchResult.Cancelled, SuggestionBatchResult.Installed),
            batch.state.value.items.map { it.result },
        )
        assertTrue(batch.state.value.remaining.isEmpty())
        assertFalse(arbiter.isBusy(first.packageName))
        assertFalse(arbiter.isBusy(second.packageName))
    }

    private fun artifact(name: String) = ExtensionArtifact(
        name, "pkg.$name", "1.6.1", 1, "en", false, emptyList(),
        RepositoryIdentity("https://repo.example", "Repository", "signer"),
        "https://repo.example/$name.apk", "", null,
    )
}
