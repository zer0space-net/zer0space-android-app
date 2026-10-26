package net.zer0space.app

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.net.CronetProviderInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `CrimsonExtension.fetch()`, done by the phone.
 *
 * This is what makes "resolve on your own device" work: the request leaves
 * from the phone's IP, with whatever Referer/Origin/User-Agent the source
 * needs, and no CORS in the way. It answers in the exact `FetchResult` shape
 * crimson-sources expects from its companion extension (types.ts), so the
 * engine cannot tell the difference.
 *
 * Chrome's own network stack (Cronet, from Play services) is used when the
 * phone has it, because Cloudflare-fronted hosters check the TLS fingerprint
 * and Cronet's is Chrome's. OkHttp is the fallback.
 */
object NativeFetcher {
    private const val TAG = "zs.fetch"
    private const val MAX_BODY = 25 * 1024 * 1024
    private const val MAX_URL = 16 * 1024

    // Headers the page may not set: the stack owns them, or they would leak
    // the zer0space session to a third party.
    private val droppedHeaders = setOf(
        "host", "cookie", "cookie2", "content-length", "connection", "keep-alive",
        "transfer-encoding", "te", "upgrade", "proxy-authorization", "proxy-connection",
        "accept-encoding", "expect",
    )

    @Volatile
    private var cronet: CronetEngine? = null
    private val executor = Executors.newFixedThreadPool(4)
    private val limiter = Semaphore(8)

    fun init(context: Context) {
        val app = context.applicationContext
        try {
            CronetProviderInstaller.installProvider(app).addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.i(TAG, "Cronet provider unavailable, using OkHttp")
                    return@addOnCompleteListener
                }
                try {
                    cronet = CronetEngine.Builder(app)
                        .setUserAgent(Http.userAgent)
                        .enableHttp2(true)
                        .enableQuic(true)
                        .enableBrotli(true)
                        .build()
                    Log.i(TAG, "Cronet ready")
                } catch (e: Throwable) {
                    Log.i(TAG, "Cronet build failed, using OkHttp: $e")
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "Cronet not available: $e")
        }
    }

    val engineName: String get() = if (cronet != null) "cronet" else "okhttp"

    private class Req(
        val url: HttpUrl,
        val method: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
        val redirect: String,
        val binary: Boolean,
        val withCookies: Boolean,
    )

    private class Res(
        val status: Int,
        val statusText: String,
        val url: String,
        val redirected: Boolean,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    suspend fun fetch(rawUrl: String, opts: JSONObject): JSONObject = limiter.withPermit {
        withContext(Dispatchers.IO) {
            if (rawUrl.length > MAX_URL) throw BlockedUrlException("URL too long")
            val url = UrlGuard.check(rawUrl)
            val req = parse(url, opts)
            val engine = cronet
            val res = if (engine != null) {
                UrlGuard.checkResolved(url)
                viaCronet(engine, req)
            } else {
                viaOkHttp(req)
            }
            toJson(res, req.binary)
        }
    }

    private fun parse(url: HttpUrl, opts: JSONObject): Req {
        val method = opts.optString("method", "GET").uppercase().ifBlank { "GET" }
        require(method in setOf("GET", "POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            "method not allowed"
        }
        val headers = mutableListOf<Pair<String, String>>()
        opts.optJSONObject("headers")?.let { h ->
            for (key in h.keys()) {
                val name = key.trim()
                val value = h.optString(key)
                if (name.isEmpty() || name.lowercase() in droppedHeaders) continue
                if (!name.matches(Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$"))) continue
                if (value.contains('\r') || value.contains('\n')) continue
                headers += name to value
            }
        }
        if (headers.none { it.first.equals("User-Agent", true) }) {
            headers += "User-Agent" to Http.userAgent
        }
        val body = if (opts.has("body") && !opts.isNull("body")) {
            opts.optString("body").toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        if (body != null && headers.none { it.first.equals("Content-Type", true) }) {
            // What a browser's fetch() sends for a string body.
            headers += "Content-Type" to "text/plain;charset=UTF-8"
        }
        return Req(
            url = url,
            method = method,
            headers = headers,
            body = if (method == "GET" || method == "HEAD") null else body,
            redirect = opts.optString("redirect", "follow"),
            binary = opts.optString("responseType", "text") == "arraybuffer",
            withCookies = opts.optString("credentials", "omit") == "include",
        )
    }

    // --- Cronet --------------------------------------------------------------

    private suspend fun viaCronet(engine: CronetEngine, req: Req): Res =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val out = ByteArrayOutputStream()
            var redirected = false

            fun finish(result: Result<Res>) {
                if (!done.compareAndSet(false, true)) return
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            fun saveCookies(info: UrlResponseInfo) {
                if (!req.withCookies) return
                val url = UrlGuard.check(info.url)
                val values = info.allHeadersAsList
                    .filter { it.key.equals("Set-Cookie", true) }
                    .map { it.value }
                BridgeCookies.saveSetCookieHeaders(url, values)
            }

            fun result(info: UrlResponseInfo, body: ByteArray) = Res(
                status = info.httpStatusCode,
                statusText = info.httpStatusText ?: "",
                url = info.url,
                redirected = redirected,
                headers = info.allHeadersAsList.map { it.key to it.value },
                body = body,
            )

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String,
                ) {
                    try {
                        saveCookies(info)
                    } catch (_: Exception) {
                    }
                    when (req.redirect) {
                        "manual" -> {
                            request.cancel()
                            finish(Result.success(result(info, ByteArray(0))))
                        }
                        "error" -> {
                            request.cancel()
                            finish(Result.failure(IOException("redirect not allowed")))
                        }
                        else -> try {
                            val next = UrlGuard.check(newLocationUrl)
                            UrlGuard.checkResolved(next)
                            redirected = true
                            request.followRedirect()
                        } catch (e: IOException) {
                            request.cancel()
                            finish(Result.failure(e))
                        }
                    }
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    try {
                        saveCookies(info)
                    } catch (_: Exception) {
                    }
                    request.read(ByteBuffer.allocateDirect(64 * 1024))
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer,
                ) {
                    byteBuffer.flip()
                    val chunk = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(chunk)
                    out.write(chunk)
                    if (out.size() > MAX_BODY) {
                        request.cancel()
                        finish(Result.failure(IOException("response too large")))
                        return
                    }
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    finish(Result.success(result(info, out.toByteArray())))
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException,
                ) {
                    finish(Result.failure(IOException(error.message ?: "network error")))
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    finish(Result.failure(IOException("canceled")))
                }
            }

            val builder = engine.newUrlRequestBuilder(req.url.toString(), callback, executor)
                .setHttpMethod(req.method)
                .disableCache()
            for ((k, v) in req.headers) builder.addHeader(k, v)
            if (req.withCookies) {
                BridgeCookies.headerFor(req.url)?.let { builder.addHeader("Cookie", it) }
            }
            req.body?.let { builder.setUploadDataProvider(UploadDataProviders.create(it), executor) }
            val request = builder.build()
            cont.invokeOnCancellation { request.cancel() }
            request.start()
        }

    // --- OkHttp --------------------------------------------------------------

    private fun viaOkHttp(req: Req): Res {
        var client = if (req.withCookies) Http.fetchWithCookies else Http.fetch
        if (req.redirect != "follow") {
            client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        val contentType = req.headers.firstOrNull { it.first.equals("Content-Type", true) }?.second
        val body = req.body?.toRequestBody(contentType?.toMediaTypeOrNull())
        val builder = Request.Builder().url(req.url).method(
            req.method,
            body ?: if (req.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null,
        )
        for ((k, v) in req.headers) builder.addHeader(k, v)
        client.newCall(builder.build()).execute().use { resp ->
            if (req.redirect == "error" && resp.isRedirect) throw IOException("redirect not allowed")
            val bytes = ByteArrayOutputStream()
            resp.body?.byteStream()?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                    if (bytes.size() > MAX_BODY) throw IOException("response too large")
                }
            }
            return Res(
                status = resp.code,
                statusText = resp.message,
                url = resp.request.url.toString(),
                redirected = resp.priorResponse != null,
                headers = resp.headers.map { it.first to it.second },
                body = bytes.toByteArray(),
            )
        }
    }

    // --- result -------------------------------------------------------------

    private fun toJson(res: Res, binary: Boolean): JSONObject {
        val headers = JSONObject()
        for ((k, v) in res.headers) {
            val key = k.lowercase()
            if (key == "set-cookie" || key == "set-cookie2") continue // a browser hides these too
            headers.put(key, if (headers.has(key)) headers.getString(key) + ", " + v else v)
        }
        val body: String
        val encoding: String
        if (binary) {
            body = Base64.encodeToString(res.body, Base64.NO_WRAP)
            encoding = "base64"
        } else {
            body = String(res.body, charsetOf(headers.optString("content-type")))
            encoding = "text"
        }
        return JSONObject()
            .put("ok", res.status in 200..299)
            .put("status", res.status)
            .put("statusText", res.statusText)
            .put("url", res.url)
            .put("redirected", res.redirected)
            .put("headers", headers)
            .put("body", body)
            .put("bodyEncoding", encoding)
    }

    private fun charsetOf(contentType: String): Charset {
        val name = Regex("""charset=["']?([\w.-]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        return try {
            if (name != null) Charset.forName(name) else Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }
}
