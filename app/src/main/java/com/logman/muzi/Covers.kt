package com.logman.muzi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Loads cover art (custom image first, then the picture embedded in the song file). */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val none = HashSet<String>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun ck(t: Track, px: Int) = t.key + "|" + px

    fun peek(t: Track, px: Int): Bitmap? = cache.get(ck(t, px))

    fun invalidate(key: String) {
        for (k in cache.snapshot().keys) if (k.startsWith("$key|")) cache.remove(k)
        synchronized(none) { none.remove(key) }
    }

    fun hasNone(t: Track) = synchronized(none) { none.contains(t.key) }

    fun load(ctx: Context, t: Track, px: Int, cb: (Bitmap?) -> Unit) {
        val hit = cache.get(ck(t, px))
        if (hit != null) { cb(hit); return }
        if (hasNone(t)) { cb(null); return }
        val appCtx = ctx.applicationContext
        exec.execute {
            val bmp = decode(appCtx, t, px)
            if (bmp != null) cache.put(ck(t, px), bmp) else synchronized(none) { none.add(t.key) }
            main.post { cb(bmp) }
        }
    }

    private fun decode(ctx: Context, t: Track, px: Int): Bitmap? {
        try {
            var bytes: ByteArray? = null
            if (t.customCover) {
                val f = Lib.coverFile(t.key)
                if (f.isFile) bytes = f.readBytes()
            }
            if (bytes == null) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(t.uri))
                    bytes = r.embeddedPicture
                } finally { try { r.release() } catch (e: Exception) { } }
            }
            val data = bytes ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
            val s = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
            return if (s > px) Bitmap.createScaledBitmap(sq, px, px, true) else sq
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }
}
