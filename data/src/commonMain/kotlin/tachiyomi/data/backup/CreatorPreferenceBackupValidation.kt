package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences

/** Validate app settings only, before any write; source preferences use independent namespaces. */
fun validateCreatorPreferenceBackup(preference: BackupPreference) {
    if (preference.key != CreatorDiscoveryPreferences.FREQUENCY_KEY) return
    val raw = (preference.value as? StringPreferenceValue)?.value
    require(raw != null && CreatorCheckFrequency.parse(raw) != null) {
        "Unsupported author check frequency in backup; local setting was preserved"
    }
}
