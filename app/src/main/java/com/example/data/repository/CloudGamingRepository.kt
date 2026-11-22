package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.CachedGameEntity
import com.example.data.local.PreferencesManager
import com.example.data.local.TouchControlPlacementEntity
import com.example.data.model.AuthPayload
import com.example.data.model.DailyClaimResult
import com.example.data.model.EndSessionResponse
import com.example.data.model.GameItem
import com.example.data.model.GoogleAuthRequest
import com.example.data.model.GpuNodeStatus
import com.example.data.model.HealthCheckResponse
import com.example.data.model.LoginRequest
import com.example.data.model.RefreshRequest
import com.example.data.model.RegisterRequest
import com.example.data.model.SessionHistoryItem
import com.example.data.model.StartSessionRequest
import com.example.data.model.StartSessionResponse
import com.example.data.model.SupportTicketItem
import com.example.data.model.SupportTicketRequest
import com.example.data.model.UserProfile
import com.example.data.model.WalletSummary
import com.example.data.remote.ApiClientFactory
import com.example.data.remote.CloudGamingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException

sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val isNetworkError: Boolean = false) : Resource<Nothing>()
}

class CloudGamingRepository(
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase
) {
    private var currentBaseUrl: String = ""
    private var cachedOkHttp: OkHttpClient? = null
    private var cachedApi: CloudGamingApi? = null

    private suspend fun api(): CloudGamingApi {
        val url = preferencesManager.settingsFlow.first().serverBaseUrl
        val existing = cachedApi
        if (existing != null && currentBaseUrl == url) {
            return existing
        }
        val client = cachedOkHttp ?: ApiClientFactory.createOkHttpClient(preferencesManager) {
            currentBaseUrl
        }.also { cachedOkHttp = it }
        currentBaseUrl = url
        return ApiClientFactory.createApi(url, client).also { cachedApi = it }
    }

    fun getOkHttpClient(): OkHttpClient {
        return cachedOkHttp ?: ApiClientFactory.createOkHttpClient(preferencesManager) {
            currentBaseUrl
        }.also { cachedOkHttp = it }
    }

    val cachedGamesFlow: Flow<List<GameItem>> = database.gameCacheDao().observeGames().map { entities ->
        entities.map { entity ->
            GameItem(
                id = entity.id,
                slug = entity.slug,
                title = entity.title,
                publisher = entity.publisher,
                category = entity.category,
                description = entity.description,
                coverImageUrl = entity.coverImageUrl,
                screenshots = if (entity.screenshotsJoined.isBlank()) emptyList() else entity.screenshotsJoined.split("|"),
                isLicensed = entity.isLicensed,
                isAvailable = entity.isAvailable,
                maxResolution = entity.maxResolution,
                maxFps = entity.maxFps,
                minGpuTier = entity.minGpuTier,
                supportsGamepad = entity.supportsGamepad,
                supportsKbm = entity.supportsKbm,
                installedNodesCount = entity.installedNodesCount
            )
        }
    }

    val touchLayoutsFlow: Flow<List<TouchControlPlacementEntity>> =
        database.touchControlDao().observePlacements()

    suspend fun saveTouchPlacements(placements: List<TouchControlPlacementEntity>) {
        database.touchControlDao().upsertPlacements(placements)
    }

    suspend fun resetTouchPlacements() {
        database.touchControlDao().resetPlacements()
    }

    suspend fun checkServerHealth(): Resource<HealthCheckResponse> = safeCall {
        api().getHealth()
    }

    suspend fun register(username: String, email: String, password: String): Resource<AuthPayload> {
        val res = safeCall { api().register(RegisterRequest(username.trim(), email.trim(), password)) }
        if (res is Resource.Success) {
            preferencesManager.saveTokens(res.data.accessToken, res.data.refreshToken, res.data.user.id)
        }
        return res
    }

    suspend fun login(email: String, password: String): Resource<AuthPayload> {
        val res = safeCall { api().login(LoginRequest(email.trim(), password)) }
        if (res is Resource.Success) {
            preferencesManager.saveTokens(res.data.accessToken, res.data.refreshToken, res.data.user.id)
        }
        return res
    }

    suspend fun loginWithGoogle(idToken: String): Resource<AuthPayload> {
        val res = safeCall { api().googleSignIn(GoogleAuthRequest(idToken)) }
        if (res is Resource.Success) {
            preferencesManager.saveTokens(res.data.accessToken, res.data.refreshToken, res.data.user.id)
        }
        return res
    }

    suspend fun logout() {
        val refresh = preferencesManager.refreshTokenFlow.first()
        if (!refresh.isNullOrBlank()) {
            runCatching { api().logout(RefreshRequest(refresh)) }
        }
        preferencesManager.clearSession()
    }

    suspend fun fetchProfile(): Resource<UserProfile> = safeCall { api().getProfile() }

    suspend fun syncGames(search: String? = null, category: String? = null): Resource<List<GameItem>> {
        val result = safeCall { api().getGames(search = search, category = category) }
        if (result is Resource.Success && search.isNullOrBlank() && category.isNullOrBlank()) {
            database.gameCacheDao().upsertGames(
                result.data.map { game ->
                    CachedGameEntity(
                        id = game.id,
                        slug = game.slug,
                        title = game.title,
                        publisher = game.publisher,
                        category = game.category,
                        description = game.description,
                        coverImageUrl = game.coverImageUrl,
                        screenshotsJoined = game.screenshots.joinToString("|"),
                        isLicensed = game.isLicensed,
                        isAvailable = game.isAvailable,
                        maxResolution = game.maxResolution,
                        maxFps = game.maxFps,
                        minGpuTier = game.minGpuTier,
                        supportsGamepad = game.supportsGamepad,
                        supportsKbm = game.supportsKbm,
                        installedNodesCount = game.installedNodesCount
                    )
                }
            )
        }
        return result
    }

    suspend fun fetchGameDetails(gameId: String): Resource<GameItem> = safeCall {
        api().getGameDetails(gameId)
    }

    suspend fun fetchWalletSummary(): Resource<WalletSummary> = safeCall {
        api().getWalletSummary()
    }

    suspend fun claimDailyReward(): Resource<DailyClaimResult> = safeCall {
        api().claimDailyReward()
    }

    suspend fun startSession(request: StartSessionRequest): Resource<StartSessionResponse> = safeCall {
        api().startSession(request)
    }

    suspend fun endSession(sessionId: String): Resource<EndSessionResponse> = safeCall {
        api().endSession(sessionId)
    }

    suspend fun fetchSessionHistory(): Resource<List<SessionHistoryItem>> = safeCall {
        api().getSessionHistory()
    }

    suspend fun fetchGpuServers(): Resource<List<GpuNodeStatus>> = safeCall {
        api().getGpuServers()
    }

    suspend fun fetchSupportTickets(): Resource<List<SupportTicketItem>> = safeCall {
        api().getSupportTickets()
    }

    suspend fun submitSupportTicket(request: SupportTicketRequest): Resource<SupportTicketItem> = safeCall {
        api().createSupportTicket(request)
    }

    private suspend fun <T> safeCall(
        call: suspend () -> com.example.data.model.ApiResponse<T>
    ): Resource<T> {
        return try {
            val response = call()
            val payload = response.data
            if (response.success && payload != null) {
                Resource.Success(payload)
            } else {
                Resource.Error(response.error ?: response.message ?: "Request rejected by server")
            }
        } catch (e: HttpException) {
            val errorMsg = when (e.code()) {
                401 -> "Session expired. Please sign in again."
                402 -> "Insufficient coins for this session."
                403 -> "Access denied or license restriction."
                409 -> "Conflict: reward already claimed or active session exists."
                429 -> "Rate limit reached. Please wait a moment."
                503 -> "Platform is under scheduled maintenance or all GPU nodes are busy."
                else -> "Server error (${e.code()})"
            }
            Resource.Error(errorMsg)
        } catch (e: IOException) {
            Resource.Error(
                "Unable to reach cloud backend. Configure Server URL in Settings.",
                isNetworkError = true
            )
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Unexpected network error")
        }
    }
}
