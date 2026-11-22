package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.data.remote.NetworkMonitor
import com.example.data.repository.CloudGamingRepository
import com.example.streaming.ControllerInputBus
import com.example.streaming.WebRtcStreamingManager

class ShubhankarCloudApp : Application(), ImageLoaderFactory {

    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var repository: CloudGamingRepository
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var controllerInputBus: ControllerInputBus
        private set
    lateinit var webRtcManager: WebRtcStreamingManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        database = AppDatabase.getInstance(this)
        repository = CloudGamingRepository(preferencesManager, database)
        networkMonitor = NetworkMonitor(this)
        controllerInputBus = ControllerInputBus()
        webRtcManager = WebRtcStreamingManager(
            context = this,
            okHttpClient = repository.getOkHttpClient(),
            inputBus = controllerInputBus
        )
        createNotificationChannel()
    }

    override fun newImageLoader(): ImageLoader {
        // Optimized memory cache for Samsung Galaxy M20 (3GB/4GB RAM) and Android 10+ devices
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_game_art_cache"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SESSION_ALERT_CHANNEL_ID,
                "Cloud Gaming Session Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for remaining gaming time and session expiration."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val SESSION_ALERT_CHANNEL_ID = "shubhankar_session_alerts"
    }
}
