package xyz.botolog.ghostify.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Repaints every home-screen instance of this widget.
 *
 * Use this **instead of** `androidx.glance.appwidget.updateAll`.
 *
 * `updateAll` asks [androidx.glance.appwidget.GlanceAppWidgetManager] for the ids of "this"
 * widget, and that manager answers from a persisted `receiver -> provider name` map whose
 * provider name is the **canonical name of the `GlanceAppWidget` subclass**
 * (`GlanceAppWidgetManagerKt.reverseMapping`). That only works while those class names are
 * stable, and on a minified release build they are not — R8 is free to rename (and, as
 * shipped, to *merge*) the widget classes, which made the Playlists and Library receivers
 * resolve to the player's provider name and let a player repaint land on their host views.
 * See the Glance keeps in `proguard-rules.pro` for the packaging half of the fix.
 *
 * The ids used here come from the widget's own `AppWidgetProvider` component — the same
 * [ComponentName] the system uses to deliver `APPWIDGET_UPDATE` — so a repaint can only ever
 * touch this widget's own instances, whatever minification did to the class names.
 *
 * @param receiver the `AppWidgetProvider` that registers this widget, e.g.
 *   [PlayerWidgetReceiver]. Named explicitly rather than derived from the widget instance,
 *   because Glance's own `receiver` link is internal.
 */
internal suspend fun GlanceAppWidget.updateAllInstances(
    context: Context,
    receiver: Class<out GlanceAppWidgetReceiver>,
) {
    val ids = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, receiver))
    for (id in ids) {
        update(context, AppWidgetId(id))
    }
}
