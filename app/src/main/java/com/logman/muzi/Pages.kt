package com.logman.muzi

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.view.inputmethod.EditorInfo

abstract class Page(val act: MainActivity) {
    lateinit var view: View
    val th: AppTheme get() = act.appTheme
    open fun refresh() {}
    open fun onTick() {}
    open fun onShow() {}

    protected fun text(size: Float, color: Int, light: Boolean = true): TextView {
        val t = TextView(act)
        t.textSize = size
        t.setTextColor(color)
        t.typeface = if (light) LIGHT_FONT else REGULAR_FONT
        t.includeFontPadding = false
        return t
    }

    protected fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
}

// =====================================================================================
// NOW PLAYING
// =====================================================================================
class PlayerPage(act: MainActivity) : Page(act) {
    private val title = text(22f, th.fg)
    private val artist = text(14f, th.mute, false)
    private val heart = act.iconView(R.drawable.ic_n_heart, th.fg, 30, 3)
    private val cover = CoverView(act)
    private val shuffleBtn = act.iconView(R.drawable.ic_shuffle, th.mute, 36, 7)
    private val repeatBtn = act.iconView(R.drawable.ic_repeat, th.mute, 36, 7)
    private val listBtn = act.iconView(R.drawable.ic_list, th.fg, 36, 7)
    private val seek = SeekBar(act)
    private val tCur = text(11f, th.mute, false)
    private val tDur = text(11f, th.mute, false)
    private val prevBtn = act.iconView(R.drawable.ic_n_prev, th.fg, 46, 13)
    private val playBtn = act.iconView(R.drawable.ic_n_play, th.fg, 60, 17)
    private val nextBtn = act.iconView(R.drawable.ic_n_next, th.fg, 46, 13)
    private val moreBtn = act.iconView(R.drawable.ic_more, th.mute, 44, 10)
    private var dragging = false
    private var shownKey = ""

    init {
        cover.big = true
        seek.max = 1000
        seek.progressTintList = android.content.res.ColorStateList.valueOf(th.fg)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(th.fg)
        seek.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(th.line)
        seek.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) act.noSwipe = true
            false
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = fmtTime(Engine.duration().toLong() * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                val d = Engine.duration()
                if (d > 0) Engine.seekTo((d.toLong() * (s?.progress ?: 0) / 1000).toInt())
            }
        })
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END

        heart.setOnClickListener { Engine.current?.let { Lib.toggleFav(it.key) } }
        shuffleBtn.setOnClickListener { Engine.setShuffle(!Engine.shuffle) }
        repeatBtn.setOnClickListener { Engine.cycleRepeat() }
        listBtn.setOnClickListener { if (act.isWideLayout()) act.go(2) else act.go(1) }
        moreBtn.setOnClickListener { act.go(2) }
        prevBtn.setOnClickListener { Engine.prev() }
        nextBtn.setOnClickListener { Engine.next(false) }
        playBtn.setOnClickListener {
            if (Engine.current == null) {
                act.toast("No songs yet. Open Settings and scan for songs.")
            } else Engine.toggle()
        }
        for (b in listOf(prevBtn, nextBtn, playBtn)) b.background = act.ringBackground(th.fg)
        view = build()
        refresh()
    }

    private fun build(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val bottomPad = if (act.isWideLayout()) act.dp(16) else act.dp(70)
        col.setPadding(act.dp(22), act.dp(6), act.dp(22), bottomPad)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.TOP
        val tcol = LinearLayout(act)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.addView(title, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tcol.addView(artist, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(3) })
        head.addView(tcol, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(heart, lp(act.dp(34), act.dp(34)))
        col.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(10) })

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val sq = SquareLayout(act)
        sq.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(sq, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        side.gravity = Gravity.CENTER_HORIZONTAL
        side.addView(shuffleBtn, lp(act.dp(36), act.dp(36)))
        side.addView(repeatBtn, lp(act.dp(36), act.dp(36)).also { it.topMargin = act.dp(8) })
        side.addView(FrameLayout(act), lp(act.dp(1), 0, 1f))
        side.addView(listBtn, lp(act.dp(36), act.dp(36)))
        row.addView(side, lp(act.dp(40), ViewGroup.LayoutParams.MATCH_PARENT).also { it.leftMargin = act.dp(6) })
        col.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(14) })

        col.addView(seek, lp(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(28)).also { it.topMargin = act.dp(14) })
        val times = LinearLayout(act)
        times.orientation = LinearLayout.HORIZONTAL
        times.addView(tCur, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(tDur, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(times, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = act.dp(4); it.rightMargin = act.dp(4) })

        val ctl = LinearLayout(act)
        ctl.orientation = LinearLayout.HORIZONTAL
        ctl.gravity = Gravity.CENTER
        ctl.addView(prevBtn, lp(act.dp(46), act.dp(46)))
        ctl.addView(playBtn, lp(act.dp(60), act.dp(60)).also { it.leftMargin = act.dp(30); it.rightMargin = act.dp(30) })
        ctl.addView(nextBtn, lp(act.dp(46), act.dp(46)))
        col.addView(ctl, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(14) })

        val scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val frame = FrameLayout(act)
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lpMore = FrameLayout.LayoutParams(act.dp(44), act.dp(44), Gravity.BOTTOM or Gravity.END)
        lpMore.rightMargin = act.dp(14)
        lpMore.bottomMargin = act.dp(6)
        frame.addView(moreBtn, lpMore)
        return frame
    }

    override fun refresh() {
        val t = Engine.current
        if (t == null) {
            title.text = "No songs yet"
            artist.text = "Open settings to scan your phone"
            cover.label = ""
            cover.bitmap = null
            shownKey = ""
            tCur.text = "0:00"
            tDur.text = "0:00"
            seek.progress = 0
        } else {
            title.text = t.title
            artist.text = "by " + t.artist
            cover.label = t.title
            cover.seed = t.title.hashCode()
            if (shownKey != t.key + "|" + t.customCover) {
                shownKey = t.key + "|" + t.customCover
                cover.bitmap = Covers.peek(t, 700)
                val key = shownKey
                Covers.load(act, t, 700) { b -> if (shownKey == key) cover.bitmap = b }
            }
        }
        val fav = t != null && Lib.isFav(t.key)
        heart.setImageResource(if (fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
        heart.setColorFilter(if (fav) th.acc else th.fg, PorterDuff.Mode.SRC_IN)
        playBtn.setImageResource(if (Engine.playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
        shuffleBtn.setColorFilter(if (Engine.shuffle) th.acc else th.mute, PorterDuff.Mode.SRC_IN)
        repeatBtn.setImageResource(if (Engine.repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatBtn.setColorFilter(if (Engine.repeat != 0) th.acc else th.mute, PorterDuff.Mode.SRC_IN)
        onTick()
    }

    override fun onTick() {
        if (dragging) return
        val d = Engine.duration()
        val p = Engine.position()
        if (d > 0) {
            seek.progress = (p.toLong() * 1000 / d).toInt().coerceIn(0, 1000)
            tDur.text = fmtTime(d.toLong())
        } else if (Engine.current != null) tDur.text = "0:00"
        tCur.text = fmtTime(p.toLong())
    }
}

// =====================================================================================
// LIBRARY
// =====================================================================================
class LibraryPage(act: MainActivity) : Page(act) {
    private val countText = text(13f, th.mute, false)
    private val search = EditText(act)
    private val list = ListView(act)
    private val emptyText = text(15f, th.mute, false)
    private val chips = HashMap<String, TextView>()
    private var favOnly = false
    private var shown: List<Track> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: Row()
            row.bind(shown[position], position)
            return row.root
        }
    }

    /** One list row. [root] is what the ListView shows. */
    private inner class Row {
        val root = LinearLayout(act)
        val cv = CoverView(act)
        val nm = text(16f, th.fg)
        val ar = text(12f, th.mute, false)
        val du = text(12f, th.mute, false)
        val hb = act.iconView(R.drawable.ic_n_heart, th.mute, 26, 3)
        var tag: String = ""
        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(0, act.dp(8), 0, act.dp(8))
            root.tag = this
            root.addView(cv, lp(act.dp(42), act.dp(42)))
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            nm.maxLines = 1; nm.ellipsize = TextUtils.TruncateAt.END
            ar.maxLines = 1; ar.ellipsize = TextUtils.TruncateAt.END
            col.addView(nm, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(ar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(2) })
            root.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = act.dp(12); it.rightMargin = act.dp(8) })
            root.addView(du, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = act.dp(6) })
            root.addView(hb, lp(act.dp(30), act.dp(30)))
        }

        fun bind(t: Track, position: Int) {
            val isCur = Engine.current?.key == t.key
            nm.text = t.title
            nm.setTextColor(if (isCur) th.acc else th.fg)
            ar.text = t.artist
            du.text = if (t.durMs > 0) fmtTime(t.durMs) else ""
            val fav = Lib.isFav(t.key)
            hb.setImageResource(if (fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
            hb.setColorFilter(if (fav) th.acc else th.mute, PorterDuff.Mode.SRC_IN)
            cv.label = t.title
            cv.seed = t.title.hashCode()
            val sig = t.key + "|" + t.customCover
            cv.bitmap = Covers.peek(t, 96)
            tag = sig
            if (cv.bitmap == null) {
                Covers.load(act, t, 96) { b -> if (tag == sig) cv.bitmap = b }
            }
            val play = View.OnClickListener { playAt(position) }
            root.setOnClickListener(play)
            nm.setOnClickListener(play)
            ar.setOnClickListener(play)
            hb.setOnClickListener { Lib.toggleFav(t.key) }
            val menu = View.OnLongClickListener { act.showMenu(t); true }
            val edit = View.OnLongClickListener { act.showEdit(t); true }
            nm.setOnLongClickListener(menu)
            ar.setOnLongClickListener(menu)
            cv.setOnLongClickListener(edit)
            cv.setOnClickListener(play)
        }
    }

    private fun playAt(position: Int) {
        if (position < 0 || position >= shown.size) return
        Engine.setQueue(shown, position, true)
        act.go(0)
    }

    init {
        search.hint = "Search songs or artists"
        search.setHintTextColor(th.mute)
        search.setTextColor(th.fg)
        search.textSize = 15f
        search.typeface = REGULAR_FONT
        search.setSingleLine(true)
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(act.dp(12), 0, act.dp(12), 0)
        search.background = act.boxBackground(th.row, th.line, 1)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { reload() }
        })
        list.adapter = adapter
        list.divider = android.graphics.drawable.ColorDrawable(th.line)
        list.dividerHeight = 1
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.cacheColorHint = Color.TRANSPARENT
        list.setBackgroundColor(Color.TRANSPARENT)
        view = build()
        reload()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val c = text(15f, th.fg, false)
        c.text = label
        c.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6))
        c.setOnClickListener { onClick() }
        chips[label] = c
        return c
    }

    private fun build(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(22), act.dp(4), act.dp(22), 0)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val back = act.iconView(R.drawable.ic_back, th.fg, 30, 0)
        back.setOnClickListener { act.go(0) }
        head.addView(back, lp(act.dp(30), act.dp(34)))
        val ttl = text(28f, th.fg)
        ttl.text = "library"
        head.addView(ttl, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(countText, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = act.dp(12); it.topMargin = act.dp(8) })
        val loc = act.iconView(R.drawable.ic_locate, th.fg, 36, 6)
        loc.setOnClickListener { locate(false) }
        head.addView(loc, lp(act.dp(36), act.dp(36)))
        col.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        for (s in listOf("name", "recent", "artist")) {
            row.addView(chip(s) { Prefs.sort = s; reload() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = act.dp(8) })
        }
        row.addView(chip("favorites") { favOnly = !favOnly; reload() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        col.addView(hs, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(12) })

        col.addView(search, lp(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(44)).also { it.topMargin = act.dp(12); it.bottomMargin = act.dp(6) })

        val frame = FrameLayout(act)
        frame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyText.gravity = Gravity.CENTER
        emptyText.text = "No songs found"
        frame.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { it.topMargin = act.dp(40) })
        col.addView(frame, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun drawChips() {
        for ((label, c) in chips) {
            val on = if (label == "favorites") favOnly else Prefs.sort == label
            c.setTextColor(if (on) th.acc else th.fg)
            c.background = act.boxBackground(Color.TRANSPARENT, if (on) th.acc else th.line, 1)
        }
    }

    fun reload() {
        shown = Lib.view(Prefs.sort, favOnly, search.text.toString())
        val total = Lib.tracks.size
        countText.text = if (shown.size == total) "$total song" + (if (total == 1) "" else "s") else "${shown.size} of $total"
        emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        drawChips()
        adapter.notifyDataSetChanged()
    }

    override fun refresh() { reload() }

    override fun onShow() { }

    /** Scrolls so the playing song is in the middle of the list. */
    fun locate(instant: Boolean) {
        val cur = Engine.current ?: return
        var i = shown.indexOfFirst { it.key == cur.key }
        if (i < 0 && (favOnly || search.text.isNotEmpty())) {
            favOnly = false
            search.setText("")
            reload()
            i = shown.indexOfFirst { it.key == cur.key }
        }
        if (i < 0) return
        list.post {
            val offset = (list.height / 2) - act.dp(32)
            list.setSelectionFromTop(i, offset.coerceAtLeast(0))
        }
    }
}

// =====================================================================================
// SETTINGS
// =====================================================================================
class SettingsPage(act: MainActivity) : Page(act) {
    private val scanMsg = text(13f, th.mute, false)
    private val scanBtn = text(15f, th.fg, false)
    private val addBtn = text(15f, th.fg, false)
    private val presetChips = HashMap<String, TextView>()
    private val gainValues = ArrayList<TextView>()
    private val gainBars = ArrayList<SeekBar>()
    private val scanToggle = ToggleView(act)
    private val eqToggle = ToggleView(act)
    private val themeBtns = ArrayList<TextView>()

    private val PRESETS = linkedMapOf(
        "flat" to intArrayOf(0, 0, 0, 0, 0),
        "bass" to intArrayOf(8, 5, 0, -1, -2),
        "vocal" to intArrayOf(-3, -1, 4, 4, 1),
        "treble" to intArrayOf(-2, -1, 0, 5, 8),
        "rock" to intArrayOf(5, 3, -2, 3, 5)
    )
    private val FREQ = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

    init { view = build(); refresh() }

    private fun sectionTitle(s: String): TextView {
        val t = text(24f, th.fg)
        t.text = s
        return t
    }

    private fun build(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(22), act.dp(4), act.dp(22), act.dp(24))

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val back = act.iconView(R.drawable.ic_back, th.fg, 30, 0)
        back.setOnClickListener { act.go(1) }
        head.addView(back, lp(act.dp(30), act.dp(34)))
        val ttl = text(28f, th.fg)
        ttl.text = "settings"
        head.addView(ttl, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- scan
        val scanRow = LinearLayout(act)
        scanRow.orientation = LinearLayout.HORIZONTAL
        scanRow.gravity = Gravity.CENTER_VERTICAL
        val st = text(20f, th.fg)
        st.text = "scan for songs"
        scanRow.addView(st, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanToggle.accent = th.acc
        scanToggle.ink = th.fg
        scanToggle.on = Prefs.scanOn
        scanToggle.onChange = { on ->
            Prefs.scanOn = on
            drawScan()
            if (on && Lib.tracks.isEmpty()) act.startScan()
        }
        scanRow.addView(scanToggle, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scanRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(18) })

        val desc = text(13f, th.mute, false)
        desc.text = "Finds the music on your phone (recordings and ringtones are skipped). Songs added with add files stay where they are."
        col.addView(desc, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(6) })

        val btns = LinearLayout(act)
        btns.orientation = LinearLayout.HORIZONTAL
        scanBtn.text = "scan again"
        scanBtn.setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
        scanBtn.background = act.boxBackground(Color.TRANSPARENT, th.line, 1)
        scanBtn.setOnClickListener { act.startScan() }
        addBtn.text = "add files"
        addBtn.setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
        addBtn.background = act.boxBackground(Color.TRANSPARENT, th.line, 1)
        addBtn.setOnClickListener { act.pickFiles() }
        btns.addView(scanBtn, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = act.dp(10) })
        btns.addView(addBtn, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(btns, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(10) })
        col.addView(scanMsg, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(8) })

        // ---- theme
        col.addView(sectionTitle("theme"), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(22) })
        val trow = LinearLayout(act)
        trow.orientation = LinearLayout.HORIZONTAL
        for (t in Themes.all) {
            val b = TextView(act)
            b.text = t.name
            b.gravity = Gravity.CENTER
            b.textSize = 13f
            b.typeface = Typeface_bold()
            b.setTextColor(t.swatchText)
            val bgc = Themes.swatch(t)
            b.tag = t.name
            b.setOnClickListener { act.changeTheme(t.name) }
            themeBtns.add(b)
            val g = act.boxBackground(bgc, if (t.name == th.name) Color.WHITE else th.line, if (t.name == th.name) 2 else 1, 6)
            b.background = g
            trow.addView(b, lp(0, act.dp(54), 1f).also { it.rightMargin = act.dp(8) })
        }
        col.addView(trow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(10) })

        // ---- equalizer
        val eqHead = LinearLayout(act)
        eqHead.orientation = LinearLayout.HORIZONTAL
        eqHead.gravity = Gravity.CENTER_VERTICAL
        eqHead.addView(sectionTitle("equalizer"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        eqToggle.accent = th.acc
        eqToggle.ink = th.fg
        eqToggle.on = Prefs.eqOn
        eqToggle.onChange = { on -> Prefs.eqOn = on; Engine.applyEq() }
        eqHead.addView(eqToggle, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(eqHead, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(22) })

        val prow = LinearLayout(act)
        prow.orientation = LinearLayout.HORIZONTAL
        for (name in PRESETS.keys) {
            val c = text(14f, th.fg, false)
            c.text = name
            c.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
            c.setOnClickListener {
                Prefs.eqPreset = name
                Prefs.eqGains = PRESETS[name]!!
                drawEq()
                Engine.applyEq()
            }
            presetChips[name] = c
            prow.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = act.dp(6) })
        }
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(prow)
        col.addView(hs, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(10) })

        for (i in 0 until 5) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val lab = text(12f, th.mute, false)
            lab.text = FREQ[i]
            r.addView(lab, lp(act.dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
            val sb = SeekBar(act)
            sb.max = 24
            sb.progressTintList = android.content.res.ColorStateList.valueOf(th.acc)
            sb.thumbTintList = android.content.res.ColorStateList.valueOf(th.fg)
            sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(th.fg)
            sb.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) act.noSwipe = true
                false
            }
            val idx = i
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val g = Prefs.eqGains
                    g[idx] = p - 12
                    Prefs.eqGains = g
                    Prefs.eqPreset = ""
                    gainValues[idx].text = "${g[idx]} dB"
                    drawPresetChips()
                    Engine.applyEq()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            gainBars.add(sb)
            r.addView(sb, lp(0, act.dp(30), 1f))
            val gv = text(12f, th.mute, false)
            gv.gravity = Gravity.END
            gainValues.add(gv)
            r.addView(gv, lp(act.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(4) })
        }
        val note = text(12f, th.mute, false)
        note.text = "The equalizer applies to everything played in the app."
        col.addView(note, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(12) })

        val foot = text(12f, th.mute, false)
        foot.gravity = Gravity.CENTER
        val footHtml = "<b>muzi</b> music built by <b>logman</b><br>0.0.1"
        foot.text = if (android.os.Build.VERSION.SDK_INT >= 24) {
            android.text.Html.fromHtml(footHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(footHtml)
        }
        col.addView(foot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = act.dp(36) })

        val sv = ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun Typeface_bold() = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

    private fun drawScan() {
        scanBtn.visibility = if (Prefs.scanOn) View.VISIBLE else View.GONE
    }

    private fun drawPresetChips() {
        for ((name, c) in presetChips) {
            val on = Prefs.eqPreset == name
            c.setTextColor(if (on) th.acc else th.fg)
            c.background = act.boxBackground(Color.TRANSPARENT, if (on) th.acc else th.line, 1)
        }
    }

    private fun drawEq() {
        val g = Prefs.eqGains
        for (i in 0 until 5) {
            gainBars[i].progress = g[i] + 12
            gainValues[i].text = "${g[i]} dB"
        }
        drawPresetChips()
    }

    fun setScanMessage(m: String) { scanMsg.text = m }

    override fun refresh() {
        scanToggle.on = Prefs.scanOn
        drawScan()
        drawEq()
        scanMsg.text = Lib.lastScanMsg
    }
}
