package com.phantom.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phantom.net.MdnsResponder
import java.net.Inet4Address

/** A Loungo TV found on the local network. */
data class FoundTv(val service: String, val name: String, val host: String, val port: Int)

/**
 * Browses for `_loungo._tcp` (only Loungo TVs announce it) and resolves each hit to an IPv4 address.
 * Resolves run one at a time (older NsdManager rejects parallel resolves). Callbacks on the main thread.
 * Runs only while the phone screen is visible: [start] in onStart, [stop] in onStop.
 */
class TvFinder(
    context: Context,
    private val onFound: (FoundTv) -> Unit,
    private val onLost: (String) -> Unit
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) { Log.i(TAG, "discovery started") }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery failed code=$errorCode")
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                main.post { onLost(name) }
            }
        }
        listener = l
        try {
            nsd.discoverServices(MdnsResponder.LOUNGO_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discover error: ${e.message}")
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        queue.clear()
        try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION") // resolveService is the API available on Android 12 (minSdk 31)
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "resolve failed ${si?.serviceName} code=$errorCode")
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val addr = si.host
                    val tv = if (addr is Inet4Address) FoundTv(
                        service = si.serviceName,
                        name = si.attributes["n"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                            ?: si.serviceName,
                        host = addr.hostAddress ?: "",
                        port = si.port
                    ) else null
                    main.post {
                        resolving = false
                        if (tv != null && tv.host.isNotEmpty() && listener != null) onFound(tv)
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolve error: ${e.message}")
            resolving = false
        }
    }

    companion object { private const val TAG = "LOUNGO_REMOTE" }
}
