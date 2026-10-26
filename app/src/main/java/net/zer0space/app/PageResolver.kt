package net.zer0space.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * A real browser page nobody sees. Two jobs:
 *
 *  - [captureMedia], the extension's `resolveInPage`: open a hoster embed,
 *    let it run its own JavaScript (proof-of-work, obfuscation, SPA players)
 *    and report the first `.m3u8`/`.mp4` it requests.
 *  - [followGate], new in the app: open a link that sits behind a Cloudflare
 *    Turnstile gate (s.to's `/r?t=…`) and report where it finally navigates.
 *    On a residential or mobile IP the managed challenge passes on its own in
 *    a real browser, which is exactly what the homelab's IP can no longer do.
 *
 * The WebView is attached behind the visible one, full size, so the page sees
 * itself as visible (players that only start when `document.hidden` is false
 * do start) while the user sees nothing. It is muted, cannot open windows,
 * gets no bridge, and is destroyed after every run. One run at a time: a
 * second request waits, and the Turnstile clearance cookie the first run
 * earned makes the next one fast.
 */
class PageResolver(private val host: FrameLayout) {

    private val mutex = Mutex()

    suspend fun captureMedia(url: String, timeoutMs: Long, mustInclude: List<String>, framed: Boolean): JSONObject {
        UrlGuard.check(url)
        return mutex.withLock { run(Mode.Media(mustInclude), url, timeoutMs, framed) }
    }

    suspend fun followGate(url: String, timeoutMs: Long): JSONObject {
        val gateHost = UrlGuard.check(url).host.lowercase()
        return mutex.withLock { run(Mode.Gate(gateHost), url, timeoutMs, framed = false) }
    }

    private sealed class Mode {
        class Media(val mustInclude: List<String>) : Mode()
        class Gate(val gateHost: String) : Mode()
    }

    private suspend fun run(mode: Mode, url: String, timeoutMs: Long, framed: Boolean): JSONObject {
        val result = CompletableDeferred<JSONObject>()
        var view: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                view = build(mode, result).also { wv ->
                    host.addView(
                        wv,
                        0,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    if (framed) {
                        wv.loadDataWithBaseURL(FRAME_BASE, frameHtml(url), "text/html", "utf-8", null)
                    } else {
                        wv.loadUrl(url)
                    }
                }
            }
            return withTimeoutOrNull(timeoutMs) { result.await() }
                ?: JSONObject().put("ok", false).put("error", "timeout")
        } finally {
            withContext(Dispatchers.Main) {
                view?.let { wv ->
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        host.removeView(wv)
                        wv.destroy()
                    } catch (e: Exception) {
                        Log.w(TAG, "teardown: $e")
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(mode: Mode, result: CompletableDeferred<JSONObject>): WebView {
        val wv = WebView(host.context)
        wv.alpha = 0f
        wv.isFocusable = false
        wv.importantForAccessibility = WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = Http.userAgent
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        // Safe Browsing would put an interstitial in front of a flagged hoster
        // and stall the resolve. Nothing here is ever shown to the user, it
        // cannot download files, and it holds no privileges.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, MUTE_SCRIPT, setOf("*"))
        }

        fun headersOf(req: WebResourceRequest): JSONObject {
            val h = JSONObject()
            for ((k, v) in req.requestHeaders) {
                when (k.lowercase()) {
                    "referer" -> h.put("referer", v)
                    "origin" -> h.put("origin", v)
                    "user-agent" -> h.put("userAgent", v)
                }
            }
            if (!h.has("userAgent")) h.put("userAgent", Http.userAgent)
            return h
        }

        fun offGate(target: String?): Boolean {
            if (mode !is Mode.Gate || target == null) return false
            val uri = Uri.parse(target)
            if (uri.scheme != "https" && uri.scheme != "http") return false
            val h = uri.host?.lowercase() ?: return false
            if (h == mode.gateHost || h.endsWith(".${mode.gateHost}")) return false
            if (h == "challenges.cloudflare.com") return false
            return try {
                UrlGuard.check(target)
                true
            } catch (_: Exception) {
                false
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val target = req.url.toString()
                // A hoster page must not reach into the phone's LAN either.
                val h = req.url.host?.lowercase() ?: ""
                if (h == "localhost" || (UrlGuard.isIpLiteral(h) && !isPublicLiteral(h))) return blocked()
                when (mode) {
                    is Mode.Gate -> if (req.isForMainFrame && offGate(target)) {
                        result.complete(JSONObject().put("ok", true).put("url", target))
                        return blocked()
                    }
                    is Mode.Media -> if (looksLikeMedia(target, mode.mustInclude)) {
                        result.complete(
                            JSONObject()
                                .put("ok", true)
                                .put("url", target)
                                .put("streamType", if (target.contains(".mp4", true)) "mp4" else "hls")
                                .put("headers", headersOf(req)),
                        )
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val target = req.url.toString()
                if (req.isForMainFrame && offGate(target)) {
                    result.complete(JSONObject().put("ok", true).put("url", target))
                    return true
                }
                // intent:, market: and friends: never leave this invisible page.
                return req.url.scheme != "https" && req.url.scheme != "http"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (offGate(url)) result.complete(JSONObject().put("ok", true).put("url", url))
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onJsAlert(view: WebView, url: String?, message: String?, r: JsResult): Boolean {
                r.cancel(); return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, r: JsResult): Boolean {
                r.cancel(); return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, d: String?, r: JsPromptResult): Boolean {
                r.cancel(); return true
            }
            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, r: JsResult): Boolean {
                r.confirm(); return true
            }
        }
        wv.setDownloadListener { _, _, _, _, _ -> /* never */ }
        return wv
    }

    private fun isPublicLiteral(h: String): Boolean = try {
        UrlGuard.isPublic(java.net.InetAddress.getByName(h))
    } catch (_: Exception) {
        false
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun looksLikeMedia(url: String, mustInclude: List<String>): Boolean {
        val path = url.substringBefore('#').lowercase()
        val media = Regex("""\.(m3u8|mp4|m4v|mpd)(\?|$)""").containsMatchIn(path)
        if (!media) return false
        return mustInclude.isEmpty() || mustInclude.any { url.contains(it, ignoreCase = true) }
    }

    private fun frameHtml(url: String): String {
        val safe = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\">" +
            "<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style>" +
            "</head><body><iframe src=\"$safe\" allow=\"autoplay; fullscreen; encrypted-media\" " +
            "referrerpolicy=\"origin\"></iframe></body></html>"
    }

    companion object {
        private const val TAG = "zs.resolver"
        private const val FRAME_BASE = "https://resolver.zer0space.invalid/"

        // Every frame, every origin: whatever plays here plays silently.
        private const val MUTE_SCRIPT = """
            (function () {
              try {
                var p = HTMLMediaElement.prototype, play = p.play;
                p.play = function () { try { this.muted = true; this.volume = 0; } catch (e) {} return play.apply(this, arguments); };
                document.addEventListener('play', function (e) { try { e.target.muted = true; } catch (x) {} }, true);
              } catch (e) {}
            })();
        """
    }
}
