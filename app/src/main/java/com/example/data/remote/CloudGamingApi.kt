package com.example.data.remote

import com.example.data.local.PreferencesManager
import com.example.data.model.ApiResponse
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
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface CloudGamingApi {

    @GET("health")
    suspend fun getHealth(): ApiResponse<HealthCheckResponse>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<AuthPayload>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<AuthPayload>

    @POST("auth/google")
    suspend fun googleSignIn(@Body body: GoogleAuthRequest): ApiResponse<AuthPayload>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): ApiResponse<AuthPayload>

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): ApiResponse<Unit>

    @GET("auth/me")
    suspend fun getProfile(): ApiResponse<UserProfile>

    @GET("games")
    suspend fun getGames(
        @Query("search") search: String? = null,
        @Query("category") category: String? = null
    ): ApiResponse<List<GameItem>>

    @GET("games/{id}")
    suspend fun getGameDetails(@Path("id") gameId: String): ApiResponse<GameItem>

    @GET("wallet")
    suspend fun getWalletSummary(): ApiResponse<WalletSummary>

    @POST("wallet/daily-reward")
    suspend fun claimDailyReward(): ApiResponse<DailyClaimResult>

    @POST("sessions/start")
    suspend fun startSession(@Body body: StartSessionRequest): ApiResponse<StartSessionResponse>

    @POST("sessions/{id}/end")
    suspend fun endSession(@Path("id") sessionId: String): ApiResponse<EndSessionResponse>

    @GET("sessions/history")
    suspend fun getSessionHistory(): ApiResponse<List<SessionHistoryItem>>

    @GET("servers/status")
    suspend fun getGpuServers(): ApiResponse<List<GpuNodeStatus>>

    @GET("support/tickets")
    suspend fun getSupportTickets(): ApiResponse<List<SupportTicketItem>>

    @POST("support/tickets")
    suspend fun createSupportTicket(@Body body: SupportTicketRequest): ApiResponse<SupportTicketItem>
}

class AuthInterceptor(
    private val preferencesManager: PreferencesManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/register") ||
            path.endsWith("/auth/google") || path.endsWith("/auth/refresh") ||
            path.endsWith("/health")
        ) {
            return chain.proceed(original)
        }
        val token = runBlocking { preferencesManager.accessTokenFlow.first() }
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}

class TokenAuthenticator(
    private val preferencesManager: PreferencesManager,
    private val refreshApiProvider: () -> CloudGamingApi
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val refreshToken = runBlocking { preferencesManager.refreshTokenFlow.first() } ?: return null
        return runBlocking {
            val result = runCatching {
                refreshApiProvider().refreshToken(RefreshRequest(refreshToken))
            }.getOrNull()
            val payload = result?.data
            if (result?.success == true && payload != null) {
                preferencesManager.saveTokens(
                    accessToken = payload.accessToken,
                    refreshToken = payload.refreshToken,
                    userId = payload.user.id
                )
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${payload.accessToken}")
                    .build()
            } else {
                preferencesManager.clearSession()
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}

object ApiClientFactory {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun createOkHttpClient(
        preferencesManager: PreferencesManager,
        baseUrlProvider: () -> String
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(preferencesManager))
            .addInterceptor(logging)
            .authenticator(
                TokenAuthenticator(preferencesManager) {
                    createUnauthenticatedApi(baseUrlProvider())
                }
            )
            .build()
    }

    private fun createUnauthenticatedApi(baseUrl: String): CloudGamingApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudGamingApi::class.java)
    }

    fun createApi(baseUrl: String, client: OkHttpClient): CloudGamingApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudGamingApi::class.java)
    }
}
