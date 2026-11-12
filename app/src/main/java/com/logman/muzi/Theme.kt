package com.logman.muzi

import android.graphics.Color

class AppTheme(
    val name: String,
    val bg: Int,
    val fg: Int,
    val mute: Int,
    val line: Int,
    val acc: Int,
    val row: Int,
    val bang: Int,
    val light: Boolean,
    val swatchText: Int
)

object Themes {
    val dark = AppTheme(
        "dark", 0xFF101318.toInt(), 0xFFF2F4F7.toInt(), Color.argb(140, 242, 244, 247), Color.argb(41, 242, 244, 247),
        0xFF3B5BFF.toInt(), Color.argb(15, 242, 244, 247), 0xFF00D0FF.toInt(), false, 0xFFE8E8E8.toInt()
    )
    val light = AppTheme(
        "light", 0xFFBCE0DF.toInt(), 0xFF14181D.toInt(), Color.argb(148, 20, 24, 29), Color.argb(51, 20, 24, 29),
        0xFF2A3FD6.toInt(), Color.argb(15, 20, 24, 29), 0xFF0093B8.toInt(), true, 0xFF102020.toInt()
    )
    val normal = AppTheme(
        "normal", 0xFF00D0FF.toInt(), 0xFF10262A.toInt(), Color.argb(163, 16, 38, 42), Color.argb(66, 16, 38, 42),
        0xFF004F73.toInt(), Color.argb(20, 16, 38, 42), 0xFF0B2A6B.toInt(), true, 0xFF0B2A6B.toInt()
    )
    val purple = AppTheme(
        "purple", 0xFF1D0F3D.toInt(), 0xFFF4ECFF.toInt(), Color.argb(153, 244, 236, 255), Color.argb(51, 244, 236, 255),
        0xFFB970FF.toInt(), Color.argb(20, 244, 236, 255), 0xFF00D0FF.toInt(), false, 0xFFE8E8E8.toInt()
    )
    val all = listOf(dark, light, normal, purple)
    /** Colour shown inside each theme button in Settings. */
    fun swatch(t: AppTheme): Int = if (t.name == "purple") 0xFF7A2FD0.toInt() else t.bg
    fun byName(n: String): AppTheme = all.firstOrNull { it.name == n } ?: dark
}
