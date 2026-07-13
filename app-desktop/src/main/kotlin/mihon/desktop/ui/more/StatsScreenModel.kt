package mihon.desktop.ui.more

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.domain.error.AppError
import tachiyomi.domain.library.interactor.AggregateLibraryStats
import tachiyomi.domain.library.interactor.LibraryStats
import tachiyomi.domain.library.model.LibraryManga

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Content(val stats: LibraryStats) : StatsUiState
    data class Error(val error: AppError) : StatsUiState
}

class StatsScreenModel(
    snapshots: Flow<List<LibraryManga>>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    aggregate: AggregateLibraryStats = AggregateLibraryStats(),
) : ScreenModel {
    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            runCatching { snapshots.collect { _state.value = StatsUiState.Content(aggregate(it)) } }
                .onFailure(::reportFailure)
        }
    }

    fun reportFailure(error: Throwable) {
        _state.value = StatsUiState.Error(AppError.Unknown(error))
    }

    override fun onDispose() = scope.cancel()
}
