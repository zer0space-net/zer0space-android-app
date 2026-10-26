package net.zer0space.app

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class BlockedUrlException(message: String) : IOException(message)

/**
 * Keeps the native fetcher pointed at the public internet.
 *
 * The bridge lets the zer0space site ask the phone to fetch arbitrary URLs:
 * that is the whole point, the phone's own IP resolves the sources. Without a
 * guard, the same primitive would let anything that runs script on the site
 * read the user's home network (router admin pages, a NAS, Tailscale peers)
 * through the phone. So every request, and every redirect hop, has to go to a
 * public address:
 *
 *  - only http/https, never the app's own host;
 *  - no LAN-only names (localhost, *.local, *.lan, *.internal, *.home.arpa,
 *    Tailscale MagicDNS *.ts.net);
 *  - DNS answers are filtered: a public name that resolves to a private,
 *    loopback, link-local, CGNAT (Tailscale's 100.64/10) or ULA address is
 *    refused, which also defeats DNS rebinding for OkHttp.
 */
object UrlGuard {

    private val blockedSuffixes = listOf(
        ".local", ".localhost", ".lan", ".internal", ".home.arpa", ".intranet",
        ".corp", ".ts.net", ".home",
    )

    @Throws(BlockedUrlException::class)
    fun check(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw BlockedUrlException("not an http(s) URL")
        val host = url.host.lowercase().trimEnd('.')
        if (host.isEmpty() || host == "localhost") throw BlockedUrlException("blocked host")
        if (blockedSuffixes.any { host.endsWith(it) }) throw BlockedUrlException("blocked host")
        if (AppConfig.isOwnHost(host)) throw BlockedUrlException("own host")
        if (isIpLiteral(host)) {
            val addr = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                throw BlockedUrlException("bad address")
            }
            if (!isPublic(addr)) throw BlockedUrlException("private address")
        }
        return url
    }

    /** Blocking DNS check, for stacks (Cronet) whose resolver we cannot hook. */
    @Throws(IOException::class)
    fun checkResolved(url: HttpUrl) {
        dns.lookup(url.host)
    }

    val dns: Dns = Dns { hostname ->
        val all = Dns.SYSTEM.lookup(hostname)
        val ok = all.filter { isPublic(it) }
        if (ok.isEmpty()) throw UnknownHostException("$hostname has no public address")
        ok
    }

    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    fun isPublic(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return false
        val b = addr.address
        return when (addr) {
            is Inet4Address -> isPublicV4(b)
            is Inet6Address -> {
                // IPv4-mapped (::ffff:a.b.c.d): judge the embedded v4 address.
                val mapped = b.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                    b[10].toInt() == -1 && b[11].toInt() == -1
                if (mapped) return isPublicV4(b.copyOfRange(12, 16))
                val first = b[0].toInt() and 0xff
                // fc00::/7 unique-local, fec0::/10 site-local (deprecated).
                if (first and 0xfe == 0xfc) return false
                if (first == 0xfe && (b[1].toInt() and 0xc0) == 0xc0) return false
                // 64:ff9b::/96 NAT64 can reach private v4 space on some carriers;
                // the rare hoster on it is not worth that.
                if (first == 0x00 && b[1].toInt() == 0x64 && (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b) return false
                true
            }
            else -> false
        }
    }

    private fun isPublicV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        return when {
            a0 == 0 -> false                         // 0.0.0.0/8
            a0 == 10 -> false                        // 10/8
            a0 == 127 -> false                       // loopback
            a0 == 100 && a1 in 64..127 -> false      // 100.64/10 CGNAT, Tailscale
            a0 == 169 && a1 == 254 -> false          // link-local
            a0 == 172 && a1 in 16..31 -> false       // 172.16/12
            a0 == 192 && a1 == 168 -> false          // 192.168/16
            a0 == 192 && a1 == 0 && (b[2].toInt() and 0xff) == 0 -> false // 192.0.0/24
            a0 == 198 && (a1 == 18 || a1 == 19) -> false // 198.18/15 benchmarking
            a0 >= 224 -> false                       // multicast + reserved
            else -> true
        }
    }
}
