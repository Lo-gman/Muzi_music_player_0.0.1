package com.logman.muzi

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Small typed wrapper around SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("muzi_prefs", Context.MODE_PRIVATE) }

    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) { sp.edit().putString("theme", v).apply() }
    var scanOn: Boolean
        get() = sp.getBoolean("scanOn", false)
        set(v) { sp.edit().putBoolean("scanOn", v).apply() }
    var sort: String
        get() = sp.getString("sort", "name") ?: "name"
        set(v) { sp.edit().putString("sort", v).apply() }
    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) { sp.edit().putBoolean("shuffle", v).apply() }
    var repeat: Int
        get() = sp.getInt("repeat", 0)
        set(v) { sp.edit().putInt("repeat", v).apply() }
    var eqOn: Boolean
        get() = sp.getBoolean("eqOn", true)
        set(v) { sp.edit().putBoolean("eqOn", v).apply() }
    var eqPreset: String
        get() = sp.getString("eqPreset", "flat") ?: "flat"
        set(v) { sp.edit().putString("eqPreset", v).apply() }
    var eqGains: IntArray
        get() {
            val parts = (sp.getString("eqGains", "0,0,0,0,0") ?: "0,0,0,0,0").split(",")
            return IntArray(5) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
        }
        set(v) { sp.edit().putString("eqGains", v.joinToString(",")).apply() }
    var loudnessOn: Boolean
        get() = sp.getBoolean("loudnessOn", false)
        set(v) { sp.edit().putBoolean("loudnessOn", v).apply() }
    var loudnessGain: Int
        get() = sp.getInt("loudnessGain", 300)
        set(v) { sp.edit().putInt("loudnessGain", v).apply() }
    var lastKey: String
        get() = sp.getString("lastKey", "") ?: ""
        set(v) { sp.edit().putString("lastKey", v).apply() }
    var lastPos: Long
        get() = sp.getLong("lastPos", 0L)
        set(v) { sp.edit().putLong("lastPos", v).apply() }
    var firstScanDone: Boolean
        get() = sp.getBoolean("firstScanDone", false)
        set(v) { sp.edit().putBoolean("firstScanDone", v).apply() }
    var searchQuery: String
        get() = sp.getString("searchQuery", "") ?: ""
        set(v) { sp.edit().putString("searchQuery", v).apply() }
}

class Track(
    val key: String,
    val uri: String,
    var title: String,
    var artist: String,
    var album: String,
    val durMs: Long,
    val added: Long,
    var customCover: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("uri", uri).put("title", title).put("artist", artist)
        .put("album", album).put("dur", durMs).put("added", added).put("cc", customCover)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            o.getString("key"), o.getString("uri"), o.optString("title"), o.optString("artist"),
            o.optString("album"), o.optLong("dur"), o.optLong("added"), o.optBoolean("cc")
        )
    }
}

object Lib {
    val tracks = ArrayList<Track>()
    val favs = HashSet<String>()
    val hidden = HashSet<String>()
    var lastScanMsg = ""

    private lateinit var app: Context
    private var inited = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        load()
    }

    private fun file() = File(app.filesDir, "library.json")

    private fun load() {
        try {
            val f = file()
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                try { tracks.add(Track.fromJson(t)) } catch (e: Exception) { }
            }
            val fa = o.optJSONArray("favs") ?: JSONArray()
            for (i in 0 until fa.length()) favs.add(fa.optString(i))
            val ha = o.optJSONArray("hidden") ?: JSONArray()
            for (i in 0 until ha.length()) hidden.add(ha.optString(i))
        } catch (e: Exception) { }
    }

    fun save() {
        val o = JSONObject()
        val arr = JSONArray()
        for (t in tracks) arr.put(t.toJson())
        o.put("tracks", arr)
        o.put("favs", JSONArray(favs.toList()))
        o.put("hidden", JSONArray(hidden.toList()))
        val text = o.toString()
        io.execute { try { file().writeText(text) } catch (e: Exception) { } }
    }

    fun find(key: String): Track? = tracks.firstOrNull { it.key == key }
    fun isFav(key: String) = favs.contains(key)

    fun toggleFav(key: String) {
        if (!favs.add(key)) favs.remove(key)
        save()
        Engine.notifyChanged()
    }

    fun coverFile(key: String) = File(File(app.filesDir, "covers").also { it.mkdirs() }, "$key.jpg")

    fun remove(t: Track) {
        tracks.remove(t)
        favs.remove(t.key)
        if (t.key.startsWith("ms-")) hidden.add(t.key)
        try { coverFile(t.key).delete() } catch (e: Exception) { }
        if (t.key.startsWith("doc-")) {
            try { app.contentResolver.releasePersistableUriPermission(Uri.parse(t.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        }
        Covers.invalidate(t.key)
        save()
        Engine.onTrackRemoved(t)
    }

    /** Filtered + sorted view of the library. */
    fun view(sort: String, favOnly: Boolean, query: String): List<Track> {
        val q = query.trim().lowercase()
        var list: List<Track> = tracks
        if (favOnly) list = list.filter { favs.contains(it.key) }
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
        return when (sort) {
            "recent" -> list.sortedByDescending { it.added }
            "artist" -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            else -> list.sortedBy { it.title.lowercase() }
        }
    }

    // ---------------------------------------------------------------- scanning
    private fun isRecording(path: String?, name: String): Boolean {
        val p = (path ?: "").lowercase().replace('\\', '/')
        val n = name.lowercase()
        val folders = listOf(
            "/recordings/", "/recording/", "/recorder/", "/record/", "/voice recorder", "/voicerecorder", "/voice_recorder",
            "/voice recordings", "/sound recorder", "/sound_recorder", "/soundrecorder", "/call/", "/calls/",
            "/callrecord", "/call record", "/call_record", "/call recording", "/phonerecord", "/phone record",
            "/whatsapp", "/telegram audio", "/voice notes", "/voicenotes", "/miui/sound_recorder", "/dictaphone", "/audiorecorder"
        )
        if (folders.any { p.contains(it) }) return true
        val names = listOf("call recording", "call_recording", "callrecord", "call-recording", "voice recording", "voice_recording")
        if (names.any { n.contains(it) }) return true
        return n.startsWith("rec_") || n.startsWith("rec-") || n.startsWith("recording") || n.startsWith("voice_") || n.startsWith("ptt-")
    }

    private fun queryMediaStore(ctx: Context): ArrayList<Track> {
        val out = ArrayList<Track>()
        val includeData = Build.VERSION.SDK_INT < 29
        val cols = ArrayList<String>()
        cols.add(MediaStore.Audio.Media._ID)
        cols.add(MediaStore.Audio.Media.TITLE)
        cols.add(MediaStore.Audio.Media.ARTIST)
        cols.add(MediaStore.Audio.Media.ALBUM)
        cols.add(MediaStore.Audio.Media.DURATION)
        cols.add(MediaStore.Audio.Media.DISPLAY_NAME)
        cols.add(MediaStore.Audio.Media.DATE_ADDED)
        if (includeData) cols.add(MediaStore.Audio.Media.DATA)
        var sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.DURATION}>=30000"
        if (Build.VERSION.SDK_INT >= 31) sel += " AND ${MediaStore.Audio.Media.IS_RECORDING}=0"
        val c = ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(), sel, null, null) ?: return out
        c.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val display = it.getString(5) ?: "audio-$id"
                val path = if (includeData) it.getString(7) else null
                if (isRecording(path, display)) continue
                val artist = it.getString(2)?.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: "Unknown artist"
                val album = it.getString(3)?.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: "On this phone"
                val title = it.getString(1)?.takeIf { s -> s.isNotBlank() } ?: display.substringBeforeLast('.')
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                out.add(Track("ms-$id", uri, title, artist, album, it.getLong(4), it.getLong(6) * 1000L))
            }
        }
        return out
    }

    /** Scans the phone's media library. [done] runs on the main thread: (added, removed, ok). */
    fun scan(ctx: Context, done: (Int, Int, Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        Thread {
            try {
                val found = queryMediaStore(appCtx)
                main.post {
                    val valid = HashSet<String>()
                    for (t in found) valid.add(t.key)
                    var removed = 0
                    val it = tracks.iterator()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t.key.startsWith("ms-") && !valid.contains(t.key)) { it.remove(); removed++ }
                    }
                    val known = HashSet<String>()
                    for (t in tracks) known.add(t.key)
                    var added = 0
                    for (t in found) {
                        if (!hidden.contains(t.key) && known.add(t.key)) { tracks.add(t); added++ }
                    }
                    if (added > 0 || removed > 0) save()
                    done(added, removed, true)
                }
            } catch (e: Exception) {
                main.post { done(0, 0, false) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- import
    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: ""
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment ?: "audio"
    }

    /** Adds songs picked with the system file picker (kept in place, not copied). */
    fun importUris(ctx: Context, uris: List<Uri>, done: (Int) -> Unit) {
        val appCtx = ctx.applicationContext
        Thread {
            val made = ArrayList<Track>()
            for (uri in uris) {
                try {
                    try { appCtx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                    val key = "doc-" + Integer.toHexString(uri.toString().hashCode())
                    val name = displayName(appCtx, uri)
                    var title = name.substringBeforeLast('.')
                    var artist = "Unknown artist"
                    var album = "Imported"
                    var dur = 0L
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(appCtx, uri)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                        dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                    } finally { try { r.release() } catch (e: Exception) { } }
                    made.add(Track(key, uri.toString(), title, artist, album, dur, System.currentTimeMillis()))
                } catch (e: Exception) { }
            }
            main.post {
                val known = HashSet<String>()
                for (t in tracks) known.add(t.key)
                var added = 0
                for (t in made) if (known.add(t.key)) { hidden.remove(t.key); tracks.add(t); added++ }
                if (added > 0) save()
                done(added)
            }
        }.start()
    }
}
