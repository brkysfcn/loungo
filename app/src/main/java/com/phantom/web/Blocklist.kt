package com.phantom.web

interface Blocklist {
    fun isBlocked(host: String): Boolean
}
