package app.magicpad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/** A Mac advertising `_magicpad._tcp` on this network. */
data class MacService(
    val serviceName: String,
    val id: String,
    val host: InetAddress,
    val port: Int,
)

/**
 * Browses for Macs with NsdManager. Results arrive on the main thread via [onChange].
 * Resolves run one at a time, because older NsdManager versions refuse parallel ones.
 */
class Discovery(
    context: Context,
    private val onChange: (List<MacService>) -> Unit,
) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, MacService>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        // Forget Macs from before a stop: only what answers now counts for auto-connect.
        if (found.isNotEmpty()) {
            found.clear()
            onChange(emptyList())
        }
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    main.post { listener = null }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        pending.addLast(info)
                        resolveNext()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        if (found.remove(info.serviceName) != null) onChange(found.values.toList())
                    }
                }
            }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd.stopServiceDiscovery(l)
        } catch (_: IllegalArgumentException) {
            // Discovery never started.
        }
        pending.clear()
    }

    /** The latest address for the Mac with this id, if it is still advertising. */
    fun find(id: String): MacService? = found.values.firstOrNull { it.id == id }

    @Suppress("DEPRECATION") // resolveService is replaced on API 34+, but still works and covers API 26.
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        add(serviceInfo)
                        resolveNext()
                    }
                }
            },
        )
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host: hostAddresses needs API 34.
    private fun add(info: NsdServiceInfo) {
        val host = info.host ?: return
        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: info.serviceName
        found[info.serviceName] = MacService(info.serviceName, id, host, info.port)
        onChange(found.values.toList())
    }

    private companion object {
        const val SERVICE_TYPE = "_magicpad._tcp"
    }
}
