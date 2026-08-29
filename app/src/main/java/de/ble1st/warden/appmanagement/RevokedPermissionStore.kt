package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert, welche gefährlichen Rechte [DangerousPermissionRevoker] für welches Paket bereits
 * entzogen hat (2026-08-29, Lückenschluss zu Feature 3 "Permission Auto-Block" aus
 * `docs/umsetzungsplan-7-features.md`, s. Memo `warden-permission-autoblock-and-score-2026-08-29`).
 * Vorher gab es dafür keinen Speicher: [SuspiciousAppScanController.enforce] rief
 * `revokeDangerousPermissions` auf und loggte nur das Ergebnis, aber
 * [SuspiciousAppScanController.trust] konnte den Entzug nicht rückgängig machen, weil nirgendwo
 * stand, *welche* Rechte das waren — "als vertrauenswürdig markieren" hob das Einfrieren auf, ließ
 * zuvor entzogene gefährliche Rechte aber dauerhaft auf `DENIED` stehen, obwohl sich der Fund
 * gerade als Fehlalarm herausgestellt hatte.
 *
 * Reine Namensliste pro Paket, keine Verschlüsselung über [de.ble1st.warden.crypto.EnvelopeFile] —
 * dieselbe Abwägung wie bei [de.ble1st.warden.cellsecurity.CellSecurityStorage]: Permission-Namen
 * sind kein schützenswertes Geheimnis, nur wiederauffindbar müssen sie sein. Normale
 * (nicht geräteverschlüsselte) SharedPreferences, anders als die Boot-vor-Entsperrung-Speicher
 * dieses Pakets — dieser Speicher wird ausschließlich aus dem laufenden Scanner heraus gelesen/
 * geschrieben, nie vor der ersten Entsperrung.
 */
object RevokedPermissionStore {
    private const val PREFS_NAME = "warden_revoked_permissions"
    private const val SEPARATOR = "|"

    /** Ergänzt statt überschreibt — ein Paket kann über mehrere Scan-Läufe hinweg schrittweise
     * weitere Rechte deklarieren/entzogen bekommen, bevor der Fund einmal quittiert wird. */
    fun record(context: Context, packageName: String, permissions: List<String>) {
        if (permissions.isEmpty()) return
        val prefs = prefs(context)
        val merged = (decode(prefs.getString(packageName, null)) + permissions).distinct()
        prefs.edit { putString(packageName, merged.joinToString(SEPARATOR)) }
    }

    /** Liefert die gemerkten Rechte und löscht den Eintrag in einem Zug — [consume] statt ein
     * reines `load`, weil der einzige Aufrufer ([SuspiciousAppScanController.trust]) sie sofort
     * wiederherstellt; ein stehenbleibender Eintrag würde bei einem künftigen erneuten Fund für
     * dasselbe Paket fälschlich als "schon behandelt" erscheinen. */
    fun consume(context: Context, packageName: String): List<String> {
        val prefs = prefs(context)
        val permissions = decode(prefs.getString(packageName, null))
        if (permissions.isNotEmpty()) prefs.edit { remove(packageName) }
        return permissions
    }

    /** Wie [consume], löscht den Eintrag aber nicht — für reine Anzeigezwecke (z. B. ein
     * "gesperrt"-Indikator in [de.ble1st.warden.ui.PermissionAuditScreen]). */
    fun peek(context: Context, packageName: String): List<String> =
        decode(prefs(context).getString(packageName, null))

    private fun decode(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
