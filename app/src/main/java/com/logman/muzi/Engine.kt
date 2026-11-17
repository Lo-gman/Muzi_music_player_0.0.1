package com.logman.muzi

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

/** The one and only music player. Lives as long as the app process; PlaybackService keeps the process alive. */
object Engine {
    interface Listener {
        fun onChanged() {}
        fun onTick() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private lateinit var app: Context
    private var inited = false
    private var mp: MediaPlayer? = null
    private var eq: Equalizer? = null
    private val main = Handler(Looper.getMainLooper())

    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    var playing = false
        private set
    var shuffle = false
        private set
    var repeat = 0 // 0 = off, 1 = repeat all, 2 = repeat one
        private set
    var cover: Bitmap? = null
        private set

    private var prepared = false
    private var autoplay = false
    private var pendingSeek = 0
    private var errors = 0
    private var pausedByFocus = false
    private var focusReq: Any? = null
    private var tickCount = 0

    val current: Track? get() = queue.getOrNull(index)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        shuffle = Prefs.shuffle
        repeat = Prefs.repeat
    }

    fun notifyChanged() { for (l in listeners) l.onChanged() }

    // ------------------------------------------------------------------ player
    private fun player(): MediaPlayer {
        var m = mp
        if (m == null) {
            m = MediaPlayer()
            m.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            m.setOnPreparedListener { onPrepared() }
            m.setOnCompletionListener { onCompleted() }
            m.setOnErrorListener { _, _, _ -> onError(); true }
            mp = m
        }
        return m
    }

    private fun applyAttrs(m: MediaPlayer) {
        m.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
    }

    private fun loadCurrent(auto: Boolean, seek: Int) {
        val t = current
        if (t == null) { stopHard(); return }
        autoplay = auto
        pendingSeek = seek
        prepared = false
        playing = false
        stopTicker()
        cover = null
        refreshCover()
        try {
            val m = player()
            m.reset()
            applyAttrs(m)
            m.setDataSource(app, Uri.parse(t.uri))
            m.prepareAsync()
        } catch (e: Exception) {
            onError()
            return
        }
        notifyChanged()
    }

    private fun onPrepared() {
        prepared = true
        errors = 0
        val m = mp ?: return
        if (pendingSeek > 0) { try { m.seekTo(pendingSeek) } catch (e: Exception) { } }
        pendingSeek = 0
        applyEq()
        if (autoplay) startInternal() else notifyChanged()
    }

    private fun onCompleted() {
        playing = false
        stopTicker()
        if (repeat == 2) {
            try { mp?.seekTo(0) } catch (e: Exception) { }
            startInternal()
        } else {
            next(true)
        }
    }

    private fun onError() {
        val wasAuto = autoplay
        errors++
        playing = false
        prepared = false
        stopTicker()
        val t = current
        if (wasAuto && t != null) Toast.makeText(app, "Can\u2019t play \u201c" + t.title + "\u201d", Toast.LENGTH_SHORT).show()
        if (wasAuto && errors <= 3 && queue.size > 1) next(false) else notifyChanged()
    }

    private fun startInternal() {
        val m = mp ?: return
        if (!prepared) { autoplay = true; return }
        requestFocus()
        try {
            m.setVolume(1f, 1f)
            m.start()
        } catch (e: Exception) { onError(); return }
        playing = true
        pausedByFocus = false
        PlaybackService.start(app)
        startTicker()
        notifyChanged()
    }

    private fun pauseInternal() {
        val m = mp
        if (m != null && playing) { try { m.pause() } catch (e: Exception) { } }
        playing = false
        stopTicker()
        saveLast()
        notifyChanged()
    }

    private fun stopHard() {
        try { mp?.reset() } catch (e: Exception) { }
        prepared = false
        playing = false
        stopTicker()
        cover = null
        notifyChanged()
        PlaybackService.stop(app)
    }

    fun play() {
        if (current == null) return
        if (!prepared) { autoplay = true; return }
        startInternal()
    }

    fun pause() {
        pausedByFocus = false
        pauseInternal()
        abandonFocus()
    }

    fun toggle() { if (playing) pause() else play() }

    fun seekTo(ms: Int) {
        if (prepared) { try { mp?.seekTo(ms) } catch (e: Exception) { } } else pendingSeek = ms
        notifyChanged()
    }

    fun position(): Int {
        if (!prepared) return pendingSeek
        return try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    }

    fun duration(): Int {
        if (prepared) {
            val d = try { mp?.duration ?: 0 } catch (e: Exception) { 0 }
            if (d > 0) return d
        }
        return (current?.durMs ?: 0L).toInt()
    }

    fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && repeat == 2) {
            try { mp?.seekTo(0) } catch (e: Exception) { }
            startInternal()
            return
        }
        var i = index + 1
        if (shuffle && queue.size > 1) {
            do { i = (Math.random() * queue.size).toInt() } while (i == index || i >= queue.size)
        } else if (i >= queue.size) {
            if (auto && repeat == 0) {
                // end of the list: stop and rewind
                try { mp?.seekTo(0) } catch (e: Exception) { }
                pauseInternal()
                abandonFocus()
                return
            }
            i = 0
        }
        index = i
        loadCurrent(true, 0)
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position() > 3000) { seekTo(0); return }
        var i = index - 1
        if (i < 0) i = queue.size - 1
        index = i
        loadCurrent(true, 0)
    }

    fun setQueue(list: List<Track>, startIndex: Int, playNow: Boolean) {
        queue = ArrayList(list)
        index = startIndex
        loadCurrent(playNow, 0)
    }

    fun setShuffle(v: Boolean) { shuffle = v; Prefs.shuffle = v; notifyChanged() }
    fun cycleRepeat() { repeat = (repeat + 1) % 3; Prefs.repeat = repeat; notifyChanged() }

    /** Loads the song that was open last time (paused). */
    fun restore() {
        if (current != null) return
        val list = Lib.view(Prefs.sort, false, "")
        if (list.isEmpty()) return
        val key = Prefs.lastKey
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0) {
            queue = list; index = i
            loadCurrent(false, Prefs.lastPos.toInt())
        } else {
            queue = list; index = 0
            loadCurrent(false, 0)
        }
    }

    fun onTrackRemoved(t: Track) {
        val cur = current
        val wasCurrent = cur != null && cur.key == t.key
        queue = queue.filter { it.key != t.key }
        if (wasCurrent) {
            stopHard()
            index = if (queue.isEmpty()) -1 else minOf(index, queue.size - 1)
            if (index >= 0) loadCurrent(false, 0)
        } else {
            index = if (cur == null) -1 else queue.indexOfFirst { it.key == cur.key }
            notifyChanged()
        }
    }

    fun refreshCover() {
        val t = current ?: return
        Covers.load(app, t, 400) { b ->
            if (current?.key == t.key) { cover = b; notifyChanged() }
        }
    }

    fun saveLast() {
        val t = current ?: return
        Prefs.lastKey = t.key
        Prefs.lastPos = position().toLong()
    }

    // ------------------------------------------------------------------ ticker
    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            tickCount++
            if (tickCount % 10 == 0) saveLast()
            for (l in listeners) l.onTick()
            main.postDelayed(this, 500)
        }
    }

    private fun startTicker() { main.removeCallbacks(ticker); main.postDelayed(ticker, 500) }
    private fun stopTicker() { main.removeCallbacks(ticker) }

    // ------------------------------------------------------------------ audio focus (calls, other apps)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) { pausedByFocus = true; pauseInternal() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try { mp?.setVolume(0.25f, 0.25f) } catch (e: Exception) { }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { mp?.setVolume(1f, 1f) } catch (e: Exception) { }
                if (pausedByFocus) { pausedByFocus = false; startInternal() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build()
                focusReq = req
                am.requestAudioFocus(req)
            } else {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                (focusReq as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            } else {
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ equalizer & audio enhancer
    private val BANDS = intArrayOf(60, 230, 910, 3600, 14000)
    private var le: android.media.audiofx.LoudnessEnhancer? = null

    fun applyEq() {
        try {
            val m = mp ?: return
            var e = eq
            if (e == null) { e = Equalizer(0, m.audioSessionId); eq = e }
            e.enabled = Prefs.eqOn
            if (Prefs.eqOn) {
                val gains = Prefs.eqGains
                val range = e.bandLevelRange
                val n = e.numberOfBands.toInt()
                for (b in 0 until n) {
                    val centerHz = e.getCenterFreq(b.toShort()) / 1000
                    var best = 0
                    var bestD = Double.MAX_VALUE
                    for (i in BANDS.indices) {
                        val d = Math.abs(Math.log(centerHz.toDouble().coerceAtLeast(1.0) / BANDS[i]))
                        if (d < bestD) { bestD = d; best = i }
                    }
                    val level = (gains[best] * 100).coerceIn(range[0].toInt(), range[1].toInt())
                    e.setBandLevel(b.toShort(), level.toShort())
                }
            }
            applyLoudness()
        } catch (e: Exception) { }
    }

    fun applyLoudness() {
        try {
            val m = mp ?: return
            if (Build.VERSION.SDK_INT >= 19) {
                var enhancer = le
                if (enhancer == null) {
                    enhancer = android.media.audiofx.LoudnessEnhancer(m.audioSessionId)
                    le = enhancer
                }
                enhancer.setTargetGain(Prefs.loudnessGain)
                enhancer.enabled = Prefs.loudnessOn
            }
        } catch (e: Exception) { }
    }
}
