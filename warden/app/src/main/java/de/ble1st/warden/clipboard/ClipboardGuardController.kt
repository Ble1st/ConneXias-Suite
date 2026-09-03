package de.ble1st.warden.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.clipboard.ClipboardClearDecision
import de.ble1st.warden.wardenAuditLog

/**
 * Framework-Seite von `docs/design-clipboard-guard.md`, Phase 1. Android beschränkt
 * Zwischenablage-Zugriff seit API 29 auf die Anwendung im Fensterfokus bzw. die Standard-IME (s.
 * Konzept, Abschnitt 1) — jeder Aufruf hier funktioniert nur zuverlässig, solange der aufrufende
 * Prozess tatsächlich im Fokus ist. Es gibt keine `DevicePolicyManager`-API für die
 * Zwischenablage, also keinen `Safeguard`/Registry-Ist-Zustand — nur die lokale Präferenz in
 * [ClipboardGuardStorage].
 *
 * Zwei Aufrufer, zwei Garantien:
 * - [checkAndClearIfStale] — automatischer Pfad, nur wenn [ClipboardGuardStorage.isEnabled];
 *   aufgerufen von `WardenStatusActivity.onWindowFocusChanged(hasFocus = true)`, nicht aus
 *   `onResume()` — Fensterfokus ist zum Zeitpunkt von `onResume()` empirisch noch nicht
 *   garantiert (s. Konzept-Recherche), `onWindowFocusChanged` ist der zuverlässige Zeitpunkt.
 * - [clearNow] — ungegateter manueller Pfad (Dashboard-Button), analog
 *   `ConcordBus.lockNow()`: leert immer, unabhängig vom Auto-Clear-Toggle.
 */
class ClipboardGuardController(private val context: Context) {

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    /** Nie werfend: ein fokusbedingtes Verpuffen von `setPrimaryClip()` (s. Klassendoc) ist kein
     * Fehlerzustand, nur ein No-Op — derselbe Fail-Safe-Stil wie
     * [de.ble1st.warden.usb.UsbAutoLockController.checkAndSync]. */
    fun checkAndClearIfStale() {
        val manager = clipboardManager ?: return
        if (!ClipboardGuardStorage.isEnabled(context)) return
        val description = runCatching { manager.primaryClipDescription }.getOrNull()
        val action = ClipboardClearDecision.action(
            enabled = true,
            hasContent = description != null,
            ageMillis = description?.let(::ageMillisOf),
            thresholdMillis = ClipboardGuardStorage.thresholdMillis(context),
        )
        if (action is ClipboardClearDecision.Action.Clear) {
            performClear(manager, trigger = "Auto (Fokus)")
        }
    }

    /** Gibt zurück, ob tatsächlich Inhalt vorhanden war — `false` ist kein Fehler, nur eine
     * bereits leere Zwischenablage. */
    fun clearNow(): Boolean {
        val manager = clipboardManager ?: return false
        val hadContent = runCatching { manager.primaryClipDescription != null }.getOrDefault(false)
        performClear(manager, trigger = "Manuell")
        return hadContent
    }

    private fun performClear(manager: ClipboardManager, trigger: String) {
        try {
            // Einziger Weg zu "leeren" — es gibt kein removePrimaryClip(), nur Überschreiben mit
            // einem leeren Eintrag (s. Konzept Abschnitt 2.3).
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
            ClipboardGuardStorage.recordClearNow(context, System.currentTimeMillis())
            wardenAuditLog(context).append(Log.INFO, TAG, "Zwischenablage geleert ($trigger)")
        } catch (e: Exception) {
            Log.w(TAG, "Zwischenablage leeren fehlgeschlagen ($trigger)", e)
        }
    }

    private fun ageMillisOf(description: ClipDescription): Long? {
        val timestamp = description.timestamp
        if (timestamp <= 0L) return null
        return (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "ClipboardGuard"
    }
}
