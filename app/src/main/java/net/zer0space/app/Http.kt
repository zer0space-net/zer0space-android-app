package net.zer0space.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp clients. All of them resolve DNS through [UrlGuard.dns]. */
object Http {
    @Volatile
    var userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    // Re-checks every hop, so a public URL cannot redirect into the LAN.
    private val guardEachHop = Interceptor { chain ->
        UrlGuard.check(chain.request().url.toString())
        chain.proceed(chain.request())
    }

    /** Source fetches. Short timeouts: a hoster that hangs is a hoster we skip. */
    val fetch: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(UrlGuard.dns)
            .addNetworkInterceptor(guardEachHop)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Same, but carrying the bridge's own cookie jar (fetch credentials: "include"). */
    val fetchWithCookies: OkHttpClient by lazy {
        fetch.newBuilder().cookieJar(BridgeCookies).build()
    }

    /**
     * Media segments and the in-app update download. No call timeout: a
     * long mp4 or a 10 MB APK on a slow line is legitimate; the read timeout
     * still ends a stalled transfer.
     */
    val stream: OkHttpClient by lazy {
        fetch.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * The bridge's cookies, in memory only and separate from the WebView's store.
 *
 * Hoster sites set cookies during a resolve (s.to's XSRF token, for example)
 * and expect them back on the next request. They are kept apart from the
 * WebView so a hoster can never see or set anything on zer0space.com, and
 * dropped when the process ends.
 */
object BridgeCookies : CookieJar {
    private val store = HashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.topPrivateDomain() ?: url.host) { mutableListOf() }
        for (c in cookies) {
            list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            list.add(c)
        }
        if (list.size > 60) list.subList(0, list.size - 60).clear()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = store[url.topPrivateDomain() ?: url.host] ?: return emptyList()
        list.removeAll { it.expiresAt < now }
        return list.filter { it.matches(url) }
    }

    /** For stacks without a cookie jar (Cronet): the Cookie header value, if any. */
    fun headerFor(url: HttpUrl): String? =
        loadForRequest(url).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }

    fun saveSetCookieHeaders(url: HttpUrl, values: List<String>) {
        val parsed = values.mapNotNull { Cookie.parse(url, it) }
        if (parsed.isNotEmpty()) saveFromResponse(url, parsed)
    }
}
