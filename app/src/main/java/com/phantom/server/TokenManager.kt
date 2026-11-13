package com.phantom.server

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object TokenManager {
    private const val PREFS_NAME = "phantom_pair"
    private const val KEY_TOKEN = "pair_token"
    private const val KEY_PAIRED = "paired"
    private val secureRandom = SecureRandom()

    fun isPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)
    }

    fun setPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    fun generateToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun getOrInitToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_TOKEN, null)
        if (token.isNullOrEmpty()) {
            token = generateToken()
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        return token
    }

    fun resetToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newToken = generateToken()
        prefs.edit().putString(KEY_TOKEN, newToken).putBoolean(KEY_PAIRED, false).apply()
        return newToken
    }

    fun validateToken(candidate: String?, validToken: String, allowDevToken: Boolean = false): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val candBytes = candidate.toByteArray(Charsets.UTF_8)
        val validBytes = validToken.toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual(candBytes, validBytes)) {
            return true
        }
        if (allowDevToken && MessageDigest.isEqual(candBytes, DEV_TOKEN.toByteArray(Charsets.UTF_8))) {
            return true
        }
        return false
    }
}
