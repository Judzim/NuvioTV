package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.core.tracking.TrackingSourceSelection
import com.nuvio.tv.core.tracking.availableLibrarySourceModes
import com.nuvio.tv.core.tracking.availableWatchProgressSources
import com.nuvio.tv.core.tracking.effectiveTrackingSourceSelection
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklRewatchMode
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.isSimklRewatchModeSelectable
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingSettingsUiState(
    val watchProgressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val connectedProviderIds: Set<TrackingProviderId> = emptySet(),
    val simklAnimeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    val simklRewatchMode: SimklRewatchMode = SimklRewatchMode.Default,
    val isReady: Boolean = false
) {
    val availableWatchProgressSources: List<WatchProgressSource>
        get() = availableWatchProgressSources(connectedProviderIds)

    val availableLibrarySourceModes: List<LibrarySourceMode>
        get() = availableLibrarySourceModes(connectedProviderIds)
}

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val sourceController: TrackingSourceController,
    private val settingsDataStore: TraktSettingsDataStore,
    private val simklSyncRepository: SimklSyncRepository,
    traktAuthDataStore: TraktAuthDataStore,
    private val simklAuthRepository: SimklAuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()

    /** Set when a free plan picks a rewatch mode, so the screen can offer the upgrade prompt. */
    private val _simklRewatchPlanBlocked = MutableStateFlow(false)
    val simklRewatchPlanBlocked: StateFlow<Boolean> = _simklRewatchPlanBlocked.asStateFlow()

    // Kept in a nested combine because the typed combine overload only takes five flows.
    private val simklPreferences = combine(
        settingsDataStore.simklAnimeIdPreference,
        settingsDataStore.simklRewatchMode
    ) { animeIdPreference, rewatchMode -> animeIdPreference to rewatchMode }

    init {
        viewModelScope.launch {
            combine(
                sourceController.watchProgressSource,
                sourceController.librarySourceMode,
                traktAuthDataStore.state,
                simklAuthRepository.state,
                simklPreferences
            ) { watchProgressSource, librarySourceMode, traktState, simklState, simklPrefs ->
                val (animeIdPref, rewatchMode) = simklPrefs
                val connectedProviderIds = buildSet {
                    if (traktState.isAuthenticated) add(TrackingProviderId.TRAKT)
                    if (simklState.isAuthenticated) add(TrackingProviderId.SIMKL)
                }
                val effective = effectiveTrackingSourceSelection(
                    requested = TrackingSourceSelection(watchProgressSource, librarySourceMode),
                    connectedProviderIds = connectedProviderIds
                )
                TrackingSettingsUiState(
                    watchProgressSource = effective.watchProgressSource,
                    librarySourceMode = effective.librarySourceMode,
                    connectedProviderIds = connectedProviderIds,
                    simklAnimeIdPreference = animeIdPref,
                    simklRewatchMode = rewatchMode,
                    isReady = true
                )
            }.collect { state ->
                _uiState.value = state
                sourceController.reconcileConnectedProviders(state.connectedProviderIds)
            }
        }
    }

    fun selectWatchProgressSource(source: WatchProgressSource) {
        viewModelScope.launch {
            sourceController.selectWatchProgressSource(source)
        }
    }

    fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        viewModelScope.launch {
            sourceController.selectLibrarySourceMode(mode)
        }
    }

    fun selectSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        viewModelScope.launch {
            settingsDataStore.setSimklAnimeIdPreference(preference)
            simklSyncRepository.invalidateProjections(preference)
        }
    }

    fun selectSimklRewatchMode(mode: SimklRewatchMode) {
        if (mode == SimklRewatchMode.OFF) {
            viewModelScope.launch { settingsDataStore.setSimklRewatchMode(mode) }
            return
        }
        viewModelScope.launch {
            // Simkl only stores rewatch sessions for Pro and VIP, so the plan is validated when the
            // user enables the feature instead of on the first write.
            val accountType = simklAuthRepository.ensurePlanLoaded()
            if (isSimklRewatchModeSelectable(mode, accountType)) {
                settingsDataStore.setSimklRewatchMode(mode)
            } else {
                _simklRewatchPlanBlocked.value = true
            }
        }
    }

    fun dismissSimklRewatchPlanBlocked() {
        _simklRewatchPlanBlocked.value = false
    }
}
