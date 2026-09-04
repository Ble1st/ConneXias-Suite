package de.ble1st.warden.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Reiner OS-Anschlusspunkt für [WardenStatusWidget] (AppWidgetProviderInfo in
 * `res/xml/warden_status_widget_info.xml`, Manifest-`<receiver>`-Eintrag) — enthält selbst keine
 * Logik, s. [WardenStatusWidget]-Klassendoc dafür. */
class WardenStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WardenStatusWidget()
}
