package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.hasMiuiPackageInstaller
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Activity used to install extensions, because we can only receive the result of the installation
 * with [startActivityForResult], which we need to update the UI.
 */
class ExtensionInstallActivity : Activity() {

    // MIUI package installer bug workaround
    private var completed = false
    private var ignoreUntil = 0L
    private var ignoreResult = false
    private var hasIgnoredResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            completed = savedInstanceState.getBoolean("completed")
            ignoreResult = savedInstanceState.getBoolean("ignoreResult")
            ignoreUntil = savedInstanceState.getLong("ignoreUntil")
            hasIgnoredResult = savedInstanceState.getBoolean("hasIgnoredResult")
            if (completed) {
                finish()
            } else if (intent.action == Intent.ACTION_UNINSTALL_PACKAGE) {
                val id = intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID)
                val packageName = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart
                if (id == null || packageName == null ||
                    !Injekt.get<ExtensionManager>().restoreUninstall(id, packageName)
                ) {
                    finish()
                }
            } else {
                val id = intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID)
                val packageName = intent.getStringExtra(ExtensionInstaller.EXTRA_PACKAGE_NAME)
                if (id == null || packageName == null ||
                    !Injekt.get<ExtensionManager>().restoreInstallWindow(id, packageName)
                ) {
                    finish()
                }
            }
            return
        }

        @Suppress("DEPRECATION")
        val installIntent = Intent(
            if (intent.action == Intent.ACTION_UNINSTALL_PACKAGE) {
                Intent.ACTION_UNINSTALL_PACKAGE
            } else {
                Intent.ACTION_INSTALL_PACKAGE
            },

        )
            .setDataAndType(intent.data, intent.type)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .putExtra(
                ExtensionInstaller.EXTRA_TRANSACTION_ID,
                intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID),
            )
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (hasMiuiPackageInstaller) {
            ignoreResult = true
            ignoreUntil = System.nanoTime() + 1.seconds.inWholeNanoseconds
        }

        try {
            startActivityForResult(installIntent, INSTALL_REQUEST_CODE)
        } catch (error: Exception) {
            // Either install package can't be found (probably bots) or there's a security exception
            // with the download manager. Nothing we can workaround.
            toast(error.message)
            checkInstallationResult(RESULT_FIRST_USER)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("completed", completed)
        outState.putBoolean("ignoreResult", ignoreResult)
        outState.putLong("ignoreUntil", ignoreUntil)
        outState.putBoolean("hasIgnoredResult", hasIgnoredResult)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != INSTALL_REQUEST_CODE || completed) return
        if (ignoreResult && System.nanoTime() < ignoreUntil) {
            hasIgnoredResult = true
            return
        }
        if (requestCode == INSTALL_REQUEST_CODE) {
            checkInstallationResult(resultCode)
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (hasIgnoredResult) {
            checkInstallationResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && intent.action != Intent.ACTION_UNINSTALL_PACKAGE) {
            intent.data?.let { contentResolver.delete(it, null, null) }
        }
    }

    private fun checkInstallationResult(resultCode: Int) {
        if (completed) return
        completed = true
        val transactionId = intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) ?: return
        val extensionManager = Injekt.get<ExtensionManager>()
        if (intent.action == Intent.ACTION_UNINSTALL_PACKAGE) {
            extensionManager.completeUninstall(transactionId, resultCode)
            return
        }
        val newStep = when (resultCode) {
            RESULT_OK -> InstallStep.Installed
            RESULT_CANCELED -> InstallStep.Idle
            else -> InstallStep.Error
        }
        extensionManager.completeInstallWindow(transactionId, newStep)
    }
}

private const val INSTALL_REQUEST_CODE = 500
