package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OFFLINE
}

data class NetworkStatus(
    val transport: NetworkTransport = NetworkTransport.OFFLINE,
    val downstreamKbps: Int = 0,
    val isMetered: Boolean = false
) {
    val isConnected: Boolean get() = transport != NetworkTransport.OFFLINE
    val recommendedHint: String
        get() = when {
            transport == NetworkTransport.OFFLINE -> "Offline"
            transport == NetworkTransport.CELLULAR -> "Mobile Data • Recommend 720p 30/60 FPS"
            downstreamKbps >= 25_000 -> "High-Speed Wi-Fi • Ready for 1080p 120 FPS"
            else -> "Wi-Fi • Ready for 720p/1080p 60 FPS"
        }
}

class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(readCurrentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _status.value = readCurrentStatus()
        }

        override fun onLost(network: Network) {
            _status.value = readCurrentStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _status.value = extractStatus(capabilities)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
    }

    private fun readCurrentStatus(): NetworkStatus {
        val active = connectivityManager.activeNetwork ?: return NetworkStatus(NetworkTransport.OFFLINE)
        val caps = connectivityManager.getNetworkCapabilities(active)
            ?: return NetworkStatus(NetworkTransport.OFFLINE)
        return extractStatus(caps)
    }

    private fun extractStatus(caps: NetworkCapabilities): NetworkStatus {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.OFFLINE
        }
        return NetworkStatus(
            transport = transport,
            downstreamKbps = caps.linkDownstreamBandwidthKbps,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
