package mihon.test.desktop.robot

import mihon.test.desktop.DesktopTestClient
import mihon.test.desktop.SourceExtensionTestSnapshot

/**
 * Robot for More tab interactions.
 */
class MoreRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the More tab.
     */
    fun open(): MoreRobot {
        client.navigate("MoreTab")
        return this
    }

    /**
     * Open settings screen.
     */
    fun openSettings(): SettingsRobot {
        client.navigate("SettingsScreen")
        return SettingsRobot(client)
    }

    /**
     * Open extension list screen.
     */
    fun openExtensions(): ExtensionsRobot {
        client.navigate("ExtensionListScreen")
        return ExtensionsRobot(client)
    }

    /**
     * Open migration screen.
     */
    fun openMigration(): MigrationRobot {
        client.navigate("MigrationSearchScreen")
        return MigrationRobot(client)
    }

    /**
     * Open about screen.
     */
    fun openAbout(): AboutRobot {
        client.navigate("AboutScreen")
        return AboutRobot(client)
    }

    /**
     * Open backup/restore screen.
     */
    fun openBackup(): BackupRobot {
        client.navigate("BackupScreen")
        return BackupRobot(client)
    }

}

/**
 * Robot for Extensions management screen.
 */
class ExtensionsRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to extensions screen.
     */
    fun open(): ExtensionsRobot {
        client.navigate("ExtensionListScreen")
        return this
    }

    fun state(): SourceExtensionTestSnapshot? = client.getState().extension

    fun refresh() = action("extension_refresh")
    fun search(query: String) = action("extension_search", mapOf("query" to query))
    fun install(packageName: String) = packageAction("extension_install", packageName)
    fun update(packageName: String) = packageAction("extension_update", packageName)
    fun retry(packageName: String) = packageAction("extension_retry", packageName)
    fun cancel(packageName: String) = packageAction("extension_cancel", packageName)
    fun updateAll() = action("extension_update_all")
    fun uninstall(packageName: String) = packageAction("extension_uninstall", packageName)
    fun trustConfirm(packageName: String) = packageAction("extension_trust_confirm", packageName)
    fun trustDismiss(packageName: String) = packageAction("extension_trust_dismiss", packageName)

    private fun packageAction(name: String, packageName: String) = action(name, mapOf("packageName" to packageName))

    private fun action(name: String, params: Map<String, Any?> = emptyMap()): ExtensionsRobot {
        val result = client.executeAction(name, params)
        check(result.success) { "$name failed: ${result.error ?: "unknown error"}" }
        return this
    }

}

/**
 * Robot for Migration screen.
 */
class MigrationRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to migration screen.
     */
    fun open(): MigrationRobot {
        client.navigate("MigrationSearchScreen")
        return this
    }

    /**
     * Search for manga to migrate.
     */
    fun search(query: String): MigrationRobot {
        client.executeAction("migration_search", mapOf("query" to query))
        return this
    }

    /**
     * Select a manga by index.
     */
    fun selectManga(index: Int): MigrationRobot {
        client.executeAction("migration_select", mapOf("index" to index))
        return this
    }

}

/**
 * Robot for About screen.
 */
class AboutRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to about screen.
     */
    fun open(): AboutRobot {
        client.navigate("AboutScreen")
        return this
    }

}

/**
 * Robot for Backup/Restore screen.
 */
class BackupRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to backup screen.
     */
    fun open(): BackupRobot {
        client.navigate("BackupScreen")
        return this
    }

    /**
     * Create a backup.
     */
    fun createBackup(): BackupRobot {
        client.executeAction("backup_create")
        return this
    }

    /**
     * Restore from backup.
     */
    fun restoreBackup(): BackupRobot {
        client.executeAction("backup_restore")
        return this
    }

}
