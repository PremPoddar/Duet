package com.prempoddar.duet.play

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps ads out of the embedded YouTube player, three ways: requests to ad servers are refused,
 * the embed page gets a small script that strips the ad slots from the player's data before the
 * player reads it, and that script skips any ad that still starts.
 */
internal object NoAds {
    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "imasdk.googleapis.com",
        "googletagservices.com", "adservice.google.com",
    )
    private val blockedPaths = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/api/stats/atr")

    /** A response to use instead of the network's, or null to let the request through. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return empty()
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
            if (blockedPaths.any { path.startsWith(it) }) return empty()
            if (request.method == "GET" && path.startsWith("/embed/")) return runCatching { embed(request) }.getOrNull()
        }
        return null
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Fetches the embed page as the web view would, and adds the script before anything else runs. */
    private fun embed(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        // Compression is left to the connection, which undoes it; the page has to be read as text.
        request.requestHeaders.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }.forEach { (k, v) -> c.setRequestProperty(k, v) }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        try {
            if (c.responseCode != 200) return null
            c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
            val html = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val at = html.indexOf("<head>").takeIf { it >= 0 }?.plus("<head>".length) ?: return null
            val page = html.substring(0, at) + "<script>$SCRIPT</script>" + html.substring(at)
            // Served without YouTube's script policy, which would refuse the added script.
            return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(page.toByteArray()))
        } finally {
            c.disconnect()
        }
    }

    private val SCRIPT = """
        (function () {
          var KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adParams', 'adBreakParams'];
          function prune(o) {
            if (!o || typeof o !== 'object') return o;
            for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) delete o[KEYS[i]];
            if (o.playerResponse) prune(o.playerResponse);
            return o;
          }
          var parse = JSON.parse;
          JSON.parse = function () { return prune(parse.apply(this, arguments)); };
          var json = Response.prototype.json;
          Response.prototype.json = function () { return json.call(this).then(prune); };
          var initial;
          try {
            Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
              get: function () { return initial; }, set: function (v) { initial = prune(v); } });
          } catch (e) {}
          setInterval(function () {
            var p = document.querySelector('.html5-video-player');
            if (!p || !p.classList.contains('ad-showing')) return;
            var v = p.querySelector('video');
            if (v && isFinite(v.duration) && v.duration > 0) { v.muted = true; v.currentTime = v.duration; }
            var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
            if (skip) skip.click();
          }, 200);
        })();
    """.trimIndent().replace("\n", " ")
}
