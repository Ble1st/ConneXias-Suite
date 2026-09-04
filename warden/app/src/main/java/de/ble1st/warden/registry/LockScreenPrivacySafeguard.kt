package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Eigene Idee (2026-08-22), dritte Ergänzungsrunde — separat von [KeyguardHardeningSafeguard]
 * (dort: welche *Auth-Methode* zulässig ist), hier: welche *Informationen/Aktionen* der
 * Sperrbildschirm selbst preisgibt, über dieselbe `setKeyguardDisabledFeatures`-Bitmaske:
 * - `KEYGUARD_DISABLE_SECURE_CAMERA` — kein Kamera-Schnellzugriff vom Sperrbildschirm ohne
 *   Entsperren (reduziert die Angriffsfläche, die ohne PIN erreichbar ist).
 * - `KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS` — Benachrichtigungsinhalte (z. B. 2FA-Codes,
 *   Nachrichtentexte) werden auf dem gesperrten Bildschirm nicht mehr im Klartext angezeigt.
 * - `KEYGUARD_DISABLE_WIDGETS_ALL` (vierte Ergänzungsrunde, "weitere Härtung", 2026-08-22) — keine
 *   Sperrbildschirm-Widgets, die sonst zusätzliche Informationen ungeschützt anzeigen könnten.
 * - `KEYGUARD_DISABLE_SHORTCUTS_ALL` (API 34) — keine Sperrbildschirm-Shortcuts (Assistent,
 *   Wallet, Kamera-Shortcut-Leiste). Together with camera/widgets this is the Device-Owner lever
 *   against lock-screen quick access; the QS shade itself is not fully suppressible via public DPM.
 *
 * `KEYGUARD_DISABLE_REMOTE_INPUT` (Inline-Antwort/Quick-Reply vom Sperrbildschirm) bewusst
 * **nicht** mit aufgenommen — die Konstante ist als deprecated markiert (ohne dokumentierten
 * Ersatz, vermutlich wirkungslos auf aktuellen Android-Versionen), toter Code wäre hier
 * irreführend statt schützend.
 *
 * Bewusst eigener Registry-Eintrag statt Erweiterung der `DISABLED_FEATURES`-Konstante in
 * [KeyguardHardeningSafeguard]: unterschiedliche Zielsetzung (Auth-Stärke vs. Informationslecks
 * am Sperrbildschirm), unabhängig voneinander sinnvoll umschaltbar. Zwei unabhängige
 * `KeyguardHardeningSafeguard`/`LockScreenPrivacySafeguard`-Aufrufe kombinieren ihre Bitmasken
 * additiv über den `and`/`or`-Vergleich in [isActive] — kein gegenseitiges Überschreiben, solange
 * beide stets die volle beabsichtigte Maske setzen (dieselbe "verwaltet den Wert exklusiv für die
 * eigenen Bits"-Einschränkung wie bei [KeyguardHardeningSafeguard], hier aber auf **andere** Bits
 * der Maske beschränkt — überschneidungsfrei).
 */
class LockScreenPrivacySafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        val current = devicePolicyManager().getKeyguardDisabledFeatures(admin)
        devicePolicyManager().setKeyguardDisabledFeatures(admin, current or DISABLED_FEATURES)
    }

    override fun revert() {
        val current = devicePolicyManager().getKeyguardDisabledFeatures(admin)
        devicePolicyManager().setKeyguardDisabledFeatures(admin, current and DISABLED_FEATURES.inv())
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getKeyguardDisabledFeatures(admin) and DISABLED_FEATURES == DISABLED_FEATURES

    companion object {
        const val ID = "lock_screen_privacy"

        // Public API 34 name: DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL.
        private const val KEYGUARD_DISABLE_SHORTCUTS_ALL = 1 shl 9

        private const val DISABLED_FEATURES =
            DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA or
                DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS or
                DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL or
                KEYGUARD_DISABLE_SHORTCUTS_ALL
    }
}
