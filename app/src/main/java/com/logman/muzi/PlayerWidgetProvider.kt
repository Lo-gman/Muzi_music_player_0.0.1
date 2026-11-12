package com.logman.muzi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews

/** Home-screen player widget (4x2). Controls the app while it is running, opens it otherwise. */
class PlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val PREFS = "muzi_widget"
        @Volatile private var title: String? = null
        @Volatile private var artist: String? = null
        @Volatile private var playing = false
        @Volatile private var fav = false
        @Volatile private var live = false
        @Volatile private var cover: Bitmap? = null

        /** Called by PlaybackService whenever the song or play state changes. */
        fun update(ctx: Context, t: String, a: String, isPlaying: Boolean, isFav: Boolean, c: Bitmap?, isLive: Boolean) {
            title = t; artist = a; playing = isPlaying; fav = isFav; live = isLive
            cover = c?.let { if (it.width > 256) Bitmap.createScaledBitmap(it, 256, 256, true) else it }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("title", t).putString("artist", a).apply()
            push(ctx)
        }

        fun push(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidgetProvider::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
            } catch (_: Exception) { }
        }

        private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        private fun action(ctx: Context, broadcastAction: String, activityCmd: String, code: Int): PendingIntent {
            return if (live) {
                PendingIntent.getBroadcast(ctx, 100 + code, Intent(broadcastAction).setPackage(ctx.packageName), flags())
            } else {
                val i = Intent(ctx, MainActivity::class.java)
                    .setAction("com.logman.muzi.WIDGET_$activityCmd")
                    .putExtra("cmd", activityCmd)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(ctx, 100 + code, i, flags())
            }
        }

        fun buildFor(ctx: Context, layout: Int): RemoteViews {
            if (title == null) {
                val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                title = p.getString("title", "Muzi Music")
                artist = p.getString("artist", "Tap to open")
            }
            val rv = RemoteViews(ctx.packageName, layout)
            rv.setTextViewText(R.id.w_title, (title ?: "Muzi Music").uppercase())
            rv.setTextViewText(R.id.w_artist, (artist ?: "").uppercase())
            val c = cover
            if (c != null) rv.setImageViewBitmap(R.id.w_cover, c) else rv.setImageViewResource(R.id.w_cover, R.drawable.notif_cover_placeholder)
            rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
            rv.setImageViewResource(R.id.w_fav, if (fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
            rv.setOnClickPendingIntent(R.id.w_prev, action(ctx, PlaybackService.CMD_PREV, "prev", 1))
            rv.setOnClickPendingIntent(R.id.w_play, action(ctx, PlaybackService.CMD_TOGGLE, "play", 2))
            rv.setOnClickPendingIntent(R.id.w_next, action(ctx, PlaybackService.CMD_NEXT, "next", 3))
            rv.setOnClickPendingIntent(R.id.w_fav, action(ctx, PlaybackService.CMD_FAV, "fav", 4))
            val open = PendingIntent.getActivity(
                ctx, 99, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags()
            )
            rv.setOnClickPendingIntent(R.id.w_cover, open)
            rv.setOnClickPendingIntent(R.id.w_title, open)
            rv.setOnClickPendingIntent(R.id.w_artist, open)
            return rv
        }

        fun build(ctx: Context): RemoteViews {
            if (title == null) {
                val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                title = p.getString("title", "Muzi Music")
                artist = p.getString("artist", "Tap to open")
            }
            val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
            rv.setTextViewText(R.id.w_title, (title ?: "Muzi Music").uppercase())
            rv.setTextViewText(R.id.w_artist, (artist ?: "").uppercase())
            val c = cover
            if (c != null) rv.setImageViewBitmap(R.id.w_cover, c) else rv.setImageViewResource(R.id.w_cover, R.drawable.notif_cover_placeholder)
            rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
            rv.setImageViewResource(R.id.w_fav, if (fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
            rv.setOnClickPendingIntent(R.id.w_prev, action(ctx, PlaybackService.CMD_PREV, "prev", 1))
            rv.setOnClickPendingIntent(R.id.w_play, action(ctx, PlaybackService.CMD_TOGGLE, "play", 2))
            rv.setOnClickPendingIntent(R.id.w_next, action(ctx, PlaybackService.CMD_NEXT, "next", 3))
            rv.setOnClickPendingIntent(R.id.w_fav, action(ctx, PlaybackService.CMD_FAV, "fav", 4))
            val open = PendingIntent.getActivity(
                ctx, 99, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags()
            )
            rv.setOnClickPendingIntent(R.id.w_cover, open)
            rv.setOnClickPendingIntent(R.id.w_title, open)
            rv.setOnClickPendingIntent(R.id.w_artist, open)
            return rv
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, build(context))
    }
}
