package com.phantom.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

sealed interface BinIn {
    data class Move(val dx: Short, val dy: Short) : BinIn
    data class Scroll(val sx: Short, val sy: Short) : BinIn
    data class Button(val down: Boolean, val btn: Int) : BinIn
}

fun decodeBinary(b: ByteArray): BinIn? {
    if (b.isEmpty()) return null
    val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    val op = buf.get().toInt() and 0xFF
    return when (op) {
        0x01 -> {
            if (b.size < 5) return null
            val dx = buf.short
            val dy = buf.short
            BinIn.Move(dx, dy)
        }
        0x02 -> {
            if (b.size < 5) return null
            val sx = buf.short
            val sy = buf.short
            BinIn.Scroll(sx, sy)
        }
        0x03 -> {
            if (b.size < 3) return null
            val down = buf.get() == 1.toByte()
            val btn = buf.get().toInt() and 0xFF
            BinIn.Button(down, btn)
        }
        else -> null
    }
}

fun encodeThumb(reqId: Int, jpeg: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(1 + 2 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(0x20.toByte())
    buf.putShort(reqId.toShort())
    buf.put(jpeg)
    return buf.array()
}

sealed interface JsonIn {
    data class Hello(val v: Int, val client: String, val token: String? = null) : JsonIn
    data class Nav(val url: String) : JsonIn
    object Back : JsonIn
    object Fwd : JsonIn
    object Reload : JsonIn
    object Stop : JsonIn
    data class Text(val value: String) : JsonIn
    data class Key(val k: String) : JsonIn
    data class Tab(val url: String, val scrollY: Int) : JsonIn
    data class Snap(val reqId: Int) : JsonIn
    data class Zoom(val text: Int) : JsonIn
    data class Ua(val mode: String) : JsonIn
    data class Popup(val id: String, val action: String) : JsonIn
    data class MediaOpen(val url: String) : JsonIn
    data class PlayerCmd(val a: String, val ms: Long? = null, val type: String? = null, val id: String? = null) : JsonIn
    data class Vol(val a: String) : JsonIn
    data class Pointer(val x: Double, val y: Double) : JsonIn
    data class Diag(val rtt: JSONObject? = null, val view: JSONObject? = null) : JsonIn
    data class Adblock(val on: Boolean) : JsonIn
    data class Lite(val mode: String) : JsonIn
    object AdblockUpdate : JsonIn
    object ResetPair : JsonIn
    object Ping : JsonIn
    object GetLogs : JsonIn
    object ClearData : JsonIn
}

fun parseJson(s: String): JsonIn? {
    return try {
        val json = JSONObject(s)
        val t = json.optString("t")
        when (t) {
            "hello" -> {
                val v = json.optInt("v", -1)
                val client = json.optString("client")
                if (v == -1 || client.isEmpty()) return null
                val token = if (json.has("token")) json.optString("token").ifEmpty { null } else null
                JsonIn.Hello(v, client, token)
            }
            "nav" -> {
                val url = json.optString("url")
                if (url.isEmpty() && !json.has("url")) return null
                JsonIn.Nav(json.optString("url"))
            }
            "back" -> JsonIn.Back
            "fwd" -> JsonIn.Fwd
            "reload" -> JsonIn.Reload
            "stop" -> JsonIn.Stop
            "text" -> {
                val value = json.optString("value", "")
                JsonIn.Text(value)
            }
            "key" -> {
                val k = json.optString("k")
                if (k.isEmpty()) return null
                JsonIn.Key(k)
            }
            "tab" -> {
                val url = json.optString("url", "")
                val scrollY = json.optInt("scrollY", 0)
                JsonIn.Tab(url, scrollY)
            }
            "snap" -> {
                val reqId = json.optInt("reqId", -1)
                if (reqId == -1) return null
                JsonIn.Snap(reqId)
            }
            "zoom" -> {
                val text = json.optInt("text", -1)
                if (text == -1) return null
                JsonIn.Zoom(text)
            }
            "ua" -> {
                val mode = json.optString("mode")
                if (mode.isEmpty()) return null
                JsonIn.Ua(mode)
            }
            "popup" -> {
                val id = json.optString("id")
                val action = json.optString("action")
                if (id.isEmpty() || action.isEmpty()) return null
                JsonIn.Popup(id, action)
            }
            "media_open" -> {
                val url = json.optString("url")
                if (url.isEmpty()) return null
                JsonIn.MediaOpen(url)
            }
            "player_cmd" -> {
                val a = json.optString("a")
                val ms = if (json.has("ms")) json.optLong("ms") else null
                val type = if (json.has("type")) json.optString("type") else null
                val id = if (json.has("id")) json.optString("id") else null
                if (a.isEmpty()) return null
                JsonIn.PlayerCmd(a, ms, type, id)
            }
            "vol" -> {
                val a = json.optString("a")
                if (a == "up" || a == "down" || a == "mute") {
                    JsonIn.Vol(a)
                } else null
            }
            "pointer" -> {
                val x = json.optDouble("x", Double.NaN)
                val y = json.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) return null
                JsonIn.Pointer(x, y)
            }
            "diag" -> {
                val rtt = json.optJSONObject("rtt")
                val view = json.optJSONObject("view")
                JsonIn.Diag(rtt, view)
            }
            "adblock" -> {
                val on = json.optBoolean("on", true)
                JsonIn.Adblock(on)
            }
            "adblock_update" -> JsonIn.AdblockUpdate
            "lite" -> {
                val mode = json.optString("mode")
                if (mode.isEmpty()) return null
                JsonIn.Lite(mode)
            }
            "reset_pair" -> JsonIn.ResetPair
            "ping" -> JsonIn.Ping
            "get_logs" -> JsonIn.GetLogs
            "clear_data" -> JsonIn.ClearData
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

fun buildHello(v: Int, w: Int, h: Int, dpr: Float, ua: String, pwa: String? = null, lite: String? = null, tour: Boolean = false): String {
    return JSONObject().apply {
        put("t", "hello")
        put("v", v)
        put("w", w)
        put("h", h)
        put("dpr", dpr)
        put("ua", ua)
        if (pwa != null) put("pwa", pwa)
        if (lite != null) put("lite", lite)
        if (tour) put("tour", true)
    }.toString()
}

fun buildState(
    url: String,
    title: String,
    canBack: Boolean,
    canFwd: Boolean,
    loading: Boolean,
    progress: Int,
    ua: String = "mobile",
    zoom: Int = 100,
    popupOpen: Boolean = false,
    adblock: Boolean = true,
    blocked: Int = 0,
    vol: JSONObject? = null
): String {
    return JSONObject().apply {
        put("t", "state")
        put("url", url)
        put("title", title)
        put("canBack", canBack)
        put("canFwd", canFwd)
        put("loading", loading)
        put("progress", progress)
        put("ua", ua)
        put("zoom", zoom)
        put("popupOpen", popupOpen)
        put("adblock", adblock)
        put("blocked", blocked)
        if (vol != null) {
            put("vol", vol)
        }
    }.toString()
}

fun buildEditable(on: Boolean, src: String? = null, value: String? = null, input: String? = null, enter: String? = null): String {
    return JSONObject().apply {
        put("t", "editable")
        put("on", on)
        if (src != null) put("src", src)
        if (value != null) put("value", value)
        if (input != null) put("input", input)
        if (enter != null) put("enter", enter)
    }.toString()
}

fun buildSnap(reqId: Int, url: String, title: String, scrollY: Int): String {
    return JSONObject().apply {
        put("t", "snap")
        put("reqId", reqId)
        put("url", url)
        put("title", title)
        put("scrollY", scrollY)
    }.toString()
}

fun buildPopup(id: String, url: String, gesture: Boolean): String {
    return JSONObject().apply {
        put("t", "popup")
        put("id", id)
        put("url", url)
        put("gesture", gesture)
    }.toString()
}

fun buildPopupGone(id: String, reason: String): String {
    return JSONObject().apply {
        put("t", "popup_gone")
        put("id", id)
        put("reason", reason)
    }.toString()
}

fun buildFullscreen(on: Boolean): String {
    return JSONObject().apply {
        put("t", "fullscreen")
        put("on", on)
    }.toString()
}

fun buildMedia(url: String, kind: String, subs: List<JSONObject> = emptyList()): String {
    return JSONObject().apply {
        put("t", "media")
        put("url", url)
        put("kind", kind)
        if (subs.isNotEmpty()) {
            put("subs", JSONArray().apply {
                subs.forEach { put(it) }
            })
        }
    }.toString()
}

fun buildPlayer(
    on: Boolean,
    playing: Boolean = false,
    pos: Long = 0L,
    dur: Long = 0L,
    title: String? = null,
    tracks: JSONObject? = null,
    sel: JSONObject? = null,
    resume: Long? = null
): String {
    val json = JSONObject()
    json.put("t", "player")
    json.put("on", on)
    if (on) {
        json.put("playing", playing)
        json.put("pos", pos)
        json.put("dur", dur)
        if (!title.isNullOrEmpty()) {
            json.put("title", title)
        }
        if (tracks != null) {
            json.put("tracks", tracks)
        }
        if (sel != null) {
            json.put("sel", sel)
        }
        if (resume != null) {
            json.put("resume", resume)
        }
    }
    return json.toString()
}

fun buildErr(code: String, msg: String): String {
    return JSONObject().apply {
        put("t", "err")
        put("code", code)
        put("msg", msg)
    }.toString()
}

fun buildPong(): String {
    return JSONObject().apply {
        put("t", "pong")
    }.toString()
}

/** Ask the phone to show its usage tips (sent while the TV tour is on screen). */
fun buildTips(): String = JSONObject().apply { put("t", "tips") }.toString()

fun buildCleared(): String {
    return JSONObject().apply {
        put("t", "cleared")
    }.toString()
}

fun buildLogs(lines: List<String>): String {
    val arr = JSONArray()
    for (l in lines) {
        arr.put(l)
    }
    return JSONObject().apply {
        put("t", "logs")
        put("lines", arr)
    }.toString()
}
