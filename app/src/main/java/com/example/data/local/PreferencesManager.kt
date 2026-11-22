package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.model.StreamQualityPreset
import com.example.data.model.VideoCodecPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shubhankar_prefs")

data class UserSettings(
    val serverBaseUrl: String = BuildConfig.DEFAULT_API_URL,
    val qualityPreset: StreamQualityPreset = StreamQualityPreset.AUTO,
    val codecPreference: VideoCodecPreference = VideoCodecPreference.H265,
    val lowMemoryMode: Boolean = false,
    val hapticFeedback: Boolean = true,
    val showTelemetryOverlay: Boolean = true,
    val muteAudio: Boolean = false
)

class PreferencesManager(private val context: Context) {

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val QUALITY_PRESET = stringPreferencesKey("quality_preset")
        val CODEC_PREFERENCE = stringPreferencesKey("codec_preference")
        val LOW_MEMORY_MODE = booleanPreferencesKey("low_memory_mode")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SHOW_TELEMETRY = booleanPreferencesKey("show_telemetry")
        val MUTE_AUDIO = booleanPreferencesKey("mute_audio")
    }

    val accessTokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.ACCESS_TOKEN] }
    val refreshTokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.REFRESH_TOKEN] }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val defaultLowMem = Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L
        UserSettings(
            serverBaseUrl = prefs[Keys.SERVER_BASE_URL] ?: BuildConfig.DEFAULT_API_URL,
            qualityPreset = runCatching {
                StreamQualityPreset.valueOf(prefs[Keys.QUALITY_PRESET] ?: StreamQualityPreset.AUTO.name)
            }.getOrDefault(StreamQualityPreset.AUTO),
            codecPreference = runCatching {
                VideoCodecPreference.valueOf(prefs[Keys.CODEC_PREFERENCE] ?: VideoCodecPreference.H265.name)
            }.getOrDefault(VideoCodecPreference.H265),
            lowMemoryMode = prefs[Keys.LOW_MEMORY_MODE] ?: defaultLowMem,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            showTelemetryOverlay = prefs[Keys.SHOW_TELEMETRY] ?: true,
            muteAudio = prefs[Keys.MUTE_AUDIO] ?: false
        )
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, userId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
            prefs[Keys.USER_ID] = userId
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.USER_ID)
        }
    }

    suspend fun updateServerUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        context.dataStore.edit { it[Keys.SERVER_BASE_URL] = normalized }
    }

    suspend fun updateQualityPreset(preset: StreamQualityPreset) {
        context.dataStore.edit { it[Keys.QUALITY_PRESET] = preset.name }
    }

    suspend fun updateCodecPreference(codec: VideoCodecPreference) {
        context.dataStore.edit { it[Keys.CODEC_PREFERENCE] = codec.name }
    }

    suspend fun updateLowMemoryMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOW_MEMORY_MODE] = enabled }
    }

    suspend fun updateHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun updateShowTelemetry(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_TELEMETRY] = enabled }
    }

    suspend fun updateMuteAudio(muted: Boolean) {
        context.dataStore.edit { it[Keys.MUTE_AUDIO] = muted }
    }
}
