package com.logman.muzi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Locale

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

val LIGHT_FONT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
val REGULAR_FONT: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

fun Context.iconView(res: Int, tint: Int, sizeDp: Int, padDp: Int = 0): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    val p = dp(padDp)
    v.setPadding(p, p, p, p)
    v.minimumWidth = dp(sizeDp)
    v.minimumHeight = dp(sizeDp)
    return v
}

fun Context.ringBackground(color: Int, widthDp: Int = 2): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(Color.TRANSPARENT)
    g.setStroke(dp(widthDp), color)
    return g
}

fun Context.boxBackground(fill: Int, stroke: Int, strokeDp: Int = 1, radiusDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.RECTANGLE
    g.setColor(fill)
    if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke)
    if (radiusDp > 0) g.cornerRadius = dpf(radiusDp.toFloat())
    return g
}

/** A layout whose height always equals its width. */
class SquareLayout(c: Context) : FrameLayout(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Cover art: shows a bitmap (centre-cropped) or a generated gradient with a letter / title. */
class CoverView(c: Context) : View(c) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var label: String = ""
        set(v) { field = v; invalidate() }
    var big = false
    var seed = 0
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = LIGHT_FONT
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = bitmap
        if (b != null && !b.isRecycled) {
            val s = minOf(b.width, b.height)
            src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(b, src, dst, paint)
            return
        }
        val h1 = ((seed % 360) + 360) % 360
        val h2 = (h1 + 50) % 360
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.HSVToColor(floatArrayOf(h1.toFloat(), 0.55f, 0.82f)),
            Color.HSVToColor(floatArrayOf(h2.toFloat(), 0.70f, 0.78f)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (label.isEmpty()) return
        if (big) {
            textPaint.textSize = w * 0.07f
            textPaint.letterSpacing = 0.35f
            val t = label.uppercase()
            val shown = if (t.length > 18) t.substring(0, 18) else t
            canvas.drawText(shown, w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
        } else {
            textPaint.textSize = h * 0.45f
            textPaint.letterSpacing = 0f
            canvas.drawText(label.substring(0, 1).uppercase(), w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
        }
    }
}

/** Pill-shaped on/off switch. */
class ToggleView(c: Context) : View(c) {
    var on = false
        set(v) { field = v; invalidate() }
    var accent = Color.BLUE
    var ink = Color.WHITE
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        setOnClickListener {
            on = !on
            onChange?.invoke(on)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(48), context.dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = context.dpf(2f)
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        if (on) {
            p.style = Paint.Style.FILL
            p.color = accent
            canvas.drawRoundRect(r, h / 2, h / 2, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = ink
            canvas.drawRoundRect(r, h / 2, h / 2, p)
        }
        p.style = Paint.Style.FILL
        p.color = if (on) Color.WHITE else ink
        val rad = h * 0.30f
        val cx = if (on) w - h / 2 else h / 2
        canvas.drawCircle(cx, h / 2, rad, p)
    }
}
