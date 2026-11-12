package com.logman.muzi

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** Light-theme home-screen widget (4x1). Shares data with the dark variant. */
class PlayerWidgetProviderLight : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, PlayerWidgetProvider.buildFor(context, R.layout.widget_player_light))
    }
    companion object {
        fun push(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidgetProviderLight::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, PlayerWidgetProvider.buildFor(ctx, R.layout.widget_player_light))
            } catch (_: Exception) { }
        }
    }
}
