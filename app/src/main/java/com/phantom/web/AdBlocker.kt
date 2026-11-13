package com.phantom.web

import android.os.SystemClock
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class AdBlocker(hashes: LongArray? = null) : Blocklist {

    @Volatile
    private var activeHashes: LongArray? = hashes

    @Volatile
    private var extraHashes: LongArray? = null

    fun loadExtraFromStream(stream: InputStream) {
        try {
            val domainSet = HashSet<String>()
            stream.bufferedReader().forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("\\s+".toRegex())
                    val d = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]).lowercase(Locale.ROOT).trim()
                    if (d.isNotEmpty() && !IGNORED_HOSTS.contains(d) && !d.startsWith("#")) {
                        domainSet.add(d)
                    }
                }
            }
            val arr = LongArray(domainSet.size)
            var i = 0
            for (d in domainSet) {
                arr[i++] = fnv1a64(d)
            }
            arr.sort()
            extraHashes = arr
            safeLogI("PHANTOM", "ADBLOCK_EXTRA_LOADED n=${arr.size}")
        } catch (e: Exception) {
            safeLogE("PHANTOM", "Failed to load adblock_extra", e)
        }
    }

    private fun safeLogI(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: Throwable) {}
    }

    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {}
    }

    fun loadFromStreamAsync(stream: InputStream, onLoaded: (() -> Unit)? = null) {
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val bytes = stream.readBytes()
                val loaded = parseBinary(bytes)
                activeHashes = loaded
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i("PHANTOM", "ADBLOCK_LOADED n=${loaded.size} ms=$ms")
                onLoaded?.invoke()
            } catch (e: Exception) {
                Log.e("PHANTOM", "Failed to load adblock.bin", e)
            }
        }.start()
    }

    fun isLoaded(): Boolean = activeHashes != null

    fun updateHashes(hashes: LongArray) {
        activeHashes = hashes
    }

    override fun isBlocked(host: String): Boolean {
        val table = activeHashes
        val extra = extraHashes
        if (table == null && extra == null) return false
        if (host.isEmpty()) return false
        val cleanHost = host.trim().lowercase(Locale.ROOT)
        if (cleanHost.isEmpty()) return false

        var current = cleanHost
        while (true) {
            val h = fnv1a64(current)
            if ((table != null && table.binarySearch(h) >= 0) || (extra != null && extra.binarySearch(h) >= 0)) {
                return true
            }
            val dotIdx = current.indexOf('.')
            if (dotIdx < 0) {
                break
            }
            val next = current.substring(dotIdx + 1)
            // Stop if next has no dot (TLD alone, e.g. "com", "net")
            if (!next.contains('.')) {
                break
            }
            current = next
        }
        return false
    }

    companion object {
        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325UL.toLong()
        const val FNV_PRIME: Long = 1099511628211L // 0x100000001b3L

        fun fnv1a64(str: String): Long {
            val bytes = str.toByteArray(Charsets.UTF_8)
            var hash = FNV_OFFSET_BASIS
            for (b in bytes) {
                hash = (hash xor (b.toLong() and 0xFFL)) * FNV_PRIME
            }
            return hash
        }

        val IGNORED_HOSTS = setOf(
            "localhost",
            "localhost.localdomain",
            "local",
            "broadcasthost",
            "0.0.0.0",
            "127.0.0.1",
            "::1",
            "ip6-localhost",
            "ip6-loopback"
        )

        fun parseHosts(reader: java.io.BufferedReader): LongArray {
            val domainSet = HashSet<String>()
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                        val d = parts[1].lowercase(Locale.ROOT).trim()
                        if (d.isNotEmpty() && !IGNORED_HOSTS.contains(d) && !d.startsWith("#")) {
                            domainSet.add(d)
                        }
                    }
                }
            }
            val hashes = LongArray(domainSet.size)
            var i = 0
            for (d in domainSet) {
                hashes[i++] = fnv1a64(d)
            }
            hashes.sort()
            return hashes
        }

        fun writeBinary(hashes: LongArray, outputStream: java.io.OutputStream) {
            val buf = ByteBuffer.allocate(8 + hashes.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("PBL1".toByteArray(Charsets.US_ASCII))
            buf.putInt(hashes.size)
            for (h in hashes) {
                buf.putLong(h)
            }
            outputStream.write(buf.array())
        }

        fun parseBinary(bytes: ByteArray): LongArray {
            if (bytes.size < 8) return LongArray(0)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            val magicStr = String(magic, Charsets.US_ASCII)
            if (magicStr != "PBL1") {
                throw IllegalArgumentException("Invalid adblock magic: $magicStr")
            }
            val count = buf.int
            if (count < 0 || count * 8 > bytes.size - 8) {
                throw IllegalArgumentException("Invalid count: $count")
            }
            val arr = LongArray(count)
            buf.asLongBuffer().get(arr)
            return arr
        }
    }
}
