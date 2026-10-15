package tachiyomi.domain.release.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.UpdateChannel
import java.util.concurrent.ConcurrentHashMap

class ApplicationUpdatePreferences(private val preferenceStore: PreferenceStore) {
    private val automatic by lazy { preferenceStore.getBoolean("fork_update_enabled", true) }
    private val testing by lazy { preferenceStore.getBoolean("fork_update_testing_channel", true) }
    private val prompts = ConcurrentHashMap<Pair<ReleaseOs, UpdateChannel>, Preference<String>>()

    fun enabled(): Preference<Boolean> = automatic
    fun testingChannel(): Preference<Boolean> = testing
    fun lastPromptedVersion(platform: ReleaseOs, channel: UpdateChannel): Preference<String> =
        prompts.getOrPut(platform to channel) {
            preferenceStore.getString(Preference.appStateKey("fork_update_prompt_${platform}_$channel"))
        }
}
