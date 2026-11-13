package com.phantom.server

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response as OkResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerTest {
    private lateinit var server: PhantomServer
    private lateinit var client: OkHttpClient
    private var port = 0
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
        port = java.net.ServerSocket(0).use { it.localPort }
        val assetSource = AssetSource { path ->
            if (path == "index.html" || path == "/") {
                ByteArrayInputStream("<html>index</html>".toByteArray())
            } else {
                null
            }
        }
        server = PhantomServer(port, assetSource, 15_000) { logs.add(it) }
        server.startServer()
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testGetIndex() {
        val request = Request.Builder().url("http://localhost:$port/").build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("<html>index</html>", response.body?.string())
        }
    }

    @Test
    fun testGetPathTraversal() {
        val socket = java.net.Socket("localhost", port)
        socket.getOutputStream().write("GET /../x HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
        val statusLine = reader.readLine()
        socket.close()
        assertTrue(statusLine != null && statusLine.contains("400"))
    }

    @Test
    fun testWsWithoutToken() {
        val request = Request.Builder().url("ws://localhost:$port/ws").build()
        val latch = CountDownLatch(1)
        var statusCode = 0
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                // Send hello without token
                webSocket.send("{\"t\":\"hello\",\"v\":1,\"client\":\"test\"}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                statusCode = code
                webSocket.close(code, reason)
                latch.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (statusCode == 0) statusCode = code
                latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                if (response != null) {
                    statusCode = response.code
                }
                latch.countDown()
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(4003, statusCode) // invalid or missing token: re-pair, not "replaced"
    }

    @Test
    fun testWsInvalidUrlToken() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=wrong_token").build()
        val latch = CountDownLatch(1)
        var statusCode = 0
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                if (response != null) {
                    statusCode = response.code
                }
                latch.countDown()
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(401, statusCode)
    }

    @Test
    fun testHelloSuccess() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=test123").build()
        val latch = CountDownLatch(1)
        var receivedHello = false
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                webSocket.send("{\"t\":\"hello\",\"v\":1,\"client\":\"test\"}")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("t\":\"hello\"")) {
                    receivedHello = true
                    latch.countDown()
                }
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(receivedHello)
    }

    @Test
    fun testHelloVersionMismatch() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=test123").build()
        val latch = CountDownLatch(1)
        var closeCode = 0
        var closeReason = ""
        var receivedMsg = ""
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                webSocket.send("{\"t\":\"hello\",\"v\":2,\"client\":\"test\"}")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedMsg = text
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                closeReason = reason
                webSocket.close(code, reason)
                latch.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closeCode == 0) closeCode = code
                latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                latch.countDown()
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(receivedMsg.contains("version"))
        assertEquals(4002, closeCode)
    }

    @Test
    fun testSingleClientReplaced() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=test123").build()
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        var ws1Closed = false
        var ws1CloseCode = 0

        val ws1 = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                latch1.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ws1Closed = true
                ws1CloseCode = code
                webSocket.close(1000, null)
                latch2.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws1Closed = true
                if (ws1CloseCode == 0) ws1CloseCode = code
                latch2.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                ws1Closed = true
                latch2.countDown()
            }
        })
        assertTrue(latch1.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        val ws2 = client.newWebSocket(request, object : WebSocketListener() {})
        val awaited = latch2.await(5, TimeUnit.SECONDS)
        assertTrue(awaited)
        assertTrue(ws1Closed)
        assertEquals(4001, ws1CloseCode)
        ws1.cancel()
        ws2.cancel()
    }

    @Test
    fun testBinaryMove() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=test123").build()
        val latch = CountDownLatch(1)
        var receivedBin: BinIn? = null
        Hub.sink = object : Hub.Sink {
            override fun onBinary(m: BinIn) {
                receivedBin = m
                latch.countDown()
            }
            override fun onJson(m: JsonIn) {}
            override fun onClientChanged(connected: Boolean) {}
        }

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                webSocket.send("{\"t\":\"hello\",\"v\":1,\"client\":\"test\"}")
                val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                buf.put(0x01.toByte())
                buf.putShort(10.toShort())
                buf.putShort(20.toShort())
                webSocket.send(okio.ByteString.of(*buf.array()))
            }
        })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedBin)
        val move = receivedBin as BinIn.Move
        assertEquals(10, move.dx.toInt())
        assertEquals(20, move.dy.toInt())
        Hub.sink = null
    }

    @Test
    fun testPingPong() {
        val request = Request.Builder().url("ws://localhost:$port/ws?k=test123").build()
        val latch = CountDownLatch(1)
        var receivedPong = false
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                webSocket.send("{\"t\":\"hello\",\"v\":1,\"client\":\"test\"}")
                webSocket.send("{\"t\":\"ping\"}")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("t\":\"pong\"")) {
                    receivedPong = true
                    latch.countDown()
                }
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(receivedPong)
    }

    @Test
    fun testInactivityTimeout() {
        val shortServer = PhantomServer(8767, { null }, 1000)
        shortServer.startServer()
        try {
            val request = Request.Builder().url("ws://localhost:8767/ws?k=test123").build()
            val latch = CountDownLatch(1)
            var closedOrFailed = false
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                    // Send nothing, wait for socket timeout
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closedOrFailed = true
                    latch.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                    closedOrFailed = true
                    latch.countDown()
                }
            })
            val dropped = latch.await(3, TimeUnit.SECONDS)
            assertTrue("Client should be dropped due to inactivity", dropped && closedOrFailed)
        } finally {
            shortServer.stop()
        }
    }

    @Test
    fun testChunkedAssetResponse() {
        val request = Request.Builder().url("http://localhost:$port/index.html").build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("chunked", response.header("Transfer-Encoding"))
            assertEquals("<html>index</html>", response.body?.string())
        }
    }

    @Test
    fun testRandomTokenAcceptedAndWrongToken401() {
        val randomToken = TokenManager.generateToken()
        val customPort = 8770
        val customServer = PhantomServer(
            port = customPort,
            assetSource = { null },
            socketTimeoutMs = 5000,
            tokenProvider = { randomToken },
            allowDevToken = false
        )
        customServer.startServer()

        try {
            // 1. Correct random token connects
            val reqOk = Request.Builder().url("ws://localhost:$customPort/ws?k=$randomToken").build()
            val latchOk = CountDownLatch(1)
            var connected = false
            customServer.let {
                client.newWebSocket(reqOk, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                        connected = true
                        latchOk.countDown()
                        webSocket.close(1000, "done")
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                        latchOk.countDown()
                    }
                })
            }
            assertTrue(latchOk.await(5, TimeUnit.SECONDS))
            assertTrue(connected)

            // 2. Wrong token gets 401
            val reqWrong = Request.Builder().url("ws://localhost:$customPort/ws?k=wrong_token").build()
            val latchWrong = CountDownLatch(1)
            var statusCode = 0
            client.newWebSocket(reqWrong, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                    if (response != null) statusCode = response.code
                    latchWrong.countDown()
                }
            })
            assertTrue(latchWrong.await(5, TimeUnit.SECONDS))
            assertEquals(401, statusCode)
        } finally {
            customServer.stop()
        }
    }

    @Test
    fun testResetPairClosesWith4003AndRejectsOldToken() {
        var currentToken = "token_initial"
        val customPort = 8771
        val customServer = PhantomServer(
            port = customPort,
            assetSource = { null },
            socketTimeoutMs = 5000,
            tokenProvider = { currentToken },
            allowDevToken = false,
            onResetPair = {
                currentToken = "token_new"
            }
        )
        customServer.startServer()

        try {
            val req = Request.Builder().url("ws://localhost:$customPort/ws?k=token_initial").build()
            val openLatch = CountDownLatch(1)
            val closeLatch = CountDownLatch(1)
            var closeCode = 0
            var closeReason = ""

            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                    openLatch.countDown()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode = code
                    closeReason = reason
                    webSocket.close(code, reason)
                    closeLatch.countDown()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closeCode == 0) closeCode = code
                    if (closeReason.isEmpty()) closeReason = reason
                    closeLatch.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                    closeLatch.countDown()
                }
            })

            assertTrue(openLatch.await(5, TimeUnit.SECONDS))

            // First send hello
            ws.send("{\"t\":\"hello\",\"v\":1,\"client\":\"test\"}")
            Thread.sleep(100)

            // Send reset_pair
            ws.send("{\"t\":\"reset_pair\"}")

            assertTrue(closeLatch.await(5, TimeUnit.SECONDS))
            assertEquals(4003, closeCode)
            assertEquals("reset_pair", closeReason)
            assertEquals("token_new", currentToken)

            // Connecting with old token now fails with 401
            val reqOld = Request.Builder().url("ws://localhost:$customPort/ws?k=token_initial").build()
            val latchOld = CountDownLatch(1)
            var oldStatus = 0
            client.newWebSocket(reqOld, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                    if (response != null) oldStatus = response.code
                    latchOld.countDown()
                }
            })
            assertTrue(latchOld.await(5, TimeUnit.SECONDS))
            assertEquals(401, oldStatus)

            // Connecting with new token succeeds
            val reqNew = Request.Builder().url("ws://localhost:$customPort/ws?k=token_new").build()
            val latchNew = CountDownLatch(1)
            var newConnected = false
            client.newWebSocket(reqNew, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                    newConnected = true
                    latchNew.countDown()
                    webSocket.close(1000, "done")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                    latchNew.countDown()
                }
            })
            assertTrue(latchNew.await(5, TimeUnit.SECONDS))
            assertTrue(newConnected)
        } finally {
            customServer.stop()
        }
    }

    @Test
    fun testWsTokenInHelloMessage() {
        val req = Request.Builder().url("ws://localhost:$port/ws").build()
        val openLatch = CountDownLatch(1)
        val msgLatch = CountDownLatch(1)
        var receivedHello = false

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                openLatch.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"t\":\"hello\"")) {
                    receivedHello = true
                    msgLatch.countDown()
                }
            }
        })

        assertTrue(openLatch.await(5, TimeUnit.SECONDS))
        ws.send("{\"t\":\"hello\",\"v\":1,\"client\":\"pwa\",\"token\":\"$DEV_TOKEN\"}")
        assertTrue(msgLatch.await(5, TimeUnit.SECONDS))
        assertTrue(receivedHello)
        ws.close(1000, "done")
    }

    @Test
    fun testWsInvalidTokenInHelloMessage() {
        val req = Request.Builder().url("ws://localhost:$port/ws").build()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        var closeCode = 0

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                openLatch.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(code, reason)
                closeLatch.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closeCode == 0) closeCode = code
                closeLatch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                closeLatch.countDown()
            }
        })

        assertTrue(openLatch.await(5, TimeUnit.SECONDS))
        ws.send("{\"t\":\"hello\",\"v\":1,\"client\":\"pwa\",\"token\":\"bad_token_xyz\"}")
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(4003, closeCode)
    }
}
