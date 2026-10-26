package net.zer0space.app

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * `installMediaRules` / `clearMediaRules`: the native twin of the extension's
 * declarativeNetRequest rules.
 *
 * When a stream was resolved on the phone, the player loads the raw CDN URL
 * directly (bytes go CDN → phone, never through the tunnel). Those CDNs check
 * Referer/Origin/User-Agent and send no CORS headers, so a plain page request
 * would fail. A rule says "for requests to these hosts, send these headers";
 * the WebView hands every matching request to [intercept], which performs it
 * natively with the headers and answers the page with CORS opened for the
 * app's own origin only.
 *
 * Limits, all deliberate:
 *  - only GET/HEAD, only public hosts ([UrlGuard]), never the app's own host;
 *  - rules can only set a short allow-list of headers, so a rule cannot add
 *    Cookie or Authorization to someone else's request;
 *  - at most [MAX_RULES], cleared at the start of every episode by the engine.
 */
class MediaRules {

    private data class Rule(
        val id: Int,
        val domains: List<String>,
        val urlPattern: Regex?,
        val headers: Map<String, String>,
    )

    private val rules = CopyOnWriteArrayList<Rule>()
    private val nextId = AtomicInteger(1)

    fun install(arr: JSONArray, replace: Boolean): List<Int> {
        if (replace) rules.clear()
        val ids = mutableListOf<Int>()
        for (i in 0 until minOf(arr.length(), MAX_RULES)) {
            val o = arr.optJSONObject(i) ?: continue
            val domains = mutableListOf<String>()
            o.optJSONArray("requestDomains")?.let { d ->
                for (j in 0 until minOf(d.length(), 16)) {
                    val host = d.optString(j).lowercase().trim().trimEnd('.')
                    if (host.isEmpty() || AppConfig.isOwnHost(host)) continue
                    if (!host.matches(Regex("^[a-z0-9.-]{1,253}$")) && !UrlGuard.isIpLiteral(host)) continue
                    domains += host
                }
            }
            val pattern = o.optString("urlFilter").takeIf { it.isNotBlank() }?.let { dnrFilterToRegex(it) }
            // A rule with neither a domain nor a filter would match everything.
            if (domains.isEmpty() && pattern == null) continue
            val headers = linkedMapOf<String, String>()
            o.optJSONObject("requestHeaders")?.let { h ->
                for (key in h.keys()) {
                    val name = key.trim()
                    val value = h.optString(key)
                    if (!allowedHeader(name) || value.contains('\r') || value.contains('\n')) continue
                    headers[name] = value
                }
            }
            val id = nextId.getAndIncrement()
            rules += Rule(id, domains, pattern, headers)
            ids += id
        }
        while (rules.size > MAX_RULES) rules.removeAt(0)
        return ids
    }

    fun clear(ids: JSONArray?) {
        if (ids == null) {
            rules.clear()
            return
        }
        val drop = (0 until ids.length()).map { ids.optInt(it) }.toSet()
        rules.removeAll { it.id in drop }
    }

    private fun match(url: Uri): Rule? {
        val host = url.host?.lowercase() ?: return null
        val full = url.toString()
        return rules.lastOrNull { r ->
            val domainOk = r.domains.isEmpty() || r.domains.any { host == it || host.endsWith(".$it") }
            val patternOk = r.urlPattern?.containsMatchIn(full) ?: true
            domainOk && patternOk
        }
    }

    /** Called on a WebView worker thread for every request of the main WebView. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (rules.isEmpty()) return null
        val url = request.url
        if (url.scheme != "https" && url.scheme != "http") return null
        val rule = match(url) ?: return null
        val method = request.method.uppercase()

        if (method == "OPTIONS") return preflight()
        if (method != "GET" && method != "HEAD") return null

        return try {
            val httpUrl = UrlGuard.check(url.toString())
            val builder = Request.Builder().url(httpUrl).method(method, null)
            // Keep what the player asked for (Range above all), drop identity.
            for ((k, v) in request.requestHeaders) {
                val lk = k.lowercase()
                if (lk in pageHeadersDropped) continue
                builder.header(k, v)
            }
            if (rule.headers.keys.none { it.equals("User-Agent", true) }) {
                builder.header("User-Agent", Http.userAgent)
            }
            for ((k, v) in rule.headers) builder.header(k, v)

            val resp = Http.stream.newCall(builder.build()).execute()
            val code = resp.code
            // WebResourceResponse rejects 3xx and odd codes; redirects were
            // already followed, anything else becomes a clean upstream error.
            val status = if (code in 200..299 || code in 400..599) code else 502
            val headers = linkedMapOf<String, String>()
            for ((k, v) in resp.headers) {
                val lk = k.lowercase()
                if (lk in responseHeadersDropped) continue
                headers[k] = if (headers.containsKey(k)) headers[k] + ", " + v else v
            }
            headers["Access-Control-Allow-Origin"] = AppConfig.origin
            headers["Access-Control-Expose-Headers"] = "Content-Length, Content-Range, Accept-Ranges, Content-Type"
            headers["Vary"] = "Origin"
            val contentType = resp.header("Content-Type") ?: "application/octet-stream"
            val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = Regex("""charset=([\w.-]+)""", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            val reason = resp.message.ifBlank { if (status < 400) "OK" else "Error" }
            val stream = resp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            WebResourceResponse(mime, charset, status, reason, headers, stream)
        } catch (e: Exception) {
            Log.w(TAG, "media relay failed for ${url.host}: $e")
            WebResourceResponse(
                "text/plain", "utf-8", 502, "Bad Gateway",
                mapOf("Access-Control-Allow-Origin" to AppConfig.origin),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    private fun preflight() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content",
        mapOf(
            "Access-Control-Allow-Origin" to AppConfig.origin,
            "Access-Control-Allow-Methods" to "GET, HEAD",
            "Access-Control-Allow-Headers" to "Range, Accept, Content-Type",
            "Access-Control-Max-Age" to "600",
        ),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val TAG = "zs.media"
        const val MAX_RULES = 64

        private val pageHeadersDropped = setOf(
            "cookie", "authorization", "origin", "referer", "user-agent", "host",
            "accept-encoding", "if-none-match", "if-modified-since", "if-range",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
        )
        private val responseHeadersDropped = setOf(
            "set-cookie", "set-cookie2", "content-encoding", "transfer-encoding", "connection",
            "access-control-allow-origin", "access-control-allow-credentials",
            "access-control-expose-headers", "vary", "keep-alive",
        )

        fun allowedHeader(name: String): Boolean {
            val n = name.lowercase()
            return n in setOf("referer", "origin", "user-agent", "accept", "accept-language", "x-requested-with") ||
                n.startsWith("sec-fetch-") || n.startsWith("sec-ch-")
        }

        /** DNR urlFilter syntax: `||` domain anchor, `|` start/end anchor, `*` wildcard, `^` separator. */
        fun dnrFilterToRegex(filter: String): Regex? {
            if (filter.length > 512) return null
            var f = filter
            val sb = StringBuilder()
            when {
                f.startsWith("||") -> {
                    sb.append("""^[a-z][a-z0-9+.-]*://([^/]*\.)?""")
                    f = f.substring(2)
                }
                f.startsWith("|") -> {
                    sb.append('^')
                    f = f.substring(1)
                }
            }
            val endAnchor = f.endsWith("|")
            if (endAnchor) f = f.dropLast(1)
            for (c in f) {
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("""(?:[^\w.%-]|$)""")
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            if (endAnchor) sb.append('$')
            return try {
                Regex(sb.toString(), RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }
        }

        fun toJsonIds(ids: List<Int>): JSONObject =
            JSONObject().put("ok", true).put("ruleIds", JSONArray(ids))
    }
}
