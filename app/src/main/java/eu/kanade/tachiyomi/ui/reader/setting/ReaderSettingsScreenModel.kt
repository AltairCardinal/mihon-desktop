package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
    private val onChangeReaderSettings: ((ReaderSettings?, Boolean) -> Unit)? = null,
) : ScreenModel {

    fun changeReaderSettings(settings: ReaderSettings) {
        onChangeReaderSettings?.invoke(settings, false)
            ?: onChangeReadingMode(ReadingMode.fromPreference(ReaderSettingsCodec.mode(settings).toInt()))
    }

    fun followDefault(follow: Boolean) {
        if (onChangeReaderSettings != null) {
            onChangeReaderSettings.invoke(null, follow)
        } else {
            val flags = mangaFlow.value?.viewerFlags ?: 0L
            val mode = if (follow) {
                0L
            } else {
                ReaderSettingsCodec.mode(
                    ReaderSettingsCodec.custom(flags, preferences.defaultReaderSettings()),
                )
            }
            onChangeReadingMode(ReadingMode.fromPreference(mode.toInt()))
        }
    }

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)
}
