package com.phantom.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

fun interface AssetSource {
    fun open(path: String): InputStream?
}

const val DEV_TOKEN = "test123"

object Hub {
    @Volatile var sink: Sink? = null
    @Volatile var adBlocker: com.phantom.web.AdBlocker? = null
    @Volatile var onAdblockUpdateRequested: (() -> Unit)? = null

    interface Sink {
        fun onBinary(m: BinIn)
        fun onJson(m: JsonIn)
        fun onClientChanged(connected: Boolean)
    }

    interface PlayerInputListener {
        fun onButton(down: Boolean, btn: Int)
        fun onScroll(sx: Short, sy: Short)
        fun onClosePlayer()
        fun onPlayerCmd(a: String, ms: Long? = null, type: String? = null, id: String? = null)
    }

    @Volatile var activePlayerListener: PlayerInputListener? = null

    /** Android phone remote pairing (no QR): one pending "pair this phone?" request at a time. */
    val pairRequests = PairRequests()
    /** Shows the prompt on the TV screen that is on top; null when no TV screen is visible. Called off the main thread. */
    @Volatile var pairPromptHandler: ((PairRequests.Pending) -> Unit)? = null

    @Volatile var helloProvider: () -> String = { buildHello(1, 1920, 1080, 2.0f, "mobile") }
    @Volatile var stateProvider: (() -> String)? = null

    @Volatile var activeSocket: NanoWSD.WebSocket? = null

    @Volatile var isActivityForeground: Boolean = false
    @Volatile var bringToFrontHandler: (() -> Unit)? = null
    val tokenListeners = CopyOnWriteArrayList<() -> Unit>()

    val clientListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    val wsRejectListeners = CopyOnWriteArrayList<() -> Unit>()

    fun onWsReject() {
        for (listener in wsRejectListeners) {
            try {
                listener()
            } catch (e: Exception) {}
        }
    }

    fun onResetPairTriggered() {
        for (listener in tokenListeners) {
            try {
                listener()
            } catch (e: Exception) {}
        }
    }

    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "phantom-hub-sender").apply { isDaemon = true }
    }

    fun sendText(s: String) {
        val sock = activeSocket ?: return
        sendExecutor.execute {
            try {
                sock.send(s)
            } catch (e: Exception) {
                android.util.Log.w("PHANTOM", "WS_ERR sendText: ${e.message}")
            }
        }
    }

    fun sendBinary(b: ByteArray) {
        val sock = activeSocket ?: return
        sendExecutor.execute {
            try {
                sock.send(b)
            } catch (e: Exception) {
                android.util.Log.w("PHANTOM", "WS_ERR sendBinary: ${e.message}")
            }
        }
    }

    fun onClientConnected(connected: Boolean) {
        sink?.onClientChanged(connected)
        for (listener in clientListeners) {
            try {
                listener(connected)
            } catch (e: Exception) {}
        }
    }
}

class PhantomServer(
    port: Int,
    val assetSource: AssetSource,
    val socketTimeoutMs: Int = 15_000,
    val tokenProvider: () -> String = { DEV_TOKEN },
    val allowDevToken: Boolean = false,
    val onResetPair: (() -> Unit)? = null,
    val logger: (String) -> Unit = {}
) : NanoWSD(port) {

    val rateLimiter = RateLimiter(maxAttempts = 10, windowMs = 60_000L, banDurationMs = 60_000L)

    fun startServer() {
        start(socketTimeoutMs, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        if (rateLimiter.isBlocked(clientIp)) {
            logger("RATE_LIMIT blocked ip=$clientIp")
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_PLAINTEXT, "Too Many Requests")
        }
        val uri = session.uri
        if (uri.startsWith("/ws")) {
            val token = session.parms["k"] ?: run {
                val query = session.queryParameterString ?: ""
                query.split("&").find { it.startsWith("k=") }?.substringAfter("k=")
            }
            if (!token.isNullOrEmpty()) {
                if (!TokenManager.validateToken(token, tokenProvider(), allowDevToken)) {
                    val banned = rateLimiter.recordFailure(clientIp)
                    logger("WS_REJECT token=$token ip=$clientIp banned=$banned")
                    Hub.onWsReject()
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
                }
            }
        }
        return super.serve(session)
    }

    public override fun serveHttp(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        if (rateLimiter.isBlocked(clientIp)) {
            logger("RATE_LIMIT blocked http ip=$clientIp")
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_PLAINTEXT, "Too Many Requests")
        }

        var path = session.uri
        if (path == "/") {
            path = "/index.html"
        }
        path = path.removePrefix("/")
        if (path.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request")
        }
        if (path == "pair/check") {
            val token = session.parms["k"] ?: run {
                val query = session.queryParameterString ?: ""
                query.split("&").find { it.startsWith("k=") }?.substringAfter("k=")
            }
            val isValid = TokenManager.validateToken(token, tokenProvider(), allowDevToken)
            if (!isValid) {
                val banned = rateLimiter.recordFailure(clientIp)
                logger("WS_REJECT check token=$token ip=$clientIp banned=$banned")
                Hub.onWsReject()
            } else {
                rateLimiter.recordSuccess(clientIp)
            }
            val status = if (isValid) Response.Status.OK else Response.Status.UNAUTHORIZED
            val resp = newFixedLengthResponse(status, MIME_PLAINTEXT, "")
            resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            return resp
        }
        if (path == "pair/request") return pairRequest(session, clientIp)
        if (path == "pair/status") return pairStatus(session, clientIp)
        val inputStream = assetSource.open(path)
        if (inputStream != null) {
            try {
                val mime = getMimeType(path)
                val resp = newChunkedResponse(Response.Status.OK, mime, inputStream)
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp.addHeader("Pragma", "no-cache")
                resp.addHeader("Expires", "0")
                return resp
            } catch (e: Exception) {
                logger("WS_ERR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        }

    /** Phone asks to pair: the TV shows "Connect this phone?" and the remote's OK approves. */
    private fun pairRequest(session: IHTTPSession, clientIp: String): Response {
        val prompt = Hub.pairPromptHandler
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"status":"tv_busy"}""")
        return when (val r = Hub.pairRequests.request(clientIp, session.parms["name"])) {
            is PairRequests.Start.Busy -> json(Response.Status.TOO_MANY_REQUESTS, """{"status":"busy"}""")
            is PairRequests.Start.Existing -> json(Response.Status.OK, """{"id":"${r.req.id}"}""")
            is PairRequests.Start.Created -> {
                logger("PAIR_REQUEST ip=$clientIp")
                prompt(r.req)
                json(Response.Status.OK, """{"id":"${r.req.id}"}""")
            }
        }
    }

    private fun pairStatus(session: IHTTPSession, clientIp: String): Response =
        when (Hub.pairRequests.status(session.parms["id"], clientIp)) {
            is PairRequests.Status.Waiting -> json(Response.Status.OK, """{"status":"pending"}""")
            is PairRequests.Status.Approved -> {
                rateLimiter.recordSuccess(clientIp)
                logger("PAIR_APPROVED ip=$clientIp")
                json(Response.Status.OK, """{"status":"approved","token":"${tokenProvider()}"}""")
            }
            is PairRequests.Status.Denied -> {
                rateLimiter.recordFailure(clientIp)
                logger("PAIR_DENIED ip=$clientIp")
                json(Response.Status.FORBIDDEN, """{"status":"denied"}""")
            }
            is PairRequests.Status.Unknown -> {
                rateLimiter.recordFailure(clientIp)
                json(Response.Status.NOT_FOUND, """{"status":"unknown"}""")
            }
        }

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket? {
        val clientIp = handshake.remoteIpAddress ?: "unknown"
        if (rateLimiter.isBlocked(clientIp)) {
            logger("RATE_LIMIT blocked ws handshake ip=$clientIp")
            return null
        }

        val urlToken = handshake.parms["k"] ?: run {
            val query = handshake.queryParameterString ?: ""
            query.split("&").find { it.startsWith("k=") }?.substringAfter("k=")
        }

        val urlAuthenticated = if (!urlToken.isNullOrEmpty()) {
            if (!TokenManager.validateToken(urlToken, tokenProvider(), allowDevToken)) {
                val banned = rateLimiter.recordFailure(clientIp)
                logger("WS_REJECT url token=$urlToken ip=$clientIp banned=$banned")
                Hub.onWsReject()
                return null
            }
            rateLimiter.recordSuccess(clientIp)
            true
        } else {
            false
        }

        class PhantomWebSocket(hs: IHTTPSession) : NanoWSD.WebSocket(hs) {
            private var authenticated = urlAuthenticated
            private var helloReceived = false

            fun sendCustomClose(code: Int, reason: String) {
                try {
                    val reasonBytes = reason.toByteArray(Charsets.UTF_8)
                    val payload = ByteArray(2 + reasonBytes.size)
                    payload[0] = ((code ushr 8) and 0xFF).toByte()
                    payload[1] = (code and 0xFF).toByte()
                    System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
                    val frame = NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Close, true, payload)
                    sendFrame(frame)
                } catch (e: Exception) {
                    logger("WS_ERR ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            override fun onOpen() {}

            override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
                if (Hub.activeSocket === this) {
                    Hub.activeSocket = null
                    Hub.onClientConnected(false)
                }
            }

            override fun onPong(pong: NanoWSD.WebSocketFrame) {}

            override fun onMessage(message: NanoWSD.WebSocketFrame) {
                try {
                    val opcode = message.opCode
                    if (opcode == NanoWSD.WebSocketFrame.OpCode.Text) {
                        val text = message.textPayload
                        if (text != null && text.isNotEmpty()) {
                            val jsonIn = parseJson(text)
                            if (!helloReceived) {
                                if (jsonIn is JsonIn.Hello) {
                                    if (!authenticated) {
                                        val tokenToValidate = jsonIn.token
                                        if (!TokenManager.validateToken(tokenToValidate, tokenProvider(), allowDevToken)) {
                                            val banned = rateLimiter.recordFailure(clientIp)
                                            logger("WS_REJECT hello token=$tokenToValidate ip=$clientIp banned=$banned")
                                            Hub.onWsReject()
                                            send(buildErr("unauthorized", "Invalid token"))
                                            sendCustomClose(4003, "unauthorized") // token invalid: the page drops it and asks to re-pair (4001 means "replaced")
                                            return
                                        }
                                        rateLimiter.recordSuccess(clientIp)
                                        authenticated = true
                                    }
                                    helloReceived = true
                                    Hub.bringToFrontHandler?.invoke()
                                    if (jsonIn.v != 1) {
                                        send(buildErr("version", "Unsupported version"))
                                        sendCustomClose(4002, "version_mismatch")
                                    } else {
                                        send(Hub.helloProvider())
                                        Hub.stateProvider?.let { sp ->
                                            try {
                                                send(sp())
                                            } catch (e: Exception) {
                                                logger("WS_ERR stateProvider: ${e.message}")
                                            }
                                        }
                                    }
                                } else {
                                    sendCustomClose(4001, "expected_hello")
                                }
                            } else {
                                when (jsonIn) {
                                    is JsonIn.Ping -> send(buildPong())
                                    is JsonIn.ResetPair -> {
                                        logger("RESET_PAIR")
                                        sendCustomClose(4003, "reset_pair")
                                        onResetPair?.invoke()
                                    }
                                    is JsonIn.GetLogs -> {
                                        val lines = com.phantom.log.EventLogStore.readLines(500)
                                        send(buildLogs(lines))
                                        logger("LOGS_SENT count=${lines.size}")
                                    }
                                    is JsonIn -> Hub.sink?.onJson(jsonIn)
                                    else -> {}
                                }
                            }
                        }
                    } else if (opcode == NanoWSD.WebSocketFrame.OpCode.Binary) {
                        val binary = message.binaryPayload
                        if (binary != null && binary.isNotEmpty()) {
                            val decoded = decodeBinary(binary)
                            if (decoded != null) {
                                Hub.sink?.onBinary(decoded)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger("WS_ERR ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            override fun onException(exception: IOException) {
                logger("WS_ERR ${exception.javaClass.simpleName}: ${exception.message}")
            }
        }

        val ws = PhantomWebSocket(handshake)
        val oldSock = Hub.activeSocket
        Hub.activeSocket = ws

        if (oldSock != null && oldSock !== ws) {
            try {
                if (oldSock is PhantomWebSocket) {
                    oldSock.sendCustomClose(4001, "replaced")
                } else {
                    oldSock.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "replaced", false)
                }
            } catch (e: Exception) {
                logger("WS_ERR ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        logger("WS_OPEN")
        Hub.onClientConnected(true)
        return ws
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html", true) -> "text/html"
            path.endsWith(".js", true) -> "application/javascript"
            path.endsWith(".css", true) -> "text/css"
            path.endsWith(".svg", true) -> "image/svg+xml"
            path.endsWith(".png", true) -> "image/png"
            path.endsWith(".json", true) -> "application/json"
            path.endsWith(".webmanifest", true) -> "application/manifest+json"
            else -> MIME_PLAINTEXT
        }
    }
}
