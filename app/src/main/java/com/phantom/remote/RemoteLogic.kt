package com.phantom.remote

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Which TV to connect to, from what discovery has found so far. Pure, unit-tested. */
object TvPick {
    sealed class Decision {
        data class Connect(val tv: FoundTv) : Decision()
        object Wait : Decision()
        data class Choose(val tvs: List<FoundTv>) : Decision()
    }

    /** Last paired TV connects at once; otherwise wait [settleMs] for others, then auto-pick a lone TV. */
    fun decide(found: List<FoundTv>, last: String?, firstFoundAt: Long, now: Long, settleMs: Long = 800): Decision {
        if (found.isEmpty()) return Decision.Wait
        found.firstOrNull { it.service == last }?.let { return Decision.Connect(it) }
        if (now - firstFoundAt < settleMs) return Decision.Wait
        return if (found.size == 1) Decision.Connect(found[0]) else Decision.Choose(found)
    }
}

/** Per-TV tokens and the last TV, keyed by the TV's service name (stable when its IP changes). */
class RemoteStore(context: Context) {
    private val prefs = context.getSharedPreferences("loungo_remote", Context.MODE_PRIVATE)
    var lastService: String?
        get() = prefs.getString("last", null)
        set(v) { prefs.edit().putString("last", v).apply() }
    fun token(service: String): String? = prefs.getString("t_$service", null)
    fun saveToken(service: String, token: String) {
        prefs.edit().putString("t_$service", token).putString("last", service).apply()
    }
    fun forget(service: String) {
        val e = prefs.edit().remove("t_$service")
        if (lastService == service) e.remove("last")
        e.apply()
    }
}

/** The TV's pairing endpoints (see docs/protocol.md). Blocking: call off the main thread. */
class PairClient(private val host: String, private val port: Int) {
    sealed class Result {
        data class Token(val token: String) : Result()
        object Pending : Result()
        object Denied : Result()
        object Busy : Result()
        /** The TV's rate limiter blocked this phone for a minute (too many failed attempts). */
        object RateLimited : Result()
        object TvBusy : Result()
        object Error : Result()
        data class Started(val id: String) : Result()
    }

    fun check(token: String): Boolean = try {
        get("/pair/check?k=${enc(token)}").first == 200
    } catch (_: Exception) { false }

    fun request(name: String): Result = try {
        val (code, body) = get("/pair/request?name=${enc(name)}")
        when (code) {
            200 -> JSONObject(body).optString("id").takeIf { it.isNotEmpty() }?.let { Result.Started(it) } ?: Result.Error
            429 -> busyOrLimited(body)
            503 -> Result.TvBusy
            else -> Result.Error
        }
    } catch (_: Exception) { Result.Error }

    fun status(id: String): Result = try {
        val (code, body) = get("/pair/status?id=${enc(id)}")
        when (code) {
            200 -> {
                val j = JSONObject(body)
                if (j.optString("status") == "approved") Result.Token(j.optString("token")) else Result.Pending
            }
            403, 404 -> Result.Denied
            429 -> busyOrLimited(body)
            else -> Result.Error
        }
    } catch (_: Exception) { Result.Error }

    /** 429 is either "another phone is waiting" (JSON) or the rate limiter's plain-text block. */
    private fun busyOrLimited(body: String): Result =
        if (body.contains("\"busy\"")) Result.Busy else Result.RateLimited

    private fun get(path: String): Pair<Int, String> {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.useCaches = false
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
