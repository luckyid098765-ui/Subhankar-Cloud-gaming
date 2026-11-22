package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class AuthPayload(
    val user: UserProfile,
    val accessToken: String,
    val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class GoogleAuthRequest(
    val idToken: String
)

@JsonClass(generateAdapter = true)
data class RefreshRequest(
    val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class UserProfile(
    val id: String,
    val username: String,
    val email: String,
    val role: String = "user",
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    @Json(name = "coin_balance") val coinBalance: Int = 0,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class GameItem(
    val id: String,
    val slug: String,
    val title: String,
    val publisher: String,
    val category: String,
    val description: String,
    @Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @Json(name = "screenshots") val screenshots: List<String> = emptyList(),
    @Json(name = "is_licensed") val isLicensed: Boolean = true,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "max_resolution") val maxResolution: String = "1080p",
    @Json(name = "max_fps") val maxFps: Int = 120,
    @Json(name = "min_gpu_tier") val minGpuTier: String = "RTX 3060",
    @Json(name = "supports_gamepad") val supportsGamepad: Boolean = true,
    @Json(name = "supports_kbm") val supportsKbm: Boolean = true,
    @Json(name = "installed_nodes_count") val installedNodesCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class WalletSummary(
    val balance: Int,
    @Json(name = "hourly_rate_coins") val hourlyRateCoins: Int = 10,
    @Json(name = "daily_reward_coins") val dailyRewardCoins: Int = 20,
    @Json(name = "daily_reward_claimed_today") val dailyRewardClaimedToday: Boolean = false,
    @Json(name = "next_claim_utc") val nextClaimUtc: String? = null,
    val transactions: List<CoinTransaction> = emptyList()
)

@JsonClass(generateAdapter = true)
data class DailyClaimResult(
    val claimed: Boolean,
    @Json(name = "coins_credited") val coinsCredited: Int,
    @Json(name = "new_balance") val newBalance: Int,
    val message: String
)

@JsonClass(generateAdapter = true)
data class CoinTransaction(
    val id: String,
    val amount: Int,
    val type: String,
    val description: String,
    @Json(name = "balance_after") val balanceAfter: Int,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class StartSessionRequest(
    @Json(name = "game_id") val gameId: String,
    @Json(name = "duration_hours") val durationHours: Int,
    val resolution: String,
    @Json(name = "target_fps") val targetFps: Int,
    val codec: String
)

@JsonClass(generateAdapter = true)
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@JsonClass(generateAdapter = true)
data class StartSessionResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "game_id") val gameId: String,
    @Json(name = "game_title") val gameTitle: String,
    @Json(name = "server_id") val serverId: String,
    @Json(name = "server_name") val serverName: String,
    @Json(name = "server_region") val serverRegion: String,
    @Json(name = "gpu_model") val gpuModel: String,
    @Json(name = "signaling_ws_url") val signalingWsUrl: String,
    @Json(name = "stream_token") val streamToken: String,
    @Json(name = "ice_servers") val iceServers: List<IceServerConfig>,
    @Json(name = "coins_reserved") val coinsReserved: Int,
    @Json(name = "new_balance") val newBalance: Int,
    @Json(name = "duration_minutes") val durationMinutes: Int,
    @Json(name = "expires_at") val expiresAt: String
)

@JsonClass(generateAdapter = true)
data class EndSessionResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "minutes_used") val minutesUsed: Int,
    @Json(name = "coins_charged") val coinsCharged: Int,
    @Json(name = "coins_refunded") val coinsRefunded: Int,
    @Json(name = "new_balance") val newBalance: Int
)

@JsonClass(generateAdapter = true)
data class SessionHistoryItem(
    val id: String,
    @Json(name = "game_id") val gameId: String,
    @Json(name = "game_title") val gameTitle: String,
    @Json(name = "server_name") val serverName: String,
    @Json(name = "gpu_model") val gpuModel: String,
    val status: String,
    val resolution: String,
    @Json(name = "target_fps") val targetFps: Int,
    val codec: String,
    @Json(name = "coins_charged") val coinsCharged: Int,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "avg_latency_ms") val avgLatencyMs: Int? = null
)

@JsonClass(generateAdapter = true)
data class GpuNodeStatus(
    val id: String,
    val name: String,
    val region: String,
    @Json(name = "gpu_model") val gpuModel: String,
    val status: String,
    @Json(name = "gpu_utilization_pct") val gpuUtilizationPct: Int,
    @Json(name = "cpu_utilization_pct") val cpuUtilizationPct: Int,
    @Json(name = "ram_used_gb") val ramUsedGb: Float,
    @Json(name = "ram_total_gb") val ramTotalGb: Float,
    @Json(name = "bandwidth_mbps") val bandwidthMbps: Int,
    @Json(name = "active_sessions") val activeSessions: Int,
    @Json(name = "max_sessions") val maxSessions: Int
)

@JsonClass(generateAdapter = true)
data class SupportTicketRequest(
    val category: String,
    val subject: String,
    val message: String,
    @Json(name = "device_info") val deviceInfo: String
)

@JsonClass(generateAdapter = true)
data class SupportTicketItem(
    val id: String,
    val category: String,
    val subject: String,
    val message: String,
    val status: String,
    @Json(name = "admin_reply") val adminReply: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class HealthCheckResponse(
    val status: String,
    @Json(name = "maintenance_mode") val maintenanceMode: Boolean = false,
    @Json(name = "online_gpu_nodes") val onlineGpuNodes: Int = 0,
    val timestamp: String? = null
)

enum class StreamQualityPreset(
    val label: String,
    val resolution: String,
    val fps: Int,
    val maxBitrateKbps: Int,
    val description: String
) {
    AUTO("Adaptive Auto", "720p", 60, 8000, "Scales dynamically for Wi-Fi or 4G"),
    SAVER_720P30("720p • 30 FPS", "720p", 30, 4500, "Optimized for mobile data & Galaxy M20"),
    BALANCED_720P60("720p • 60 FPS", "720p", 60, 8000, "Low latency 60Hz on mobile & Wi-Fi"),
    HD_1080P60("1080p • 60 FPS", "1080p", 60, 15000, "Full HD crisp stream (Wi-Fi 5GHz)"),
    ULTRA_1080P120("1080p • 120 FPS", "1080p", 120, 25000, "Competitive high-refresh (5GHz / Fiber)")
}

enum class VideoCodecPreference(val wireValue: String, val label: String) {
    H265("H265", "H.265 (HEVC)"),
    H264("H264", "H.264 (AVC)")
}

data class StreamTelemetry(
    val connectionState: String = "Connecting",
    val rttMs: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val packetLossPct: Float = 0f,
    val activeCodec: String = "H264",
    val activeResolution: String = "720p",
    val isReconnecting: Boolean = false,
    val activeGamepadName: String? = null
)

object CoinPricingCalculator {
    const val COINS_PER_HOUR = 10
    const val DAILY_FREE_COINS = 20

    fun coinsRequiredForHours(hours: Int): Int = (hours.coerceAtLeast(1)) * COINS_PER_HOUR

    fun canAfford(balance: Int, hours: Int): Boolean =
        balance >= coinsRequiredForHours(hours) && hours >= 1

    fun minutesForCoins(coins: Int): Int = (coins.coerceAtLeast(0) * 60) / COINS_PER_HOUR
}
