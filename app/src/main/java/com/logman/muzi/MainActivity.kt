package com.logman.muzi

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

class MainActivity : Activity(), Engine.Listener {
    lateinit var appTheme: AppTheme
    /** Set by sliders so that dragging them never counts as a page swipe. */
    var noSwipe = false

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var playPage: PlayerPage
    private lateinit var libPage: LibraryPage
    private lateinit var setPage: SettingsPage
    private lateinit var pages: List<Page>
    private var page = 0
    private var scanning = false
    private var splashShown = false
    private val main = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var swipeOk = false
    private var imageCb: ((Bitmap) -> Unit)? = null

    companion object {
        private const val REQ_FIRST = 79
        private const val REQ_SCAN = 78
        private const val REQ_FILES = 2001
        private const val REQ_IMAGE = 2002
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Lib.init(this)
        Engine.init(this)
        Engine.restore()
        appTheme = Themes.byName(Prefs.theme)
        buildUi(0)
        Engine.addListener(this)
        firstRun()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        Engine.removeListener(this)
        Engine.saveLast()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onPause() {
        Engine.saveLast()
        super.onPause()
    }

    override fun onChanged() {
        runOnUiThread {
            playPage.refresh()
            // In wide layout library is always visible; in portrait refresh only when active
            if (isWideLayout() || page == 1) libPage.refresh()
        }
    }

    override fun onTick() {
        // Player is always visible in wide layout
        if (isWideLayout() || page == 0) runOnUiThread { playPage.onTick() }
    }

    private fun refreshAll() {
        if (!::playPage.isInitialized) return
        playPage.refresh()
        libPage.refresh()
        setPage.refresh()
    }

    private fun handleIntent(i: Intent?) {
        val cmd = i?.getStringExtra("cmd") ?: return
        i.removeExtra("cmd")
        when (cmd) {
            "play" -> Engine.play()
            "next" -> Engine.next(false)
            "prev" -> Engine.prev()
            "fav" -> Engine.current?.let { Lib.toggleFav(it.key) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page == 2) { go(0); return }
        if (!isWideLayout() && page != 0) { go(0); return }
        if (Engine.playing) moveTaskToBack(true) else super.onBackPressed()
    }

    fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ UI
    /** True when the screen is wider than it is tall (landscape) or is a tablet (sw >= 600 dp). */
    fun isWideLayout(): Boolean {
        val dm = resources.displayMetrics
        val swDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return dm.widthPixels > dm.heightPixels || swDp >= 600f
    }

    @Suppress("DEPRECATION")
    private fun buildUi(start: Int) {
        val th = appTheme
        root = FrameLayout(this)
        root.setBackgroundColor(th.bg)

        val dm = resources.displayMetrics

        if (isWideLayout()) {
            buildWideUi(th, dm, start)
        } else {
            buildPortraitUi(th, dm, start)
        }

        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                content.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                content.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }

        if (!splashShown) {
            splashShown = true
            val sp = ImageView(this)
            sp.setImageResource(R.drawable.splash)
            sp.scaleType = ImageView.ScaleType.CENTER_CROP
            sp.setBackgroundResource(R.color.splash_bg)
            sp.isClickable = true
            root.addView(sp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            main.postDelayed({
                sp.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(sp) }.start()
            }, 1300)
        }

        setContentView(root)
        window.setBackgroundDrawable(ColorDrawable(th.bg))
        window.statusBarColor = th.bg
        window.navigationBarColor = th.bg
        if (Build.VERSION.SDK_INT >= 23) {
            var f = 0
            if (th.light) {
                f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = f
        }
        root.requestApplyInsets()
    }

    // ------------------------------------------------------------------ Portrait layout
    @Suppress("DEPRECATION")
    private fun buildPortraitUi(th: AppTheme, dm: android.util.DisplayMetrics, start: Int) {
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        // logo
        val lw = minOf(dp(175), (dm.widthPixels * 0.406f).toInt())
        val lh = lw * 142 / 520
        val logo = buildLogo(th)
        logo.contentDescription = "Muzic"
        val llp = LinearLayout.LayoutParams(lw, lh)
        llp.leftMargin = dp(22)
        llp.topMargin = (dm.heightPixels * 0.10f).toInt()
        llp.bottomMargin = dp(6)
        content.addView(logo, llp)

        playPage = PlayerPage(this)
        libPage = LibraryPage(this)
        setPage = SettingsPage(this)
        pages = listOf(playPage, libPage, setPage)
        val frame = FrameLayout(this)
        for ((i, p) in pages.withIndex()) {
            p.view.visibility = if (i == start) View.VISIBLE else View.GONE
            frame.addView(p.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        page = start
        content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ Wide (landscape / tablet) layout
    @Suppress("DEPRECATION")
    private fun buildWideUi(th: AppTheme, dm: android.util.DisplayMetrics, start: Int) {
        // In wide layout: left pane = NowPlaying (with logo), right pane = Library (no logo).
        // Settings is shown full-screen over the top (FrameLayout overlay).
        content = LinearLayout(this)
        content.orientation = LinearLayout.HORIZONTAL

        // ---- LEFT pane: logo + player ----
        val leftPane = LinearLayout(this)
        leftPane.orientation = LinearLayout.VERTICAL

        val lw = minOf(dp(160), (dm.widthPixels * 0.25f).toInt())
        val lh = lw * 142 / 520
        val logo = buildLogo(th)
        logo.contentDescription = "Muzic"
        val llp = LinearLayout.LayoutParams(lw, lh)
        llp.leftMargin = dp(18)
        llp.topMargin = dp(18)
        llp.bottomMargin = dp(4)
        leftPane.addView(logo, llp)

        playPage = PlayerPage(this)
        leftPane.addView(playPage.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Thin divider between panes
        val divider = View(this)
        divider.setBackgroundColor(th.line)

        // ---- RIGHT pane: library only (no logo) ----
        val rightPane = FrameLayout(this)
        libPage = LibraryPage(this)
        rightPane.addView(libPage.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Settings page lives in an overlay on the root FrameLayout (full-screen)
        setPage = SettingsPage(this)
        setPage.view.visibility = View.GONE

        // Determine split ratio: 45% player / 55% library on landscape phone,
        // or 40/60 on tablet (sw>=600). Use weight.
        val dm2 = resources.displayMetrics
        val swDp = minOf(dm2.widthPixels, dm2.heightPixels) / dm2.density
        val leftWeight = if (swDp >= 600f) 0.42f else 0.45f
        val rightWeight = 1f - leftWeight

        content.addView(leftPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, leftWeight))
        content.addView(divider, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(rightPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, rightWeight))

        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Settings overlay on top of everything
        root.addView(setPage.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pages = listOf(playPage, libPage, setPage)
        // In wide layout page index is irrelevant for player/library, but keep it pointing to 0
        page = if (start == 2) 2 else 0
        if (start == 2) setPage.view.visibility = View.VISIBLE
    }

    /** Builds the layered logo FrameLayout (without size applied — caller sets LayoutParams). */
    private fun buildLogo(th: AppTheme): FrameLayout {
        val logo = FrameLayout(this)
        fun layer(res: Int, color: Int) {
            val iv = ImageView(this)
            iv.setImageResource(res)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            logo.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        layer(R.drawable.logo_muz, th.fg)
        layer(R.drawable.logo_bang, th.bang)
        layer(R.drawable.logo_c, th.fg)
        return logo
    }

    fun changeTheme(name: String) {
        if (name == Prefs.theme) return
        Prefs.theme = name
        appTheme = Themes.byName(name)
        buildUi(2)
    }

    fun go(p: Int) {
        if (p < 0 || p > 2) return
        if (isWideLayout()) {
            // In wide layout player (0) and library (1) are always visible side-by-side.
            // Only animate Settings (2) in/out as a full-screen overlay.
            if (p == 2) {
                // Show settings overlay
                page = 2
                setPage.refresh()
                setPage.view.visibility = View.VISIBLE
                setPage.view.alpha = 0f
                setPage.view.translationX = dp(48).toFloat()
                setPage.view.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                setPage.onShow()
            } else if (page == 2) {
                // Dismiss settings overlay
                page = 0
                setPage.view.animate().translationX(dp(48).toFloat()).alpha(0f).setDuration(220)
                    .withEndAction { setPage.view.visibility = View.GONE }.start()
                // Refresh both always-visible pages
                playPage.refresh()
                libPage.refresh()
                if (p == 1) libPage.locate(true)
            }
            // Navigating between 0 and 1 is a no-op in wide layout (both always shown)
            return
        }
        // Portrait single-page navigation
        if (p == page) return
        val old = pages[page]
        val nw = pages[p]
        val dir = if (p > page) 1 else -1
        val from = page
        page = p
        old.view.animate().cancel()
        old.view.visibility = View.GONE
        nw.view.animate().cancel()
        nw.refresh()
        nw.view.visibility = View.VISIBLE
        nw.view.alpha = 0f
        nw.view.translationX = (dir * dp(48)).toFloat()
        nw.view.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        nw.onShow()
        if (p == 1 && from == 0) nw.view.post { libPage.locate(true) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                noSwipe = false
                swipeOk = true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeOk && !noSwipe) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (Math.abs(dx) > dp(70) && Math.abs(dy) < Math.abs(dx) * 0.6f) {
                        if (isWideLayout()) {
                            if (dx < 0 && page != 2) {
                                go(2)
                            } else if (dx > 0 && page == 2) {
                                go(0)
                            }
                        } else {
                            if (dx < 0) go(page + 1) else go(page - 1)
                        }
                    }
                }
                swipeOk = false
            }
            MotionEvent.ACTION_CANCEL -> swipeOk = false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ permissions and scanning
    private fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPerm() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED

    private fun firstRun() {
        if (Build.VERSION.SDK_INT >= 23) {
            val need = ArrayList<String>()
            if (!Prefs.firstScanDone) {
                if (hasAudioPerm()) {
                    Prefs.firstScanDone = true
                    main.post { startScan() }
                } else need.add(audioPerm())
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_FIRST)
        } else if (!Prefs.firstScanDone) {
            Prefs.firstScanDone = true
            main.post { startScan() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ai = permissions.indexOf(audioPerm())
        val granted = ai >= 0 && grantResults.getOrNull(ai) == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_FIRST) {
            if (ai >= 0) {
                Prefs.firstScanDone = true
                if (granted) startScan()
            }
        } else if (requestCode == REQ_SCAN) {
            if (granted) startScan() else {
                Lib.lastScanMsg = "Permission denied. Allow Muzi to access music and audio in Android Settings, then tap scan again."
                setPage.refresh()
            }
        }
    }

    fun startScan() {
        if (Build.VERSION.SDK_INT >= 23 && !hasAudioPerm()) {
            requestPermissions(arrayOf(audioPerm()), REQ_SCAN)
            return
        }
        if (scanning) return
        scanning = true
        Lib.lastScanMsg = "Scanning your phone\u2026"
        setPage.refresh()
        Lib.scan(this) { added, removed, ok ->
            scanning = false
            Lib.lastScanMsg = when {
                !ok -> "Scan failed. Please try again."
                added > 0 -> "Added $added song" + (if (added == 1) "" else "s") + " from your phone."
                else -> "No new songs found."
            }
            if (ok) Prefs.scanOn = true
            if (Engine.current == null) Engine.restore()
            refreshAll()
            Engine.notifyChanged()
        }
    }

    fun pickFiles() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "audio/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(i, REQ_FILES)
        } catch (e: Exception) { toast("No file picker available") }
    }

    private fun pickImage(cb: (Bitmap) -> Unit) {
        imageCb = cb
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            startActivityForResult(i, REQ_IMAGE)
        } catch (e: Exception) { toast("No image picker available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_FILES) {
            val uris = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) { for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) } }
            else data.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            toast("Adding ${uris.size} song" + (if (uris.size == 1) "" else "s") + "\u2026")
            Lib.importUris(this, uris) { added ->
                Lib.lastScanMsg = if (added > 0) "Added $added song" + (if (added == 1) "" else "s") + "." else "Those songs are already in your library."
                if (Engine.current == null) Engine.restore()
                refreshAll()
                Engine.notifyChanged()
            }
        } else if (requestCode == REQ_IMAGE) {
            val uri = data.data ?: return
            try {
                val bmp = decodeSquare(uri, 512) ?: return
                imageCb?.invoke(bmp)
            } catch (e: Exception) { toast("Could not read that image") }
        }
    }

    private fun decodeSquare(uri: Uri, size: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (b.outWidth / (sample * 2) >= size && b.outHeight / (sample * 2) >= size) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val s = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
        return if (s > size) Bitmap.createScaledBitmap(sq, size, size, true) else sq
    }

    // ------------------------------------------------------------------ dialogs
    private fun panel(): Pair<Dialog, LinearLayout> {
        val th = appTheme
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(18), dp(20), dp(14))
        box.background = boxBackground(th.bg, th.line, 1, 14)
        val sv = ScrollView(this)
        sv.addView(box)
        d.setContentView(sv)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(minOf(dp(340), resources.displayMetrics.widthPixels - dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return Pair(d, box)
    }

    private fun label(s: String, size: Float, color: Int, light: Boolean = true): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = if (light) LIGHT_FONT else REGULAR_FONT
        return t
    }

    private fun rowButton(s: String, color: Int, border: Boolean, onClick: () -> Unit): TextView {
        val t = label(s, 18f, color, false)
        t.setPadding(0, dp(13), 0, dp(13))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(appTheme.line)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

    private fun twoButtons(box: LinearLayout, left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit, rightColor: Int) {
        val th = appTheme
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val l = label(left, 16f, th.fg, false)
        l.gravity = Gravity.CENTER
        l.setPadding(0, dp(12), 0, dp(12))
        l.background = boxBackground(Color.TRANSPARENT, th.line, 1)
        l.setOnClickListener { onLeft() }
        val r = label(right, 16f, Color.WHITE, false)
        r.gravity = Gravity.CENTER
        r.setPadding(0, dp(12), 0, dp(12))
        r.background = boxBackground(rightColor, rightColor, 1)
        r.setOnClickListener { onRight() }
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = dp(10) })
        row.addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) })
    }

    /** Long-press on a song name: share or delete. */
    fun showMenu(t: Track) {
        val th = appTheme
        val (d, box) = panel()
        val ti = label(t.title, 20f, th.fg)
        ti.maxLines = 1
        ti.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(ti)
        box.addView(label(t.artist, 13f, th.mute, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8); it.topMargin = dp(2) })
        box.addView(divider())
        box.addView(rowButton("Share", th.fg, false) { d.dismiss(); share(t) })
        box.addView(divider())
        box.addView(rowButton("Delete", 0xFFFF5A5A.toInt(), false) {
            box.removeAllViews()
            box.addView(label("Delete song?", 20f, th.fg))
            box.addView(label("\u201c" + t.title + "\u201d will be removed from your Muzi library. The original file stays on your phone.", 13f, th.mute, false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
            twoButtons(box, "Cancel", "Delete", { d.dismiss() }, { d.dismiss(); Lib.remove(t); refreshAll() }, 0xFFE04646.toInt())
        })
        box.addView(divider())
        box.addView(rowButton("Cancel", th.mute, false) { d.dismiss() })
        d.show()
    }

    /** Long-press on a song cover: edit name, artist and cover. */
    fun showEdit(t: Track) {
        val th = appTheme
        val (d, box) = panel()
        var pending: Bitmap? = null
        var reset = false
        box.addView(label("Edit song", 22f, th.fg))
        val preview = CoverView(this)
        preview.label = t.title
        preview.seed = t.title.hashCode()
        preview.bitmap = Covers.peek(t, 96)
        Covers.load(this, t, 400) { b -> if (pending == null && !reset) preview.bitmap = b }
        box.addView(preview, LinearLayout.LayoutParams(dp(120), dp(120)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(12) })
        box.addView(rowButton("Change cover", th.fg, false) {
            pickImage { bmp -> pending = bmp; reset = false; preview.bitmap = bmp }
        })
        box.addView(divider())
        box.addView(rowButton("Reset cover", th.fg, false) {
            pending = null; reset = true; preview.bitmap = null
        })
        box.addView(divider())
        fun field(hint: String, value: String): EditText {
            val e = EditText(this)
            e.setText(value)
            e.hint = hint
            e.setTextColor(th.fg)
            e.setHintTextColor(th.mute)
            e.textSize = 16f
            e.setSingleLine(true)
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.setPadding(dp(12), 0, dp(12), 0)
            e.background = boxBackground(th.row, th.line, 1)
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).also { it.topMargin = dp(10) })
            return e
        }
        val et = field("Song name", t.title)
        val ea = field("Artist", t.artist)
        twoButtons(box, "Cancel", "Save", { d.dismiss() }, {
            val nt = et.text.toString().trim().ifEmpty { t.title }
            val na = ea.text.toString().trim().ifEmpty { t.artist }
            t.title = nt
            t.artist = na
            val p = pending
            if (p != null) {
                try {
                    val bos = ByteArrayOutputStream()
                    p.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                    Lib.coverFile(t.key).writeBytes(bos.toByteArray())
                    t.customCover = true
                } catch (e: Exception) { toast("Could not save the cover") }
            } else if (reset) {
                try { Lib.coverFile(t.key).delete() } catch (e: Exception) { }
                t.customCover = false
            }
            Covers.invalidate(t.key)
            Lib.save()
            if (Engine.current?.key == t.key) Engine.refreshCover()
            d.dismiss()
            refreshAll()
            Engine.notifyChanged()
        }, th.acc)
        d.show()
    }

    fun share(t: Track) {
        try {
            val uri = Uri.parse(t.uri)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "audio/*"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_SUBJECT, t.title)
            i.clipData = ClipData.newRawUri(t.title, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share song"))
        } catch (e: Exception) {
            toast("Unable to share this song")
        }
    }
}
