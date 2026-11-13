package com.phantom.server

class RateLimiter(
    val maxAttempts: Int = 10,
    val windowMs: Long = 60_000L,
    val banDurationMs: Long = 60_000L
) {
    private data class ClientState(
        val failedAttempts: MutableList<Long> = mutableListOf(),
        var bannedUntil: Long = 0L
    )

    private val clients = mutableMapOf<String, ClientState>()

    @Synchronized
    fun isBlocked(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val state = clients[ip] ?: return false
        if (now < state.bannedUntil) {
            return true
        }
        return false
    }

    @Synchronized
    fun recordFailure(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val state = clients.getOrPut(ip) { ClientState() }
        if (now < state.bannedUntil) {
            return true
        }

        // Remove timestamps older than windowMs
        state.failedAttempts.removeIf { now - it > windowMs }
        state.failedAttempts.add(now)

        if (state.failedAttempts.size > maxAttempts) {
            state.bannedUntil = now + banDurationMs
            return true
        }
        return false
    }

    @Synchronized
    fun recordSuccess(ip: String) {
        val state = clients[ip] ?: return
        val now = System.currentTimeMillis()
        if (now >= state.bannedUntil) {
            clients.remove(ip)
        }
    }

    @Synchronized
    fun getBannedUntil(ip: String): Long {
        return clients[ip]?.bannedUntil ?: 0L
    }

    @Synchronized
    fun clear() {
        clients.clear()
    }
}
