package mihon.desktop.download

import mihon.desktop.platform.DesktopDownloadDirectoryAvailability
import mihon.desktop.platform.DesktopDownloadDirectoryPolicy
import mihon.desktop.platform.DesktopDownloadDirectoryProbe
import mihon.desktop.platform.DesktopDownloadDirectoryProbeResult
import mihon.desktop.platform.DesktopDownloadDirectorySelection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences

class DesktopDownloadDirectoryPreferenceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var backingPreferences: Preferences
    private lateinit var preferenceStore: DesktopPreferenceStore

    @BeforeEach
    fun setUp() {
        backingPreferences = Preferences.userRoot().node("/mihon/test/download-directory/${UUID.randomUUID()}")
        preferenceStore = DesktopPreferenceStore(backingPreferences)
    }

    @AfterEach
    fun tearDown() {
        backingPreferences.removeNode()
    }

    @Test
    fun `unset preference exposes caller default without persisting the machine path`() {
        val defaultDirectory = tempDir.resolve("platform-default").toFile()
        val preference = DesktopDownloadPreferences(preferenceStore).downloadDirectory(defaultDirectory)

        val state = preference.state()

        assertEquals(Preference.appStateKey("download_directory"), preference.key())
        assertEquals(defaultDirectory.toPath().toAbsolutePath().normalize().toFile(), state.defaultDirectory)
        assertNull(state.configuredDirectory)
        assertEquals(state.defaultDirectory, state.activeDirectory)
        assertEquals(state.defaultDirectory, state.pendingDirectory)
        assertEquals(DesktopDownloadDirectoryAvailability.UNKNOWN, state.availability)
        assertFalse(state.restartRequired)
        assertFalse(preference.isSet())
        assertFalse(preferenceStore.getAll().containsKey(preference.key()))
    }

    @Test
    fun `valid custom directory is saved only after validation and in normalized absolute form`() {
        val defaultDirectory = tempDir.resolve("default").toFile()
        val customDirectory = tempDir.resolve("custom").resolve("nested")
        Files.createDirectories(customDirectory)
        val nonNormalizedPath = customDirectory.resolve("..").resolve("nested").toString()
        val preference = DesktopDownloadPreferences(preferenceStore).downloadDirectory(defaultDirectory)

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            preference.save(nonNormalizedPath),
        )

        val normalized = customDirectory.toAbsolutePath().normalize().toFile()
        assertEquals(normalized, result.directory)
        assertTrue(preference.isSet())
        assertEquals(normalized.path, preferenceStore.getAll()[preference.key()])
        assertEquals(normalized, preference.state().configuredDirectory)
    }

    @Test
    fun `validation failure preserves the previously saved custom directory`() {
        val defaultDirectory = tempDir.resolve("default").toFile()
        val existingDirectory = Files.createDirectories(tempDir.resolve("existing"))
        val preference = DesktopDownloadPreferences(preferenceStore).downloadDirectory(defaultDirectory)
        assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            preference.save(existingDirectory.toString()),
        )
        val storedBeforeFailure = preferenceStore.getAll()[preference.key()]
        val failingPreference = DesktopDownloadPreferences(preferenceStore).downloadDirectory(
            defaultDirectory = defaultDirectory,
            policy = DesktopDownloadDirectoryPolicy(
                DesktopDownloadDirectoryProbe {
                    DesktopDownloadDirectoryProbeResult(
                        DesktopDownloadDirectoryAvailability.NOT_WRITABLE,
                        IllegalStateException("denied"),
                    )
                },
            ),
        )

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            failingPreference.save(tempDir.resolve("denied").toString()),
        )

        assertEquals(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, result.availability)
        assertEquals(storedBeforeFailure, preferenceStore.getAll()[preference.key()])
        assertEquals(existingDirectory.toAbsolutePath().normalize().toFile(), failingPreference.state().configuredDirectory)
    }

    @Test
    fun `restore default deletes custom value and a new owner exposes the new platform default`() {
        val firstDefault = tempDir.resolve("first-default").toFile()
        val customDirectory = Files.createDirectories(tempDir.resolve("custom"))
        val preference = DesktopDownloadPreferences(preferenceStore).downloadDirectory(firstDefault)
        assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            preference.save(customDirectory.toString()),
        )

        preference.restoreDefault()

        val secondDefault = tempDir.resolve("second-default").toFile()
        val reconstructed = DesktopDownloadPreferences(preferenceStore).downloadDirectory(secondDefault)
        assertFalse(reconstructed.isSet())
        assertFalse(preferenceStore.getAll().containsKey(reconstructed.key()))
        assertEquals(secondDefault.toPath().toAbsolutePath().normalize().toFile(), reconstructed.state().activeDirectory)
    }
}
