package com.phantom.web

fun toDesktopUserAgent(defaultUa: String): String {
    var ua = defaultUa.replace("; wv", "")
    ua = ua.replace(Regex("""\(Linux;[^)]*Android[^)]*\)"""), "(X11; Linux x86_64)")
    ua = ua.replace(" Mobile", "")
    return ua
}
