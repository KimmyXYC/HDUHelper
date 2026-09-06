package moe.nepnep.hduhelper.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    val online: Flow<Boolean> = callbackFlow {
        fun usable(): Boolean {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(usable()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(usable()) }
            override fun onLost(network: Network) { trySend(usable()) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(usable())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
