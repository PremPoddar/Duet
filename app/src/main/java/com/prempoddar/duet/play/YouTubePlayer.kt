package com.prempoddar.duet.play

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YouTube's own embedded player, in a web view, driven by Duet: load, play, pause and jump, and a
 * steady report of where it is so the phones can be kept together. It's shown on the session
 * screen, as YouTube requires, and streams straight from YouTube on every phone.
 */
@SuppressLint("SetJavaScriptEnabled")
object YouTubePlayer {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    @Volatile var videoId: String? = null; private set
    @Volatile private var reportedMs = 0L
    @Volatile private var reportedAt = 0L
    @Volatile private var state = UNSTARTED
    @Volatile var ready = false; private set

    private val _durationMs = MutableStateFlow(0L)
    /** The video's length once YouTube has said; 0 before. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val playing get() = state == PLAYING
    val ended get() = state == ENDED

    /** Where the video is now: the last report, carried forward while it plays. */
    fun positionMs(): Long = if (playing) reportedMs + (SystemClock.elapsedRealtime() - reportedAt) else reportedMs

    /** The web view to show on screen; made once and reused. */
    fun view(context: Context): WebView = web ?: WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(android.graphics.Color.BLACK)
        addJavascriptInterface(Bridge(), "Duet")
        // The page's messages go to the system log, to see why a video won't play.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.w("DuetYouTube", "${m.message()} (${m.lineNumber()})")
                return true
            }
        }
        // A real origin, so the embedded player accepts the page as a site embedding it.
        loadDataWithBaseURL("https://prempoddar.github.io/", PAGE, "text/html", "utf-8", null)
        web = this
    }

    fun load(id: String, startMs: Long, play: Boolean) {
        videoId = id; state = UNSTARTED; reportedMs = startMs; reportedAt = SystemClock.elapsedRealtime()
        _durationMs.value = 0; _error.value = null
        val seconds = startMs / 1000.0
        js(if (play) "load('$id',$seconds)" else "cue('$id',$seconds)")
    }

    fun play() = js("player&&player.playVideo()")
    fun pause() = js("player&&player.pauseVideo()")
    fun seek(ms: Long) {
        reportedMs = ms; reportedAt = SystemClock.elapsedRealtime()
        js("player&&player.seekTo(${ms / 1000.0},true)")
    }

    fun stop() {
        if (videoId == null) return
        videoId = null; state = UNSTARTED
        js("player&&player.stopVideo()")
    }

    // Commands wait until the page has defined what they call.
    private var pageReady = false
    private val waiting = ArrayList<String>()

    private fun js(code: String) = main.post {
        if (pageReady) web?.evaluateJavascript(code, null) else waiting += code
    }

    private class Bridge {
        @JavascriptInterface fun ready() { YouTubePlayer.ready = true }
        @JavascriptInterface fun page() = main.post {
            pageReady = true
            waiting.forEach { web?.evaluateJavascript(it, null) }
            waiting.clear()
        }
        @JavascriptInterface fun time(ms: Long, duration: Long, s: Int) {
            reportedMs = ms; reportedAt = SystemClock.elapsedRealtime(); state = s
            if (duration > 0) _durationMs.value = duration
        }
        @JavascriptInterface fun error(code: Int) {
            _error.value = when (code) {
                100 -> "This video isn't available any more"
                101, 150, 152, 153 -> "The video's owner doesn't allow it to play in other apps"
                else -> "YouTube couldn't play this video (code $code)"
            }
        }
    }

    // YouTube player states.
    private const val UNSTARTED = -1
    private const val ENDED = 0
    private const val PLAYING = 1

    private val PAGE = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;display:block}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        // The player is made with its first video: made empty, YouTube reports a bad parameter.
        var player = null, apiReady = false, pending = null;
        function onYouTubeIframeAPIReady() { console.log('api ready'); apiReady = true; if (pending) { pending(); pending = null; } }
        function make(id, s, auto) {
          console.log('make ' + id + ' at ' + s + ' auto=' + auto);
          player = new YT.Player('p', { width: '100%', height: '100%', videoId: id,
            playerVars: { playsinline: 1, controls: 0, disablekb: 1, rel: 0, fs: 0, iv_load_policy: 3, autoplay: auto ? 1 : 0, start: Math.floor(s), origin: 'https://prempoddar.github.io' },
            events: {
              onReady: function () { console.log('player ready'); Duet.ready(); if (s % 1 > 0) player.seekTo(s, true); if (auto) player.playVideo(); },
              onError: function (e) { console.log('error ' + e.data); Duet.error(e.data); }
            } });
        }
        function show(id, s, auto) {
          var go = function () {
            if (!player) make(id, s, auto);
            else if (auto) player.loadVideoById({ videoId: id, startSeconds: s });
            else player.cueVideoById({ videoId: id, startSeconds: s });
          };
          if (apiReady) go(); else pending = go;
        }
        function load(id, s) { show(id, s, true); }
        function cue(id, s) { show(id, s, false); }
        Duet.page();
        setInterval(function () {
          if (player && player.getCurrentTime) Duet.time(Math.round(player.getCurrentTime() * 1000), Math.round((player.getDuration() || 0) * 1000), player.getPlayerState());
        }, 200);
        </script></body></html>
    """.trimIndent()
}
