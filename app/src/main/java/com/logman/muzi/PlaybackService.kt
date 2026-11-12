package com.logman.muzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews
import java.util.Locale

/** Keeps the app alive while music plays and owns the notification, lock-screen controls and widgets. */
class PlaybackService : Service(), Engine.Listener {
    companion object {
        const val CHANNEL = "muzi_playback"
        const val NOTIF_ID = 11
        const val CMD_TOGGLE = "com.logman.muzi.CMD_TOGGLE"
        const val CMD_NEXT = "com.logman.muzi.CMD_NEXT"
        const val CMD_PREV = "com.logman.muzi.CMD_PREV"
        const val CMD_FAV = "com.logman.muzi.CMD_FAV"
        @Volatile var running = false

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, PlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (e: Exception) { }
        }
    }

    private var session: MediaSession? = null
    private var foreground = false
    private var tickN = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CMD_TOGGLE -> Engine.toggle()
                CMD_NEXT -> Engine.next(false)
                CMD_PREV -> Engine.prev()
                CMD_FAV -> Engine.current?.let { Lib.toggleFav(it.key) }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> if (Engine.playing) Engine.pause()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val f = IntentFilter()
        f.addAction(CMD_TOGGLE); f.addAction(CMD_NEXT); f.addAction(CMD_PREV); f.addAction(CMD_FAV)
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        @Suppress("DEPRECATION")
        val s = MediaSession(this, "MuziMusic")
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { Engine.play() }
            override fun onPause() { Engine.pause() }
            override fun onSkipToNext() { Engine.next(false) }
            override fun onSkipToPrevious() { Engine.prev() }
            override fun onSeekTo(pos: Long) { Engine.seekTo(pos.toInt()) }
            override fun onStop() { Engine.pause() }
        })
        s.isActive = true
        session = s
        Engine.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // must call startForeground quickly after startForegroundService()
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
            foreground = true
        } catch (e: Exception) { }
        refresh()
        return START_NOT_STICKY
    }

    override fun onChanged() { refresh() }

    override fun onTick() {
        tickN++
        if (Build.VERSION.SDK_INT < 31 && tickN % 2 == 0) refresh()
    }

    // ---------------------------------------------------------------- session + widgets
    private fun updateSession() {
        val s = session ?: return
        val t = Engine.current
        try {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "Muzi Music")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Engine.duration().toLong())
            Engine.cover?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(meta.build())
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
            s.setPlaybackState(
                PlaybackState.Builder().setActions(actions)
                    .setState(
                        if (Engine.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        Engine.position().toLong(), if (Engine.playing) 1f else 0f, SystemClock.elapsedRealtime()
                    ).build()
            )
        } catch (e: Exception) { }
    }

    private fun updateWidgets(live: Boolean) {
        val t = Engine.current
        val fav = t != null && Lib.isFav(t.key)
        PlayerWidgetProvider.update(this, t?.title ?: "Muzi Music", t?.artist ?: "Tap to open", Engine.playing, fav, Engine.cover, live)
        PlayerWidgetProviderLight.push(this)
    }

    // ---------------------------------------------------------------- notification
    private fun pi(action: String, code: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(this, code, i, flags)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun bindCommon(rv: RemoteViews, title: String, artist: String, playing: Boolean) {
        rv.setTextViewText(R.id.n_title, title.uppercase())
        rv.setTextViewText(R.id.n_artist, artist.uppercase())
        val c = Engine.cover
        if (c != null) rv.setImageViewBitmap(R.id.n_cover, c) else rv.setImageViewResource(R.id.n_cover, R.drawable.notif_cover_placeholder)
        rv.setImageViewResource(R.id.n_prev, R.drawable.ic_n_prev)
        rv.setImageViewResource(R.id.n_next, R.drawable.ic_n_next)
        rv.setImageViewResource(R.id.n_play, if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
        rv.setOnClickPendingIntent(R.id.n_prev, pi(CMD_PREV, 1))
        rv.setOnClickPendingIntent(R.id.n_play, pi(CMD_TOGGLE, 2))
        rv.setOnClickPendingIntent(R.id.n_next, pi(CMD_NEXT, 3))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val t = Engine.current
        val title = t?.title ?: "Muzi Music"
        val artist = t?.artist ?: ""
        val playing = Engine.playing
        val fav = t != null && Lib.isFav(t.key)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(artist.ifBlank { "Muzi Music" })
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        Engine.cover?.let { b.setLargeIcon(it) }

        if (Build.VERSION.SDK_INT >= 31) {
            b.addAction(android.R.drawable.ic_media_previous, "Previous", pi(CMD_PREV, 1))
            b.addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pi(CMD_TOGGLE, 2)
            )
            b.addAction(android.R.drawable.ic_media_next, "Next", pi(CMD_NEXT, 3))
            b.setColor(0xFF1B1F26.toInt())
            b.setColorized(true)
            val st = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            session?.let { st.setMediaSession(it.sessionToken) }
            b.setStyle(st)
            return b.build()
        }

        val pos = Engine.position().toLong()
        val dur = Engine.duration().toLong()
        val big = RemoteViews(packageName, R.layout.notif_big)
        bindCommon(big, title, artist, playing)
        big.setImageViewResource(R.id.n_fav, if (fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
        big.setOnClickPendingIntent(R.id.n_fav, pi(CMD_FAV, 4))
        big.setProgressBar(R.id.n_progress, 1000, if (dur > 0) (pos * 1000 / dur).toInt() else 0, false)
        big.setTextViewText(R.id.n_cur, fmt(pos))
        big.setTextViewText(R.id.n_tot, fmt(dur))
        val small = RemoteViews(packageName, R.layout.notif_small)
        bindCommon(small, title, artist, playing)
        if (Build.VERSION.SDK_INT >= 24) {
            b.setCustomContentView(small)
            b.setCustomBigContentView(big)
            return b.build()
        }
        val n = b.build()
        n.contentView = small
        n.bigContentView = big
        return n
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        updateSession()
        updateWidgets(true)
        val n = buildNotification()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Engine.playing) {
            if (!foreground) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    else startForeground(NOTIF_ID, n)
                    foreground = true
                } catch (e: Exception) { }
            } else {
                try { nm.notify(NOTIF_ID, n) } catch (e: Exception) { }
            }
        } else {
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH) else stopForeground(false)
                foreground = false
            }
            try { nm.notify(NOTIF_ID, n) } catch (e: Exception) { }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.pause()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        running = false
        Engine.removeListener(this)
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
        try { session?.isActive = false; session?.release() } catch (e: Exception) { }
        updateWidgets(false)
        stopForeground(true)
        super.onDestroy()
    }
}
