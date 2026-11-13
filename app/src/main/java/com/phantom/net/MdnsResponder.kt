package com.phantom.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

class MdnsResponder(
    private val context: Context,
    val hostname: String,
    val ip: String,
    val port: Int
) {
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListeners: List<NsdManager.RegistrationListener> = emptyList()
    @Volatile private var isRunning = false
    private var workerThread: Thread? = null
    private val hostnames: List<String> = MdnsHelper.allHostnames(hostname)

    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. Android NsdManager service registration: generic _http._tcp (legacy) and
        //    _loungo._tcp, which the Android phone remote browses for (no printers/routers in its list).
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceName = hostname.substringBefore(".local")
            registrationListeners = listOf("_http._tcp.", LOUNGO_SERVICE_TYPE).mapNotNull { type ->
                register(serviceName, type)
            }
        } catch (e: Exception) {
            Log.w("PHANTOM", "NSD register error: ${e.message}")
        }

        // 2. Multicast Lock & UDP 5353 A-record responder
        workerThread = thread(name = "Phantom-mDNS", isDaemon = true) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("phantom_mdns")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

                val group = InetAddress.getByName("224.0.0.251")
                val s = MulticastSocket(5353).apply {
                    reuseAddress = true
                    timeToLive = 255
                    try {
                        joinGroup(group)
                    } catch (e: Exception) {
                        Log.w("PHANTOM", "mDNS joinGroup warning: ${e.message}")
                    }
                }
                socket = s
                Log.i("PHANTOM", "MDNS_UP host=$hostname ip=$ip port=$port")

                val buf = ByteArray(1500)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val data = packet.data
                    val len = packet.length

                    // Answers the current name and the legacy phantom-XXXX.local name.
                    val asked = answeredNames(data, len, hostnames)
                    if (asked.isNotEmpty()) {
                        // Current address at answer time: it can change (DHCP, VPN up/down).
                        val now = com.phantom.pairing.PairingHelper.getLocalIpAddress(context).takeIf { it != "127.0.0.1" } ?: ip
                        for (name in asked) {
                            Log.i("PHANTOM", "MDNS_QUERY $name -> $now")
                            val responseBytes = buildARecordResponse(name, now)
                            val outPacket = DatagramPacket(responseBytes, responseBytes.size, group, 5353)
                            s.send(outPacket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w("PHANTOM", "mDNS responder error: ${e.message}")
                }
            } finally {
                cleanup()
            }
        }
    }

    private fun register(serviceName: String, type: String): NsdManager.RegistrationListener? {
        val nsd = nsdManager ?: return null
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = type
            this.port = this@MdnsResponder.port
            // Human-readable TV name for the phone's TV list ("Living room TV").
            tvDisplayName()?.let { setAttribute("n", it) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo?) {
                Log.i("PHANTOM", "NSD_REGISTERED ${registeredInfo?.serviceName}.$type port=${this@MdnsResponder.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w("PHANTOM", "NSD_FAILED type=$type code=$errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    private fun tvDisplayName(): String? = try {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            registrationListeners.forEach { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        registrationListeners = emptyList()
        cleanup()
    }

    private fun cleanup() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    companion object {
        /** DNS-SD type only Loungo TVs announce; the phone remote browses for it. */
        const val LOUNGO_SERVICE_TYPE = "_loungo._tcp."

        /** Subset of [names] that the query asks for (A or ANY), in the order given. */
        fun answeredNames(data: ByteArray, length: Int, names: List<String>): List<String> =
            names.filter { shouldAnswerQuery(data, length, it) }

        fun shouldAnswerQuery(data: ByteArray, length: Int, targetHostname: String): Boolean {
            if (length < 12) return false
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if ((flags and 0x8000) != 0) return false

            val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            if (qdCount <= 0) return false

            var offset = 12
            val cleanTarget = targetHostname.trim().lowercase().trimEnd('.')
            for (q in 0 until qdCount) {
                val (qname, nextOffset) = parseDnsName(data, offset, length)
                if (nextOffset < 0 || nextOffset + 4 > length) break
                val qtype = ((data[nextOffset].toInt() and 0xFF) shl 8) or (data[nextOffset + 1].toInt() and 0xFF)
                if ((qtype == 1 || qtype == 255) && qname.equals(cleanTarget, ignoreCase = true)) {
                    return true
                }
                offset = nextOffset + 4
            }
            return false
        }

        fun parseDnsName(data: ByteArray, startOffset: Int, maxLen: Int): Pair<String, Int> {
            val parts = mutableListOf<String>()
            var offset = startOffset
            var jumped = false
            var finalOffset = -1

            while (offset < maxLen) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) {
                    offset++
                    if (!jumped) finalOffset = offset
                    break
                }
                if ((len and 0xC0) == 0xC0) {
                    if (offset + 1 >= maxLen) return "" to -1
                    val ptr = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (!jumped) finalOffset = offset + 2
                    jumped = true
                    offset = ptr
                    continue
                }
                offset++
                if (offset + len > maxLen) return "" to -1
                val label = String(data, offset, len, Charsets.UTF_8)
                parts.add(label)
                offset += len
            }
            return parts.joinToString(".") to (if (jumped) finalOffset else offset)
        }

        fun buildARecordResponse(targetHostname: String, ip: String): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            dos.writeShort(0x0000)
            dos.writeShort(0x8400)
            dos.writeShort(0x0000)
            dos.writeShort(0x0001)
            dos.writeShort(0x0000)
            dos.writeShort(0x0000)

            val cleanHost = targetHostname.trim().trimEnd('.')
            for (label in cleanHost.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)

            dos.writeShort(0x0001)
            dos.writeShort(0x8001)
            dos.writeInt(120)
            dos.writeShort(4)

            val ipBytes = InetAddress.getByName(ip).address
            dos.write(ipBytes)
            dos.flush()

            return baos.toByteArray()
        }
    }
}
