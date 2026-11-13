package com.phantom.server

import java.security.SecureRandom
import java.util.Base64

/**
 * "Pair this phone?" requests from the Android phone remote (no QR there).
 * One pending request at a time; the TV user approves it with the remote.
 * Proof of presence is the same idea as the QR: the person holding the TV remote said yes.
 * Pure state, no Android: the server asks, the TV UI answers.
 */
class PairRequests(private val timeoutMs: Long = 30_000L) {

    data class Pending(val id: String, val ip: String, val name: String, val createdAt: Long)

    sealed class Status {
        object Waiting : Status()
        object Approved : Status()
        object Denied : Status()
        object Unknown : Status()
    }

    sealed class Start {
        data class Created(val req: Pending) : Start()
        data class Existing(val req: Pending) : Start()
        object Busy : Start()
    }

    private var pending: Pending? = null
    /** Final answers, kept briefly so the phone's next poll can read them. */
    private val decided = LinkedHashMap<String, Pair<Pending, Boolean>>()
    private val random = SecureRandom()

    @Synchronized
    fun request(ip: String, rawName: String?, now: Long = System.currentTimeMillis()): Start {
        expire(now)
        val p = pending
        if (p != null) return if (p.ip == ip) Start.Existing(p) else Start.Busy
        val req = Pending(newId(), ip, cleanName(rawName), now)
        pending = req
        return Start.Created(req)
    }

    /** Status for the phone. The id is bound to the IP that created it. */
    @Synchronized
    fun status(id: String?, ip: String, now: Long = System.currentTimeMillis()): Status {
        expire(now)
        if (id.isNullOrEmpty()) return Status.Unknown
        pending?.let { if (it.id == id && it.ip == ip) return Status.Waiting }
        val d = decided[id] ?: return Status.Unknown
        if (d.first.ip != ip) return Status.Unknown
        decided.remove(id) // an answer is read once
        return if (d.second) Status.Approved else Status.Denied
    }

    @Synchronized
    fun answer(id: String, approved: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        expire(now)
        val p = pending ?: return false
        if (p.id != id) return false
        pending = null
        remember(p, approved)
        return true
    }

    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): Pending? {
        expire(now)
        return pending
    }

    private fun expire(now: Long) {
        val p = pending ?: return
        if (now - p.createdAt >= timeoutMs) {
            pending = null
            remember(p, false)
        }
        decided.entries.removeAll { now - it.value.first.createdAt > timeoutMs * 4 }
    }

    private fun remember(p: Pending, approved: Boolean) {
        decided[p.id] = p to approved
        while (decided.size > 8) decided.remove(decided.keys.first())
    }

    private fun newId(): String {
        val b = ByteArray(12)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        /** Shown on the TV, so keep it short and printable. */
        fun cleanName(raw: String?): String {
            val s = raw.orEmpty().filter { !it.isISOControl() }.trim().take(40)
            return s.ifEmpty { "Android" }
        }
    }
}
