package net.zer0space.app

import android.net.Uri

/**
 * The one site this app is a shell for. Every privilege the app grants a page
 * (the native bridge, media header rules, installing updates) is bound to
 * exactly this origin, compared as a whole string, never by prefix or suffix.
 */
object AppConfig {
    private val base: Uri = Uri.parse(BuildConfig.BASE_URL)

    val host: String = requireNotNull(base.host) { "BASE_URL has no host" }.lowercase()

    /** "https://zer0space.com", the form WebView origin rules and Origin headers use. */
    val origin: String = buildString {
        append("https://").append(host)
        if (base.port != -1 && base.port != 443) append(':').append(base.port)
    }

    val startUrl: String = "$origin/dashboard"

    /** True only for https URLs on exactly the app's own host and port. */
    fun isAppUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https") return false
        val port = if (uri.port == -1) 443 else uri.port
        val basePort = if (base.port == -1) 443 else base.port
        return uri.host?.lowercase() == host && port == basePort
    }

    /** The app host or any subdomain of it. Native fetches never go there. */
    fun isOwnHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == this.host || h.endsWith(".${this.host}")
    }
}
