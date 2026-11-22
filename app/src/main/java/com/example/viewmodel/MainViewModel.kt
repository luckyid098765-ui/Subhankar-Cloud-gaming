package com.example.viewmodel

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ShubhankarCloudApp
import com.example.data.local.TouchControlPlacementEntity
import com.example.data.local.UserSettings
import com.example.data.model.CoinPricingCalculator
import com.example.data.model.GameItem
import com.example.data.model.GpuNodeStatus
import com.example.data.model.HealthCheckResponse
import com.example.data.model.SessionHistoryItem
import com.example.data.model.StartSessionRequest
import com.example.data.model.StartSessionResponse
import com.example.data.model.StreamQualityPreset
import com.example.data.model.SupportTicketItem
import com.example.data.model.SupportTicketRequest
import com.example.data.model.UserProfile
import com.example.data.model.VideoCodecPreference
import com.example.data.model.WalletSummary
import com.example.data.remote.NetworkStatus
import com.example.data.repository.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MainUiState(
    val isBootstrapping: Boolean = true,
    val isAuthenticated: Boolean = false,
    val isAuthBusy: Boolean = false,
    val authError: String? = null,
    val user: UserProfile? = null,
    val serverHealth: HealthCheckResponse? = null,
    val serverReachable: Boolean = false,
    val games: List<GameItem> = emptyList(),
    val isSyncingGames: Boolean = false,
    val searchQuery: String = "",
    val selectedCategory: String = "All",
    val wallet: WalletSummary? = null,
    val isWalletBusy: Boolean = false,
    val sessionHistory: List<SessionHistoryItem> = emptyList(),
    val gpuNodes: List<GpuNodeStatus> = emptyList(),
    val supportTickets: List<SupportTicketItem> = emptyList(),
    val activeSession: StartSessionResponse? = null,
    val remainingSessionSeconds: Int = 0,
    val sessionWarningBanner: String? = null,
    val isStartingSession: Boolean = false,
    val statusBannerMessage: String? = null,
    val isErrorBanner: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShubhankarCloudApp
    private val repository = app.repository
    private val preferences = app.preferencesManager
    val webRtcManager = app.webRtcManager
    val inputBus = app.controllerInputBus

    val settings: StateFlow<UserSettings> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val networkStatus: StateFlow<NetworkStatus> = app.networkMonitor.status

    val touchPlacements: StateFlow<List<TouchControlPlacementEntity>> =
        repository.touchLayoutsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = defaultTouchPlacements()
        )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var notifiedFiveMinutes = false
    private var notifiedOneMinute = false

    init {
        viewModelScope.launch {
            repository.cachedGamesFlow.collect { cached ->
                if (cached.isNotEmpty() && _uiState.value.games.isEmpty()) {
                    _uiState.update { it.copy(games = cached) }
                }
            }
        }
        bootstrap()
    }

    fun bootstrap() {
        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val health = repository.checkServerHealth()
            val token = preferences.accessTokenFlow.first()
            if (!token.isNullOrBlank()) {
                when (val profileRes = repository.fetchProfile()) {
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isAuthenticated = true,
                                user = profileRes.data,
                                serverReachable = health is Resource.Success,
                                serverHealth = (health as? Resource.Success)?.data
                            )
                        }
                        refreshAllUserData()
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isAuthenticated = false,
                                serverReachable = health is Resource.Success,
                                serverHealth = (health as? Resource.Success)?.data
                            )
                        }
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isAuthenticated = false,
                        serverReachable = health is Resource.Success,
                        serverHealth = (health as? Resource.Success)?.data
                    )
                }
            }
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < 1200L) delay(1200L - elapsed)
            _uiState.update { it.copy(isBootstrapping = false) }
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(statusBannerMessage = null, authError = null) }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val res = repository.login(email, password)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isAuthBusy = false,
                            isAuthenticated = true,
                            user = res.data.user,
                            serverReachable = true
                        )
                    }
                    refreshAllUserData()
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isAuthBusy = false, authError = res.message) }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun register(username: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val res = repository.register(username, email, password)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isAuthBusy = false,
                            isAuthenticated = true,
                            user = res.data.user,
                            serverReachable = true
                        )
                    }
                    refreshAllUserData()
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isAuthBusy = false, authError = res.message) }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun signInWithGoogleToken(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val res = repository.loginWithGoogle(idToken)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isAuthBusy = false,
                            isAuthenticated = true,
                            user = res.data.user,
                            serverReachable = true
                        )
                    }
                    refreshAllUserData()
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isAuthBusy = false, authError = res.message) }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun setAuthError(message: String) {
        _uiState.update { it.copy(authError = message, isAuthBusy = false) }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            countdownJob?.cancel()
            webRtcManager.stopStream()
            repository.logout()
            _uiState.update {
                MainUiState(
                    isBootstrapping = false,
                    isAuthenticated = false,
                    serverReachable = it.serverReachable,
                    serverHealth = it.serverHealth
                )
            }
            onComplete()
        }
    }

    fun refreshAllUserData() {
        viewModelScope.launch {
            val health = repository.checkServerHealth()
            _uiState.update {
                it.copy(
                    serverReachable = health is Resource.Success,
                    serverHealth = (health as? Resource.Success)?.data
                )
            }
            syncGames()
            refreshWallet()
            refreshHistory()
            refreshGpuNodes()
            refreshSupportTickets()
        }
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        syncGames()
    }

    fun updateCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
        syncGames()
    }

    fun syncGames() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingGames = true) }
            val q = _uiState.value.searchQuery.takeIf { it.isNotBlank() }
            val cat = _uiState.value.selectedCategory.takeIf { it != "All" }
            when (val res = repository.syncGames(search = q, category = cat)) {
                is Resource.Success -> {
                    _uiState.update { it.copy(games = res.data, isSyncingGames = false, serverReachable = true) }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isSyncingGames = false,
                            serverReachable = !res.isNetworkError,
                            statusBannerMessage = res.message,
                            isErrorBanner = true
                        )
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun refreshWallet() {
        viewModelScope.launch {
            when (val res = repository.fetchWalletSummary()) {
                is Resource.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            wallet = res.data,
                            user = state.user?.copy(coinBalance = res.data.balance)
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    fun claimDailyReward() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWalletBusy = true) }
            when (val res = repository.claimDailyReward()) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isWalletBusy = false,
                            statusBannerMessage = "+${res.data.coinsCredited} daily coins credited!",
                            isErrorBanner = false
                        )
                    }
                    refreshWallet()
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isWalletBusy = false,
                            statusBannerMessage = res.message,
                            isErrorBanner = true
                        )
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            when (val res = repository.fetchSessionHistory()) {
                is Resource.Success -> _uiState.update { it.copy(sessionHistory = res.data) }
                else -> Unit
            }
        }
    }

    fun refreshGpuNodes() {
        viewModelScope.launch {
            when (val res = repository.fetchGpuServers()) {
                is Resource.Success -> _uiState.update { it.copy(gpuNodes = res.data) }
                else -> Unit
            }
        }
    }

    fun refreshSupportTickets() {
        viewModelScope.launch {
            when (val res = repository.fetchSupportTickets()) {
                is Resource.Success -> _uiState.update { it.copy(supportTickets = res.data) }
                else -> Unit
            }
        }
    }

    fun submitSupportTicket(category: String, subject: String, message: String) {
        viewModelScope.launch {
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"
            when (
                val res = repository.submitSupportTicket(
                    SupportTicketRequest(category, subject.trim(), message.trim(), deviceInfo)
                )
            ) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            supportTickets = listOf(res.data) + it.supportTickets,
                            statusBannerMessage = "Support ticket submitted (#${res.data.id.take(8)})",
                            isErrorBanner = false
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(statusBannerMessage = res.message, isErrorBanner = true)
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun startCloudGameSession(
        game: GameItem,
        durationHours: Int,
        preset: StreamQualityPreset,
        codec: VideoCodecPreference,
        onSessionStarted: () -> Unit
    ) {
        val currentBalance = _uiState.value.wallet?.balance ?: _uiState.value.user?.coinBalance ?: 0
        if (!CoinPricingCalculator.canAfford(currentBalance, durationHours)) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Insufficient coins. Need ${CoinPricingCalculator.coinsRequiredForHours(durationHours)} coins.",
                    isErrorBanner = true
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isStartingSession = true) }
            val request = StartSessionRequest(
                gameId = game.id,
                durationHours = durationHours,
                resolution = preset.resolution,
                targetFps = preset.fps,
                codec = codec.wireValue
            )
            when (val res = repository.startSession(request)) {
                is Resource.Success -> {
                    val session = res.data
                    _uiState.update {
                        it.copy(
                            isStartingSession = false,
                            activeSession = session,
                            remainingSessionSeconds = session.durationMinutes * 60,
                            sessionWarningBanner = null,
                            user = it.user?.copy(coinBalance = session.newBalance)
                        )
                    }
                    webRtcManager.startStream(
                        sessionId = session.sessionId,
                        signalingWsUrl = session.signalingWsUrl,
                        streamToken = session.streamToken,
                        iceServers = session.iceServers,
                        codec = codec,
                        targetResolution = preset.resolution,
                        targetFps = preset.fps
                    )
                    startSessionCountdown(session.durationMinutes * 60)
                    onSessionStarted()
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isStartingSession = false,
                            statusBannerMessage = res.message,
                            isErrorBanner = true
                        )
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun endActiveSession(onFinished: () -> Unit) {
        val sessionId = _uiState.value.activeSession?.sessionId ?: run {
            onFinished()
            return
        }
        viewModelScope.launch {
            countdownJob?.cancel()
            webRtcManager.stopStream()
            when (val res = repository.endSession(sessionId)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            activeSession = null,
                            remainingSessionSeconds = 0,
                            sessionWarningBanner = null,
                            user = it.user?.copy(coinBalance = res.data.newBalance),
                            statusBannerMessage = "Session ended • Charged ${res.data.coinsCharged} coins (${res.data.minutesUsed} min)",
                            isErrorBanner = false
                        )
                    }
                    refreshWallet()
                    refreshHistory()
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            activeSession = null,
                            remainingSessionSeconds = 0,
                            statusBannerMessage = res.message,
                            isErrorBanner = true
                        )
                    }
                }
                Resource.Loading -> Unit
            }
            onFinished()
        }
    }

    private fun startSessionCountdown(totalSeconds: Int) {
        countdownJob?.cancel()
        notifiedFiveMinutes = false
        notifiedOneMinute = false
        countdownJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining--
                _uiState.update { it.copy(remainingSessionSeconds = remaining) }
                if (remaining == 300 && !notifiedFiveMinutes) {
                    notifiedFiveMinutes = true
                    _uiState.update {
                        it.copy(sessionWarningBanner = "5 minutes remaining in your reserved coin session.")
                    }
                    postExpirationNotification("5 Minutes Remaining", "Your cloud gaming session will expire in 5 minutes.")
                } else if (remaining == 60 && !notifiedOneMinute) {
                    notifiedOneMinute = true
                    _uiState.update {
                        it.copy(sessionWarningBanner = "1 minute remaining! Save your progress now.")
                    }
                    postExpirationNotification("1 Minute Remaining", "Save your game! Session ends in 60 seconds.")
                }
            }
            if (remaining <= 0 && _uiState.value.activeSession != null) {
                postExpirationNotification("Session Expired", "Reserved coin time completed. Session closed.")
                endActiveSession {}
            }
        }
    }

    private fun postExpirationNotification(title: String, body: String) {
        runCatching {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(app, ShubhankarCloudApp.SESSION_ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(title.hashCode(), notification)
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            preferences.updateServerUrl(url.trim())
            val health = repository.checkServerHealth()
            _uiState.update {
                it.copy(
                    serverReachable = health is Resource.Success,
                    serverHealth = (health as? Resource.Success)?.data,
                    statusBannerMessage = if (health is Resource.Success) {
                        "Connected to Shubhankar Cloud Backend"
                    } else {
                        "Server URL saved, but backend did not respond yet."
                    },
                    isErrorBanner = health !is Resource.Success
                )
            }
        }
    }

    fun updateQualityPreset(preset: StreamQualityPreset) {
        viewModelScope.launch { preferences.updateQualityPreset(preset) }
    }

    fun updateCodec(codec: VideoCodecPreference) {
        viewModelScope.launch { preferences.updateCodecPreference(codec) }
    }

    fun updateLowMemoryMode(enabled: Boolean) {
        viewModelScope.launch { preferences.updateLowMemoryMode(enabled) }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { preferences.updateHapticFeedback(enabled) }
    }

    fun updateShowTelemetry(enabled: Boolean) {
        viewModelScope.launch { preferences.updateShowTelemetry(enabled) }
    }

    fun updateMuteAudio(muted: Boolean) {
        viewModelScope.launch { preferences.updateMuteAudio(muted) }
    }

    fun saveTouchControlPosition(controlId: String, xFraction: Float, yFraction: Float) {
        viewModelScope.launch {
            val current = touchPlacements.value.ifEmpty { defaultTouchPlacements() }
            val updated = current.map { item ->
                if (item.controlId == controlId) {
                    item.copy(
                        xFraction = xFraction.coerceIn(0.05f, 0.95f),
                        yFraction = yFraction.coerceIn(0.10f, 0.90f)
                    )
                } else item
            }
            repository.saveTouchPlacements(updated)
        }
    }

    fun resetTouchControls() {
        viewModelScope.launch {
            repository.saveTouchPlacements(defaultTouchPlacements())
        }
    }

    companion object {
        fun defaultTouchPlacements(): List<TouchControlPlacementEntity> = listOf(
            TouchControlPlacementEntity("left_stick", "Move", 0.14f, 0.68f),
            TouchControlPlacementEntity("dpad_up", "W", 0.14f, 0.36f),
            TouchControlPlacementEntity("btn_a", "Jump", 0.87f, 0.74f),
            TouchControlPlacementEntity("btn_x", "Atk", 0.78f, 0.62f),
            TouchControlPlacementEntity("btn_y", "Act", 0.87f, 0.50f),
            TouchControlPlacementEntity("btn_b", "Back", 0.93f, 0.62f),
            TouchControlPlacementEntity("btn_lb", "LB", 0.10f, 0.18f),
            TouchControlPlacementEntity("btn_rb", "RB", 0.90f, 0.18f)
        )
    }
}
