package de.ble1st.warden.presence

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Reagiert auf eine erkannte Duress-PIN-Eingabe (GrapheneOS-Vorbild "Duress PIN", 2026-08-22, auf
 * Nutzerwunsch übernommen) — anders als dort **kein** echter Wipe:
 * [de.ble1st.warden.domain.presence.SensitiveAction.WIPE_DATA] bleibt bewusst ein geloggter Stub
 * (dortiges Klassendoc), dieses Feature erweitert ihn nicht und arbeitet komplett unabhängig davon.
 * Stattdessen dieselbe bereits etablierte, reversible Schutzreaktion wie
 * [de.ble1st.warden.autoreboot.AutoRebootController] für ein verlorenes/gestohlenes Gerät: ein
 * sofortiger `DevicePolicyManager.reboot()` versetzt das Gerät zurück in den Vor-Entsperr-Zustand
 * (BFU) — credential-verschlüsselter Speicher wird wieder unzugänglich, ohne dass Warden selbst
 * irgendwelche Nutzerdaten löschen müsste.
 *
 * Aufgerufen von [WardenPinActivity], sobald die dort eingegebene PIN gegen den in
 * [de.ble1st.warden.pin.WardenDuressPinStorage] hinterlegten Hash verifiziert — **nicht** gegen den
 * Haupt-PIN-Hash. Auf dem Bildschirm sieht das für eine unter Zwang zusehende dritte Person genauso
 * aus wie eine falsche Haupt-PIN (dieselbe "Falsche PIN"-Meldung, derselbe Anti-Hammering-Zähler
 * wird erhöht) — der eigentliche Reboot ist die einzige sichtbare Folge, kein Hinweis im UI-Text
 * verrät, dass etwas anderes als ein simpler Tippfehler passiert ist.
 */
class DuressPinResponder(context: Context) {
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }
    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun trigger() {
        dpm.reboot(admin)
    }
}
