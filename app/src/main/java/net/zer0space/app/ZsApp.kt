package net.zer0space.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.WebSettings

class ZsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Http.userAgent = chromeLikeUserAgent()
        NativeFetcher.init(this)
        createPlaybackChannel()
    }

    /**
     * One User-Agent for everything this app sends: the WebView, the native
     * fetcher, the hidden resolver and the media header rules.
     *
     * It has to be ONE string because several hoster CDNs bind their stream
     * token to the User-Agent that resolved it (VOE answers 403 to a different
     * one). It also has to look like plain mobile Chrome rather than a WebView:
     * the default WebView UA carries "; wv" and "Version/4.0", which some
     * hosters refuse outright. Chrome itself sends the reduced form below
     * ("Android 10; K"), so this also leaks less about the phone.
     */
    private fun chromeLikeUserAgent(): String {
        val default = try {
            WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            ""
        }
        val major = Regex("""Chrome/(\d+)""").find(default)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    private fun createPlaybackChannel() {
        val channel = NotificationChannel(
            PlaybackService.CHANNEL_ID,
            getString(R.string.channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_playback_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
