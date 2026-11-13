package com.phantom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.phantom.server.AssetSource
import com.phantom.server.Hub
import com.phantom.server.PhantomServer
import com.phantom.server.TokenManager

class CoreService : Service() {
    private var server: PhantomServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mdnsResponder: com.phantom.net.MdnsResponder? = null
    private val clientListener: (Boolean) -> Unit = { connected ->
        if (connected) {
            wifiLock?.acquire()
            Log.i("PHANTOM", "WIFI_LOCK on")
        } else {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i("PHANTOM", "WIFI_LOCK off")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phantom")

        Hub.clientListeners.add(clientListener)

        val serviceHandler = Handler(Looper.getMainLooper())
        Hub.bringToFrontHandler = {
            if (!Hub.isActivityForeground) {
                try {
                    val bringIntent = Intent(this, BrowserActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(bringIntent)
                } catch (e: Exception) {
                    Log.w("PHANTOM", "BRING_FRONT error", e)
                }
            }
        }

        val t0 = SystemClock.elapsedRealtime()
        try {
            server = PhantomServer(
                port = BuildConfig.WS_PORT,
                assetSource = AssetSource { assets.open("pwa/$it") },
                tokenProvider = { TokenManager.getOrInitToken(applicationContext) },
                allowDevToken = BuildConfig.DEBUG,
                onResetPair = {
                    TokenManager.resetToken(applicationContext)
                    Hub.onResetPairTriggered()
                }
            ) { Log.i("PHANTOM", it) }
            server?.startServer()
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i("PHANTOM", "SERVER_UP port=${BuildConfig.WS_PORT} ms=$ms")

            Hub.onAdblockUpdateRequested = {
                com.phantom.web.AdBlockUpdater.checkAndTriggerUpdate(this, Hub.adBlocker, force = true)
            }
            com.phantom.web.AdBlockUpdater.checkAndTriggerUpdate(this, Hub.adBlocker, force = false)

            val id = com.phantom.net.MdnsHelper.getOrCreatePersistentId(applicationContext)
            val hostname = com.phantom.net.MdnsHelper.getHostname(id)
            val ip = com.phantom.pairing.PairingHelper.getLocalIpAddress(applicationContext)
            mdnsResponder = com.phantom.net.MdnsResponder(this, hostname, ip, BuildConfig.WS_PORT)
            mdnsResponder?.start()
        } catch (e: Exception) {
            Log.e("PHANTOM", "SERVER_UP failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Hub.onAdblockUpdateRequested = null
        Hub.bringToFrontHandler = null
        Hub.clientListeners.remove(clientListener)
        mdnsResponder?.stop()
        mdnsResponder = null
        server?.stop()
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.i("PHANTOM", "WIFI_LOCK off")
        }
    }
}
