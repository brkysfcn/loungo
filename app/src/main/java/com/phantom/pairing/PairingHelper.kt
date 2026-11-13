package com.phantom.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address

object PairingHelper {
    fun getLocalIpAddress(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "127.0.0.1"
            // The phone reaches the TV on the home network, never through a VPN: while a VPN is
            // up it is the "active" network and its tunnel address (e.g. 10.0.0.1) is useless.
            val isLan = { n: android.net.Network ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
            val candidates = listOfNotNull(cm.activeNetwork?.takeIf(isLan)) + cm.allNetworks.filter(isLan)
            for (network in candidates) {
                val linkProperties = cm.getLinkProperties(network) ?: continue
                for (linkAddress in linkProperties.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress
                        if (!host.isNullOrEmpty()) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    fun generateQrBitmap(content: String, size: Int = 480): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
