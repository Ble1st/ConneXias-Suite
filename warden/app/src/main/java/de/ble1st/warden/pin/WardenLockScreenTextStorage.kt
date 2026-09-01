package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert den **Soll**-Wert für den frei wählbaren Zusatztext auf dem echten OS-Sperrbildschirm
 * (Keyguard) — das eigentliche Setzen läuft über [de.ble1st.warden.registry.LockScreenInfoManager]
 * (`DevicePolicyManager.setDeviceOwnerLockScreenInfo`, s. dortiges Klassendoc für die genaue
 * Mechanik/das Nutzerbeispiel "Dieses Gerät wird von deiner Organisation verwaltet"). Einstellbar
 * über [de.ble1st.warden.ui.SettingsScreen] → [de.ble1st.warden.ui.LockScreenMessageField].
 *
 * **Warum überhaupt ein eigener Soll-Wert-Cache, wenn `DevicePolicyManager` selbst schon einen
 * Getter hat** (`getDeviceOwnerLockScreenInfo()`)? Derselbe Grund wie bei jedem anderen Safeguard
 * hier (Kamera/Bildschirmaufnahme, s. `RegistryReconciler`-Klassendoc: "Soll vs. Ist, korrigieren"):
 * die DPM-Policy kann drift­en (z. B. durch einen OS-Bug oder einen versehentlichen Reset), der
 * Soll-Wert muss unabhängig vom Live-DPM-Zustand irgendwo persistiert bleiben, um sie bei der
 * nächsten Boot-Reconciliation ([de.ble1st.warden.boot.RegistryReconciliationReceiver])
 * wiederherzustellen.
 *
 * Bewusst **Klartext-`SharedPreferences`, nicht [de.ble1st.warden.crypto.EnvelopeFile]**: der Text
 * ist nicht vertraulich — im Gegenteil, er landet ohnehin sichtbar auf dem Sperrbildschirm für
 * jeden, der das Gerät in Händen hält. Ein verlorener/beschädigter Wert bedeutet im schlimmsten
 * Fall "keine Nachricht angezeigt", kein Fail-Safe-Fall wie beim PIN-Blob — deshalb kein
 * Hash-Chain/Envelope-Aufwand nötig.
 *
 * Trotzdem bewusst über [Context.createDeviceProtectedStorageContext] statt normalem
 * `context.getSharedPreferences(...)` (anders als z. B. [de.ble1st.warden.ui.theme.WardenThemePrefs]
 * für den Akzent) — auf Nutzerwunsch ausdrücklich in Device-Protected-Storage abgelegt: derselbe
 * Speicherort wie [WardenPinStorage], erreichbar auch **vor** dem Entsperren (Direct Boot/FBE),
 * falls ein künftiger Boot-Pfad den Soll-Wert schon dort braucht.
 */
object WardenLockScreenTextStorage {
    private const val PREFS_NAME = "warden_lockscreen"
    private const val KEY_TEXT = "owner_message"

    /** Willkürlich, aber großzügig genug für einen kurzen Organisations-/Kontakthinweis, knapp
     * genug um auf dem Sperrbildschirm nicht abgeschnitten zu wirken. */
    const val MAX_LENGTH = 120

    /** `null`, wenn nichts (oder nur Leerraum) hinterlegt ist — der Aufrufer zeigt dann seinen
     * eigenen Default statt eines leeren Textblocks. */
    fun load(context: Context): String? {
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEXT, null)
        return stored?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `text = null` oder nur Leerraum löscht den Soll-Wert wieder. Länger als [MAX_LENGTH] wird
     * abgeschnitten statt abgelehnt — der Aufrufer (UI) begrenzt zwar schon die Eingabe, aber
     * diese Methode bleibt auch bei einem künftigen zweiten Aufrufer sicher. */
    fun save(context: Context, text: String?) {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (normalized == null) remove(KEY_TEXT) else putString(KEY_TEXT, normalized)
            }
    }
}
