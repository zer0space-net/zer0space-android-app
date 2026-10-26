package net.zer0space.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps music going with the screen off and puts its controls on the lock
 * screen, the notification shade, Bluetooth and headset buttons.
 *
 * It plays nothing itself. The `<audio>` element in the WebView does, exactly
 * as in the browser; this service is the system-facing half: a media session
 * mirroring [MediaHub.state] and, while something plays, a foreground service
 * so Android does not freeze the process between two tracks.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private var foreground = false
    private val handler = Handler(Looper.getMainLooper())
    private val demote = Runnable {
        if (MediaHub.state.playback != "playing" && foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
            notify(buildNotification(MediaHub.state, false))
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "zer0space").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onStop() = send("pause")
                override fun onSkipToNext() = send("nexttrack")
                override fun onSkipToPrevious() = send("previoustrack")
                override fun onFastForward() = send("seekforward")
                override fun onRewind() = send("seekbackward")
                override fun onSeekTo(pos: Long) = send("seekto", pos / 1000.0)
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> send("play")
            ACTION_PAUSE -> send("pause")
            ACTION_NEXT -> send("nexttrack")
            ACTION_PREV -> send("previoustrack")
        }
        // Started with startForegroundService(): Android requires a
        // startForeground() within seconds even if the state flipped to paused
        // in the meantime, or it kills the app. Promote first, sync() may
        // detach again right after.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true && !foreground) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(MediaHub.state, true), mediaType())
                foreground = true
            } catch (_: Exception) {
            }
        }
        sync()
        return START_NOT_STICKY
    }

    private fun send(action: String, seekTime: Double? = null) {
        MediaHub.actionSink?.invoke(action, seekTime)
    }

    private fun sync() {
        val s = MediaHub.state
        if (s.playback == "none") {
            stopEverything()
            return
        }
        val playing = s.playback == "playing"

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
            .apply {
                if (s.durationMs > 0) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durationMs)
                MediaHub.artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }
            .build()
        session.setMetadata(meta)

        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if ("nexttrack" in s.actions) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if ("previoustrack" in s.actions) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if ("seekto" in s.actions) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    s.positionMs,
                    if (playing) s.rate else 0f,
                )
                .build(),
        )

        val notification = buildNotification(s, playing)
        if (playing) {
            handler.removeCallbacks(demote)
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, mediaType())
                foreground = true
            } catch (_: Exception) {
                // Android refused a foreground start from the background; the
                // notification below still shows the controls.
                notify(notification)
            }
        } else {
            notify(notification)
            // Paused: keep the controls, and after a grace period let the system
            // reclaim the service. Not at once: the player reports "paused" for a
            // moment between two tracks, and once demoted Android does not let a
            // backgrounded app become a foreground service again, so the next
            // track would play without the process being protected.
            if (foreground) {
                handler.removeCallbacks(demote)
                handler.postDelayed(demote, DEMOTE_AFTER_MS)
            }
        }
    }

    private fun mediaType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun notify(n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Notifications not allowed on Android 13+. Media controls on the
            // lock screen come from the session, so playback is unaffected.
        }
    }

    private fun buildNotification(s: MediaHub.State, playing: Boolean): Notification {
        val compact = mutableListOf<Int>()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zs)
            .setContentTitle(s.title.ifBlank { getString(R.string.app_name) })
            .setContentText(s.artist)
            .setSubText(s.album)
            .setLargeIcon(MediaHub.artwork)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_PAUSE, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
        var index = 0
        if ("previoustrack" in s.actions) {
            builder.addAction(R.drawable.ic_prev, getString(R.string.action_previous), serviceIntent(ACTION_PREV, 1))
            compact += index++
        }
        if (playing) {
            builder.addAction(R.drawable.ic_pause, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE, 2))
        } else {
            builder.addAction(R.drawable.ic_play, getString(R.string.action_play), serviceIntent(ACTION_PLAY, 3))
        }
        compact += index++
        if ("nexttrack" in s.actions) {
            builder.addAction(R.drawable.ic_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT, 4))
            compact += index
        }
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*compact.toIntArray()),
        )
        return builder.build()
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun stopEverything() {
        handler.removeCallbacks(demote)
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away ends its WebView, and with it the music.
        stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 42
        private const val DEMOTE_AFTER_MS = 5 * 60 * 1000L
        const val ACTION_SYNC = "net.zer0space.app.SYNC"
        const val EXTRA_FOREGROUND = "fg"
        const val ACTION_PLAY = "net.zer0space.app.PLAY"
        const val ACTION_PAUSE = "net.zer0space.app.PAUSE"
        const val ACTION_NEXT = "net.zer0space.app.NEXT"
        const val ACTION_PREV = "net.zer0space.app.PREV"
    }
}
