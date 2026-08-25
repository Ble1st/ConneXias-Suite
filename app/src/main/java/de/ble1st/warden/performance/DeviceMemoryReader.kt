package de.ble1st.warden.performance

import android.app.ActivityManager
import android.content.Context

/**
 * Performance-Monitoring-Fenster (2026-08-25, auf Nutzerwunsch: "Lokale Statistik-Erfassung via
 * ActivityManager.MemoryInfo, BatteryManager"). Geräteweiter Speicherzustand — `availMemBytes`/
 * `totalMemBytes`/`lowMemory` kommen direkt aus `ActivityManager.getMemoryInfo()`, ohne
 * Sonderberechtigung erreichbar.
 *
 * **Bewusst kein Pro-App-Speicherverbrauch:** `ActivityManager.getRunningAppProcesses()` liefert
 * laut Android-Dokumentation seit API 21 für Nicht-System-/Nicht-Signature-Apps ausschließlich die
 * *eigenen* Prozesse zurück — das gilt unverändert auch für eine Device-Owner-App wie Warden (der
 * Device-Owner-Status verändert diese plattformseitige Prozess-Sichtbarkeitsgrenze nicht). Ein
 * "CPU/Memory pro fremder App"-Wert, wie ihn die Nutzeranfrage ursprünglich skizzierte
 * (`ActivityManager.RunningAppProcessInfo`), ist auf einem nicht gerooteten Gerät schlicht nicht
 * beschaffbar — `de.ble1st.warden.performance.AppUsageReader`s Vordergrund-Nutzungszeit
 * (`UsageStatsManager`) ist der einzige real verfügbare Aktivitäts-Näherungswert für einzelne
 * Apps und wird deshalb als solcher gekennzeichnet, nicht als CPU-/RAM-Wert ausgegeben.
 */
data class DeviceMemorySnapshot(
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
)

class DeviceMemoryReader(private val context: Context) {

    fun read(): DeviceMemorySnapshot? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return DeviceMemorySnapshot(
            availMemBytes = info.availMem,
            totalMemBytes = info.totalMem,
            thresholdBytes = info.threshold,
            lowMemory = info.lowMemory,
        )
    }
}
