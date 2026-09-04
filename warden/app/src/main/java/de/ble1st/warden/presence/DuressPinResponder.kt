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
 *
 * **Fallback bei Reboot-Scheitern** (z. B. SecurityException, wenn Warden zwischenzeitlich den
 * Device-Owner-Status verloren hat): ein erfolgreicher Reboot versetzt in BFU (bestmöglicher
 * Schutz). Schlägt er fehl, ist das Gerät weiterhin entsperrt — ein ungeschützter Zustand, der bei
 * einer erkannten Duress-Situation nicht akzeptabel ist. Deshalb wird als Fallback
 * `DevicePolicyManager.lockNow()` versucht, das zumindest den Bildschirm sofort sperrt, auch wenn
 * es credential-verschlüsselten Speicher erreichbar laesst, sobald die echte PIN eingegeben wird.
 * [trigger] liefert `true`, wenn entweder Reboot oder LockNow erfolgreich war.
 */
class DuressPinResponder(context: Context) {
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }
    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    /** Löst die Schutzreaktion aus. Liefert `true`, wenn der Reboot angestoßen wurde oder der
     *  [lockNow]-Fallback erfolgreich war; `false`, wenn beide scheiterten (dann bleibt das Gerät
     *  entsperrt — der Aufrufer sollte das protokollieren). */
    fun trigger(): Boolean {
        // reboot() wirft SecurityException/IllegalStateException bei Misserfolg, liefert kein
        // Boolean — der frühere Aufrufer (WardenPinActivity) fing die Exception zwar ab, hatte
        // aber keinen Fallback, sodass das Gerät bei einem Reboot-Scheitern ungeschützt blieb.
        val rebooted = runCatching { dpm.reboot(admin) }.isSuccess
        if (rebooted) return true
        // Fallback: zumindest sofort sperren. lockNow() wirft ebenfalls bei nicht-Owner, aber
        // der Versuch ist besser als nichts — ein gesperrter Bildschirm schützt immerhin vor
        // gelegentlichem Zugriff, auch wenn credential-verschlüsselter Speicher mit der echten
        // PIN wieder erreichbar wäre.
        return runCatching { dpm.lockNow() }.isSuccess
    }
}
