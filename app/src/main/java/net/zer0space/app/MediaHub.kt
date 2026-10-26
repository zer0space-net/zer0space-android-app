package net.zer0space.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject

/**
 * What is playing, as the page reports it through the Media Session API.
 *
 * The page code does not know about the app: zer0space Music already calls
 * `navigator.mediaSession` (metadata, playbackState, setPositionState, action
 * handlers) for the browser's lock-screen controls. The bridge script wraps
 * that API and forwards every change here; [PlaybackService] turns it into a
 * system media session + notification, and button presses travel back to the
 * page's own handlers through [actionSink]. Audio itself keeps playing in the
 * WebView exactly as in the browser; the service only keeps the process alive
 * and the controls on the lock screen.
 */
object MediaHub {

    data class State(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artworkUrl: String = "",
        val playback: String = "none", // none | paused | playing
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val rate: Float = 1f,
        val actions: Set<String> = emptySet(),
    )

    @Volatile
    var state = State()
        private set

    @Volatile
    var artwork: Bitmap? = null
        private set
    private var artworkFor: String = ""

    /** Set by the activity: delivers a media button press to the page. */
    @Volatile
    var actionSink: ((action: String, seekTime: Double?) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isPlaying: Boolean get() = state.playback == "playing"

    fun update(context: Context, p: JSONObject) {
        val actions = mutableSetOf<String>()
        p.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) actions += a.optString(i) }
        val next = State(
            title = p.optString("title").take(300),
            artist = p.optString("artist").take(300),
            album = p.optString("album").take(300),
            artworkUrl = p.optString("artwork").take(2000),
            playback = p.optString("state", "none").let { if (it in setOf("playing", "paused")) it else "none" },
            durationMs = (p.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0).times(1000).toLong(),
            positionMs = (p.optDouble("position", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0).times(1000).toLong(),
            rate = p.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f,
            actions = actions,
        )
        val previous = state
        state = next
        if (next.artworkUrl != artworkFor) loadArtwork(context.applicationContext, next.artworkUrl)
        if (next == previous) return
        // Nothing has ever played: do not start a service just for a page that
        // set up its handlers.
        if (next.playback == "none" && previous.playback == "none") return
        poke(context)
    }

    fun clear(context: Context) {
        if (state.playback == "none") return
        state = State()
        poke(context)
    }

    private fun poke(context: Context) {
        val intent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_SYNC)
        try {
            if (isPlaying) {
                ContextCompat.startForegroundService(context, intent.putExtra(PlaybackService.EXTRA_FOREGROUND, true))
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
            // Background start refused (paused + app in background): the
            // existing notification simply keeps its last state.
        }
    }

    private fun loadArtwork(context: Context, url: String) {
        artworkFor = url
        artwork = null
        if (url.isEmpty()) return
        scope.launch {
            val bmp = try {
                val req = Request.Builder().url(UrlGuard.check(url)).header("User-Agent", Http.userAgent).build()
                Http.fetch.newCall(req).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null || body.contentLength() > 3_000_000) return@use null
                    val bytes = body.bytes()
                    if (bytes.size > 3_000_000) return@use null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (_: Exception) {
                null
            }
            if (artworkFor == url && bmp != null) {
                artwork = bmp
                if (state.playback != "none") poke(context)
            }
        }
    }
}
