package mihon.desktop.domain

import kotlinx.coroutines.runBlocking
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.addSingleton
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences

@org.junit.jupiter.api.parallel.Isolated
class CreatorGlobalScheduleWiringTest {
    @TempDir lateinit var directory: Path
    @Test fun `real Desktop scheduler executes global calendar contract`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        tachiyomi.data.Database.Schema.create(driver)
        val database = tachiyomi.data.Database(driver,
            historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(tachiyomi.data.StringListColumnAdapter, tachiyomi.data.UpdateStrategyColumnAdapter),
        )
        val node = Preferences.userRoot().node("/mihon-tests/ga03-${UUID.randomUUID()}")
        var scheduler: CreatorDiscoveryScheduler? = null
        try {
            tachiyomi.data.creator.verifyCreatorGlobalSchedule(
                tachiyomi.data.JvmDatabaseHandler(database, driver),
                tachiyomi.core.common.preference.DesktopPreferenceStore(node),
            ) { repository, service ->
                val owner = CreatorDiscoveryScheduler(
                    mihon.desktop.task.DesktopTaskScheduler(mihon.desktop.task.FileTaskCheckpointStore(directory.resolve("tasks.json"))),
                    discoverDue = service::discoverDueWatches,
                    discoverCreator = { service.discoverCreator(it) },
                    connectivity = mihon.desktop.tracking.DesktopNetworkConnectivity { true },
                )
                scheduler = owner
                var occurrences = 0
                val wake: suspend () -> Unit = {
                    owner.runNow().join()
                    if (occurrences++ == 0) check(owner.state.value.totalSources == 2) { owner.state.value.toString() }
                }
                wake
            }
        } finally { scheduler?.stopAndJoin(); driver.close(); node.removeNode() }
    }
    @Test fun `actual Desktop modules share global preference in service repository and UI`() = runBlocking {
        val node = Preferences.userRoot().node("/mihon-tests/ga03-di-${UUID.randomUUID()}")
        val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
        val sourceId = 9901L
        val type = readerfixture.ReflectiveImageSource::class.java
        val classPath = type.name.replace('.', '/') + ".class"
        val extensionDir = directory.resolve("extensions").toFile().apply { mkdirs() }
        java.util.zip.ZipOutputStream(extensionDir.resolve("ga03-fixture.jar").outputStream()).use { zip ->
            val entries = listOf(
                classPath to checkNotNull(type.classLoader.getResourceAsStream(classPath)).use { it.readBytes() },
                "META-INF/services/eu.kanade.tachiyomi.source.Source" to type.name.toByteArray(Charsets.UTF_8),
            )
            entries.forEach { (name, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        val context = mihon.desktop.di.initDesktopDIForTest(directory.toFile(), store,
            trackerConnectivity = mihon.desktop.tracking.DesktopNetworkConnectivity { true })
        try {
            val preferences = Injekt.get<tachiyomi.domain.creator.service.CreatorDiscoveryPreferences>()
            preferences.frequency().set("weekly")
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().enabledLanguages.set(setOf("en"))
            check(Injekt.get<tachiyomi.domain.creator.service.CreatorDiscoverySourcePort>().enabledSourcesSnapshot().any { it.sourceId == sourceId })
            tachiyomi.data.creator.verifyCreatorBackupReadiness(
                context.handler, Injekt.get<tachiyomi.data.backup.AuthorArchiveBackupContributor>(),
            )
            val repository = Injekt.get<tachiyomi.domain.creator.repository.CreatorArchiveRepository>()
            val creator = Injekt.get<tachiyomi.domain.creator.repository.CreatorRepository>().upsertCreator("Desktop DI author")
            repository.upsertWatchPolicy(tachiyomi.domain.creator.model.ArchiveWatchPolicy(creator.id, true, 60_000, setOf(sourceId), emptySet()), System.currentTimeMillis())
            Injekt.get<CreatorDiscoveryScheduler>().runNow().join()
            check(Injekt.get<CreatorDiscoveryScheduler>().state.value.totalSources > 0) { Injekt.get<CreatorDiscoveryScheduler>().state.value.toString() }
            val success = repository.getSourceCheckpoints(creator.id).single().lastSuccessAt!!
            val rawDue = context.handler.awaitList { author_archiveQueries.getArchiveDueWatchSources(success + 2 * 86_400_000L, 10) }
            check(rawDue.isEmpty()) { "service did not receive the global schedule" }
            check(repository.getDueWatchSources(success + 2 * 86_400_000L, 10).isEmpty()) { "repository lost the shared schedule" }
            check(repository.getDueWatchSources(success + 8 * 86_400_000L, 10).size == 1)
            val dependencies = mihon.desktop.DesktopUiDependencies.fromInjekt()
            check(dependencies.creatorDiscoveryPreferences === preferences)
        } finally { context.closeAndJoin(); node.removeNode() }
    }

}
