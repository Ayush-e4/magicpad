package app.magicpad

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the phone is on any Wi-Fi network, as Compose state. [onRegained] runs (main
 * thread) when Wi-Fi comes back after being gone, so discovery can start over.
 */
class WifiWatcher(
    context: Context,
    private val onRegained: () -> Unit,
) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val networks = HashSet<Network>()
    private var registered = false

    var connected: Boolean by mutableStateOf(true)
        private set

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    networks += network
                    if (!connected) {
                        connected = true
                        onRegained()
                    }
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    networks -= network
                    if (networks.isEmpty()) connected = false
                }
            }
        }

    fun start() {
        if (registered) return
        networks.clear()
        connected = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // Also reports the Wi-Fi networks that are up right now.
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        cm.unregisterNetworkCallback(callback)
        registered = false
    }
}
