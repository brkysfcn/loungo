package com.phantom.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProtocolTest {

    private fun loadVectors(): JSONArray {
        val file = File("../../docs/protocol_vectors.json").takeIf { it.exists() }
            ?: File("docs/protocol_vectors.json").takeIf { it.exists() }
            ?: File("../docs/protocol_vectors.json").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"), "docs/protocol_vectors.json")
        
        val content = file.readText()
        return JSONArray(content)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testProtocolVectors() {
        val vectors = loadVectors()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val kind = v.getString("kind")
            val hex = v.getString("hex")
            val bytes = hexToByteArray(hex)
            val fields = v.getJSONObject("fields")

            when (kind) {
                "move" -> {
                    val decoded = decodeBinary(bytes) as BinIn.Move
                    assertEquals(fields.getInt("dx").toShort(), decoded.dx)
                    assertEquals(fields.getInt("dy").toShort(), decoded.dy)
                }
                "scroll" -> {
                    val decoded = decodeBinary(bytes) as BinIn.Scroll
                    assertEquals(fields.getInt("sx").toShort(), decoded.sx)
                    assertEquals(fields.getInt("sy").toShort(), decoded.sy)
                }
                "button" -> {
                    val decoded = decodeBinary(bytes) as BinIn.Button
                    assertEquals(fields.getBoolean("down"), decoded.down)
                    assertEquals(fields.getInt("btn"), decoded.btn)
                }
                "thumb" -> {
                    val reqId = fields.getInt("reqId")
                    val jpegHex = fields.getString("jpegHex")
                    val jpegBytes = hexToByteArray(jpegHex)
                    val encoded = encodeThumb(reqId, jpegBytes)
                    assertEquals(hex, byteArrayToHex(encoded))
                }
            }
        }
    }

    @Test
    fun testParseJson() {
        val helloJson = "{\"t\":\"hello\",\"v\":1,\"client\":\"pwa\"}"
        val hello = parseJson(helloJson) as JsonIn.Hello
        assertEquals(1, hello.v)
        assertEquals("pwa", hello.client)

        val navJson = "{\"t\":\"nav\",\"url\":\"https://google.com\"}"
        val nav = parseJson(navJson) as JsonIn.Nav
        assertEquals("https://google.com", nav.url)

        assertNotNull(parseJson("{\"t\":\"back\"}"))
        assertNotNull(parseJson("{\"t\":\"fwd\"}"))
        assertNotNull(parseJson("{\"t\":\"reload\"}"))
        assertNotNull(parseJson("{\"t\":\"stop\"}"))

        val textJson = "{\"t\":\"text\",\"value\":\"hello\"}"
        val text = parseJson(textJson) as JsonIn.Text
        assertEquals("hello", text.value)

        val keyJson = "{\"t\":\"key\",\"k\":\"Enter\"}"
        val key = parseJson(keyJson) as JsonIn.Key
        assertEquals("Enter", key.k)

        val tabJson = "{\"t\":\"tab\",\"url\":\"https://example.com\",\"scrollY\":100}"
        val tab = parseJson(tabJson) as JsonIn.Tab
        assertEquals("https://example.com", tab.url)
        assertEquals(100, tab.scrollY)

        val snapJson = "{\"t\":\"snap\",\"reqId\":123}"
        val snap = parseJson(snapJson) as JsonIn.Snap
        assertEquals(123, snap.reqId)

        val zoomJson = "{\"t\":\"zoom\",\"text\":150}"
        val zoom = parseJson(zoomJson) as JsonIn.Zoom
        assertEquals(150, zoom.text)

        val uaJson = "{\"t\":\"ua\",\"mode\":\"desktop\"}"
        val ua = parseJson(uaJson) as JsonIn.Ua
        assertEquals("desktop", ua.mode)

        val popupJson = "{\"t\":\"popup\",\"id\":\"p1\",\"action\":\"open\"}"
        val popup = parseJson(popupJson) as JsonIn.Popup
        assertEquals("p1", popup.id)
        assertEquals("open", popup.action)

        val mediaOpenJson = "{\"t\":\"media_open\",\"url\":\"https://vid.com\"}"
        val mediaOpen = parseJson(mediaOpenJson) as JsonIn.MediaOpen
        assertEquals("https://vid.com", mediaOpen.url)

        val pointerJson = "{\"t\":\"pointer\",\"x\":0.5,\"y\":0.8}"
        val pointer = parseJson(pointerJson) as JsonIn.Pointer
        assertEquals(0.5, pointer.x, 0.001)
        assertEquals(0.8, pointer.y, 0.001)

        val diagJson = "{\"t\":\"diag\",\"rtt\":{\"p50\":12.5,\"p95\":25.0,\"max\":40.0,\"lost\":0}}"
        val diag = parseJson(diagJson) as JsonIn.Diag
        assertEquals(12.5, diag.rtt?.getDouble("p50") ?: 0.0, 0.01)
        assertEquals(0, diag.rtt?.getInt("lost") ?: 0)

        val viewDiagJson = "{\"t\":\"diag\",\"view\":{\"dpr\":3,\"innerW\":390,\"innerH\":844,\"standalone\":true}}"
        val viewDiag = parseJson(viewDiagJson) as JsonIn.Diag
        assertEquals(3, viewDiag.view?.getInt("dpr"))
        assertTrue(viewDiag.view?.getBoolean("standalone") == true)

        val volUp = parseJson("{\"t\":\"vol\",\"a\":\"up\"}") as JsonIn.Vol
        assertEquals("up", volUp.a)
        val volDown = parseJson("{\"t\":\"vol\",\"a\":\"down\"}") as JsonIn.Vol
        assertEquals("down", volDown.a)
        val volMute = parseJson("{\"t\":\"vol\",\"a\":\"mute\"}") as JsonIn.Vol
        assertEquals("mute", volMute.a)
        assertNull(parseJson("{\"t\":\"vol\",\"a\":\"other\"}"))

        assertNotNull(parseJson("{\"t\":\"adblock_update\"}") as JsonIn.AdblockUpdate)

        assertNotNull(parseJson("{\"t\":\"ping\"}"))

        assertNull(parseJson("invalid json"))
        assertNull(parseJson("{\"t\":\"unknown\"}"))
    }

    @Test
    fun testJsonBuilders() {
        assertTrue(buildHello(1, 1920, 1080, 1.5f, "Mozilla").contains("\"t\":\"hello\""))
        assertTrue(buildState("url", "title", true, false, true, 50).contains("\"t\":\"state\""))
        val volObj = org.json.JSONObject().apply {
            put("level", 8)
            put("max", 15)
            put("muted", false)
        }
        val stateWithVol = buildState("url", "title", true, false, true, 50, vol = volObj)
        assertTrue(stateWithVol.contains("\"vol\":{\"level\":8,\"max\":15,\"muted\":false}"))
        assertTrue(buildEditable(true, "val", "text", "go").contains("\"t\":\"editable\""))
        assertTrue(buildSnap(1, "url", "title", 10).contains("\"t\":\"snap\""))
        assertTrue(buildPopup("id", "url", true).contains("\"t\":\"popup\""))
        assertTrue(buildPopupGone("id", "timeout").contains("\"t\":\"popup_gone\""))
        assertTrue(buildFullscreen(true).contains("\"t\":\"fullscreen\""))
        assertTrue(buildMedia("url", "mp4").contains("\"t\":\"media\""))
        assertTrue(buildErr("code", "msg").contains("\"t\":\"err\""))
        assertTrue(buildPong().contains("\"t\":\"pong\""))
    }
}
