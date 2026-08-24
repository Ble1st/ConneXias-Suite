package de.ble1st.warden.integrity

import android.os.Build

/**
 * Prüft, ob das Betriebssystem selbst ein Debug-/Engineering-Build ist (Konzept 2b/(5):
 * "Engineering-/User-Debug-OS-Builds: Warden prüft `Build.TYPE`/`ro.debuggable` ... auf einem
 * debuggable OS wird eine sichtbare Warnung geloggt und angezeigt — das Vertrauensmodell setzt
 * ein user-Build voraus").
 *
 * Nutzt bewusst nur [Build.TYPE] (öffentliche API) statt Reflection auf das interne
 * `ro.debuggable`-Systemproperty (`android.os.SystemProperties`, hidden API, seit Android 9
 * je nach Greylist/Denylist-Status nicht zuverlässig zugänglich, auf manchen OEM-Bildern ggf.
 * ganz blockiert): `Build.TYPE` ist der öffentlich dokumentierte Proxy für dasselbe Signal —
 * AOSP setzt `ro.debuggable=1` exakt für die Build-Typen "userdebug" und "eng", nie für "user".
 */
class DebuggableOsStatusReader {

    fun isDebuggableOs(): Boolean = Build.TYPE != "user"

    fun buildType(): String = Build.TYPE
}
