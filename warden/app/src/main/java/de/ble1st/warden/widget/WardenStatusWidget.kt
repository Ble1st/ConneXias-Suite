package de.ble1st.warden.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.ble1st.warden.R
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.domain.score.SecurityScoreLevel
import de.ble1st.warden.profile.AutoProfileStorage
import de.ble1st.warden.score.SecurityScoreHistoryStore
import de.ble1st.warden.ui.WardenStatusActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feature 6 "Quick-Action Widgets" aus `docs/umsetzungsplan-7-features.md` — ursprünglich
 * 2026-08-29 bewusst komplett nicht gebaut (s. `warden/CLAUDE.md`, Abschnitt "Feature 6
 * (\"Quick-Action Widgets\") war deliberately not built"): ein tap-auslösender Homescreen-
 * `AppWidgetProvider` liefe außerhalb jeder Activity und könnte nicht durch
 * [de.ble1st.warden.presence.WardenLockActivity] geroutet werden — exakt die Lücke ("wer ein
 * entsperrtes Gerät in der Hand hat, kann einen Safeguard umlegen"), die `WardenLockActivity`
 * ursprünglich schließen sollte. Nachgereicht am 2026-09-03 (Ideenliste-Folgegespräch 3) als
 * reines **Status**-Widget ohne jede Schalt-Aktion — ein Tap öffnet nur [WardenStatusActivity]
 * ganz normal über deren eigenen Start-Intent, läuft also durch denselben `onResume()`-Gate-Check
 * wie jeder andere App-Start auch. Kein Weg, der `WardenLockActivity` umgeht.
 *
 * Zeigt drei bereits an anderer Stelle vorhandene, günstig lesbare Werte — keine neue
 * Datenerhebung, kein periodischer Scan:
 * - Lockdown-Modus ([de.ble1st.warden.bus.ConcordBus.isLockdownModeActive], dieselbe reine
 *   DPM-Statusabfrage wie auf dem Dashboard).
 * - Aktives Profil ([AutoProfileStorage.loadLastEffective], eine einzelne SharedPreferences-Zeile).
 * - Der zuletzt **manuell** berechnete Sicherheits-Score
 *   ([SecurityScoreHistoryStore.entriesWithinWindow], ebenfalls eine reine SharedPreferences-
 *   Lesung). Bewusst **keine** Neuberechnung hier — genau die vier teuren Lesepfade, die
 *   [de.ble1st.warden.ui.SecurityScoreScreen] zu einem eigenen Bildschirm mit explizitem
 *   "Berechnen"-Button statt einer Dashboard-Kennzahl gemacht haben, dürfen erst recht nicht bei
 *   jeder periodischen Widget-Aktualisierung mitlaufen.
 *
 * [loadSnapshot] formatiert Text bereits fertig, hält Roh-Zustände (`lockdownActive`,
 * `scoreLevel`) aber zusätzlich unformatiert vor — `GlanceTheme.colors.*` ist ein
 * `@Composable`-Getter (analog `MaterialTheme.colorScheme`) und kann deshalb erst innerhalb der
 * `GlanceTheme { }`-Composition in [WidgetContent] aufgelöst werden, nicht schon in [loadSnapshot]
 * selbst (das läuft vor `provideContent`, außerhalb jeder Composition).
 *
 * Aktualisierung über `android:updatePeriodMillis` in `res/xml/warden_status_widget_info.xml`
 * (30 Minuten, das von Android selbst durchgesetzte Minimum für dieses Feld — kein zusätzlicher
 * periodischer WorkManager-Worker nötig, s. dortiger Kommentar), plus ein gezielter
 * [requestUpdate]-Aufruf direkt aus [de.ble1st.warden.bus.ConcordBus.applyProfile] — der einzige
 * Aufrufpunkt, den sowohl der manuelle Profil-Tap als auch `AutoProfileController` zwingend
 * durchlaufen, also die eine Stelle, an der ein sofortiges Update nicht auseinanderlaufen kann.
 * **Bewusst nicht** zusätzlich in [de.ble1st.warden.presence.DestructiveActionExecutor] verdrahtet
 * (`LOCKDOWN_MODE_ARM`/`MASTER_SWITCH_REVERT`) — diese Klasse hat absichtlich keinen `Context`
 * (reine Lambda-Injektion, s. deren eigenes Klassendoc), ihn nur für eine kosmetische
 * Widget-Aktualisierung hindurchzureichen wäre eine unnötige Kopplung an einer
 * sicherheitskritischen Stelle; die 30-Minuten-Periodik holt diesen Fall nach.
 *
 * **Zwei Aktions-Schaltflächen ergänzt (2026-09-05, Nutzerwunsch "ein Quick-Action-Menü für
 * Lockdown und Sentinel-Lockdown-Task, aber mit Schalter in den Einstellungen").** Anders als die
 * drei Status-Zeilen oben lösen diese beiden tatsächlich etwas aus — genau die Lücke, wegen der
 * dieses Widget ursprünglich rein statusanzeigend gebaut wurde (s. Klassendoc oben). Deshalb
 * **nur sichtbar, wenn [WidgetQuickActionsStore.isEnabled] zutrifft** (Default aus) — der neue
 * Einstellungs-Schalter, den der Nutzer explizit verlangt hat, bevor diese Schaltflächen überhaupt
 * gezeichnet werden. Die eigentliche Ausführung läuft über [LockdownArmQuickActionCallback]/
 * [KioskEngageQuickActionCallback] (`actionRunCallback`, nicht `actionStartActivity` mit Extra —
 * s. deren gemeinsames Klassendoc, warum das für eine exportierte Launcher-Activity wichtig ist)
 * und bleibt strukturell hinter [de.ble1st.warden.presence.WardenLockSession]: ohne bereits
 * gültige Sitzung landet ein Tap zunächst ganz normal in `WardenLockActivity`, exakt wie jeder
 * andere App-Start.
 */
class WardenStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot(context) }
        provideContent {
            WidgetContent(snapshot)
        }
    }

    @Composable
    private fun WidgetContent(snapshot: WidgetSnapshot) {
        GlanceTheme {
            val lockdownColor = when (snapshot.lockdownActive) {
                true -> GlanceTheme.colors.primary
                false -> GlanceTheme.colors.onSurfaceVariant
                null -> GlanceTheme.colors.error
            }
            val scoreColor = when (snapshot.scoreLevel) {
                null -> GlanceTheme.colors.onSurfaceVariant
                SecurityScoreLevel.SEHR_GUT, SecurityScoreLevel.GUT -> GlanceTheme.colors.primary
                SecurityScoreLevel.VERBESSERUNGSWUERDIG -> GlanceTheme.colors.tertiary
                SecurityScoreLevel.KRITISCH -> GlanceTheme.colors.error
            }
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .padding(12.dp)
                    .clickable(actionStartActivity<WardenStatusActivity>()),
            ) {
                Text(
                    text = snapshot.title,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                StatusLine(text = snapshot.lockdownLine, color = lockdownColor)
                StatusLine(text = snapshot.profileLine, color = GlanceTheme.colors.onSurfaceVariant)
                StatusLine(text = snapshot.scoreLine, color = scoreColor)
                if (snapshot.quickActionsEnabled) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    QuickActionRow(
                        text = snapshot.lockdownArmActionLabel,
                        color = GlanceTheme.colors.error,
                        onClick = actionRunCallback<LockdownArmQuickActionCallback>(),
                    )
                    QuickActionRow(
                        text = snapshot.kioskEngageActionLabel,
                        color = GlanceTheme.colors.error,
                        onClick = actionRunCallback<KioskEngageQuickActionCallback>(),
                    )
                }
            }
        }
    }

    /** Eigene Zeile statt Wiederverwendung von [StatusLine] — trägt ein zweites `clickable`
     * (den `actionRunCallback`), das Umgebende `clickable(actionStartActivity<...>())` auf der
     * äußeren `Column` bleibt für den Rest der Karte unverändert bestehen; Glance verschachtelt
     * `clickable`-Modifier pro Element, kein Konflikt. */
    @Composable
    private fun QuickActionRow(text: String, color: ColorProvider, onClick: Action) {
        Text(
            text = text,
            style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Start),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp).clickable(onClick),
        )
    }

    @Composable
    private fun StatusLine(text: String, color: ColorProvider) {
        Text(
            text = text,
            style = TextStyle(color = color, fontSize = 12.sp, textAlign = TextAlign.Start),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
        )
    }

    companion object {
        /** Fire-and-forget-Sofortaktualisierung für Aufrufer ohne eigenen Coroutine-Scope (plain
         * Funktionen wie [de.ble1st.warden.bus.ConcordBus.applyProfile]) — die 30-Minuten-Periodik
         * in `warden_status_widget_info.xml` deckt den Rest ab. Verschluckt Fehler bewusst
         * (`runCatching`, kein Log-Eintrag): ein fehlgeschlagenes Widget-Update (z. B. kein
         * Widget aktuell auf dem Homescreen platziert) ist rein kosmetisch und darf nie irgendeine
         * sicherheitsrelevante Aktion beeinträchtigen oder den Audit-Log-Rauschpegel erhöhen. */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { WardenStatusWidget().updateAll(appContext) }
            }
        }
    }
}

/** Reine Anzeige-Momentaufnahme, framework-nah (SharedPreferences-Reads plus ein
 * `ConcordBus`-Aufruf) — keine eigene `domain`-Entscheidungsklasse, weil hier nichts zu
 * entscheiden ist, nur zu formatieren; die eigentliche Score-/Profil-/Lockdown-Logik lebt bereits
 * in ihren jeweiligen Decision-Klassen. `lockdownActive`/`scoreLevel` bleiben zusätzlich zum
 * fertigen Text erhalten, s. Klassendoc oben (Farbauflösung erst in [WardenStatusWidget
 * .WidgetContent]). */
private data class WidgetSnapshot(
    val title: String,
    val lockdownActive: Boolean?,
    val lockdownLine: String,
    val profileLine: String,
    val scoreLevel: SecurityScoreLevel?,
    val scoreLine: String,
    val quickActionsEnabled: Boolean,
    val lockdownArmActionLabel: String,
    val kioskEngageActionLabel: String,
)

private fun loadSnapshot(context: Context): WidgetSnapshot {
    val app = context.applicationContext as WardenApplication
    val lockdownActive = runCatching { app.concordBus.isLockdownModeActive() }.getOrNull()
    val profile = AutoProfileStorage.loadLastEffective(context)
    val lastEntry = runCatching {
        SecurityScoreHistoryStore(context).entriesWithinWindow().lastOrNull()
    }.getOrNull()

    val lockdownLine = when (lockdownActive) {
        true -> context.getString(R.string.widget_lockdown_active)
        false -> context.getString(R.string.widget_lockdown_inactive)
        null -> context.getString(R.string.widget_lockdown_unknown)
    }
    val profileLine = context.getString(
        R.string.widget_profile_line,
        profile?.label ?: context.getString(R.string.status_profile_none),
    )
    val scoreLine = if (lastEntry == null) {
        context.getString(R.string.widget_score_not_calculated)
    } else {
        context.getString(R.string.widget_score_line, lastEntry.total, lastEntry.level.label)
    }
    return WidgetSnapshot(
        title = context.getString(R.string.widget_title),
        lockdownActive = lockdownActive,
        lockdownLine = lockdownLine,
        profileLine = profileLine,
        scoreLevel = lastEntry?.level,
        scoreLine = scoreLine,
        quickActionsEnabled = WidgetQuickActionsStore.isEnabled(context),
        lockdownArmActionLabel = context.getString(R.string.widget_action_lockdown_arm),
        kioskEngageActionLabel = context.getString(R.string.widget_action_kiosk_engage),
    )
}
