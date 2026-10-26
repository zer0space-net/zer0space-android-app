package net.zer0space.app

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native end of `window.CrimsonExtension` and `window.zer0spaceApp`.
 *
 * The page side lives in assets/bridge.js. The two talk over androidx.webkit's
 * WebMessageListener, which, unlike the old addJavascriptInterface, is bound
 * to an origin allow-list: WebView only creates the `zs_native` object in
 * frames whose origin is exactly [AppConfig.origin]. A hoster iframe inside
 * Crimson, an ad, or any other site the WebView ends up on gets nothing.
 * Messages are additionally re-checked here (origin string, main frame only),
 * so a mistake in one layer does not open the other.
 *
 * Every method is a request/response pair keyed by `id`; `emit` pushes events
 * (media buttons, update progress) the other way.
 */
class Bridge(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val media: MediaRules,
    private val resolver: PageResolver,
) : WebViewCompat.WebMessageListener {

    @Volatile
    private var lastReply: JavaScriptReplyProxy? = null

    private val updater = Updater(activity) { data -> emit("update", data) }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || !sameOrigin(sourceOrigin)) return
        val data = message.data ?: return
        if (data.length > MAX_MESSAGE) return
        val msg = try {
            JSONObject(data)
        } catch (_: Exception) {
            return
        }
        lastReply = replyProxy
        val id = msg.optLong("id", 0)
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()

        scope.launch {
            val reply = JSONObject().put("id", id)
            try {
                val result = handle(method, params)
                reply.put("ok", true).put("result", result ?: JSONObject.NULL)
            } catch (e: Exception) {
                Log.d(TAG, "$method failed: $e")
                reply.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            if (id > 0) {
                withContext(Dispatchers.Main) {
                    try {
                        replyProxy.postMessage(reply.toString())
                    } catch (_: Exception) {
                        // The page navigated away; nobody is waiting any more.
                    }
                }
            }
        }
    }

    private fun sameOrigin(origin: Uri): Boolean {
        val s = origin.toString().trimEnd('/')
        return s == AppConfig.origin
    }

    private suspend fun handle(method: String, p: JSONObject): Any? = when (method) {
        "hello" -> JSONObject()
            .put("ok", true)
            .put("protocol", 2)
            .put("version", "app-${BuildConfig.VERSION_NAME}")
            .put("enabled", true)
            .put("engine", NativeFetcher.engineName)

        "status" -> true

        "fetch" -> NativeFetcher.fetch(p.optString("url"), p.optJSONObject("opts") ?: JSONObject())

        "installMediaRules" -> MediaRules.toJsonIds(
            media.install(p.optJSONArray("rules") ?: JSONArray(), p.optBoolean("replace", false)),
        )

        "clearMediaRules" -> {
            media.clear(if (p.isNull("ids")) null else p.optJSONArray("ids"))
            true
        }

        "resolveInPage" -> {
            val must = mutableListOf<String>()
            p.optJSONArray("mustInclude")?.let { a -> for (i in 0 until minOf(a.length(), 16)) must += a.optString(i) }
            resolver.captureMedia(
                p.optString("url"),
                p.optLong("timeoutMs", 30_000).coerceIn(3_000, 90_000),
                must.filter { it.isNotBlank() },
                p.optBoolean("frame", false),
            )
        }

        "resolveRedirect" -> resolver.followGate(
            p.optString("url"),
            p.optLong("timeoutMs", 25_000).coerceIn(3_000, 60_000),
        )

        "media.update" -> {
            MediaHub.update(activity.applicationContext, p)
            null
        }

        "app.info" -> JSONObject()
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("packageName", activity.packageName)
            .put("engine", NativeFetcher.engineName)

        "app.installUpdate" -> updater.install(
            p.optString("url"),
            p.optString("sha256"),
            p.optLong("versionCode", 0),
        )

        else -> throw IllegalArgumentException("unknown method")
    }

    /** Push an event to the page that talked to us last. */
    fun emit(event: String, data: JSONObject) {
        val msg = JSONObject().put("event", event).put("data", data).toString()
        activity.runOnUiThread {
            try {
                lastReply?.postMessage(msg)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "zs.bridge"
        private const val MAX_MESSAGE = 4 * 1024 * 1024
    }
}
