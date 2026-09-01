package de.ble1st.warden.autoreboot

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.autoreboot.AutoRebootDecision
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [AutoRebootDecision] (2026-08-22, "Auto-Reboot nach Zeitfenster ohne
 * Entsperren", auf Nutzerwunsch). [AutoRebootWorker] ruft [checkAndMaybeReboot] periodisch auf
 * (WorkManager, s. dortiges Klassendoc für die 15-Minuten-Intervall-Begründung).
 *
 * **Kein `ACTION_USER_PRESENT`-BroadcastReceiver:** seit Android 8 sind implizite Broadcasts wie
 * `ACTION_USER_PRESENT` für manifest-registrierte Empfänger eingeschränkt — ein zuverlässiger
 * Empfang würde einen dauerhaft laufenden Foreground-Service erfordern (Akku-/Auffälligkeits-
 * Kosten, die dieses Härtungs-Feature nicht rechtfertigt). Stattdessen beobachtet [checkAndMaybeReboot]
 * bei jedem periodischen Lauf selbst live über `KeyguardManager.isKeyguardLocked()`: ist das Gerät
 * gerade *entsperrt*, wird die Baseline ([AutoRebootStorage.saveLastSeenUnlockedMillis]) auf jetzt
 * vorgerückt — dieselbe Information wie ein `ACTION_USER_PRESENT`-Ereignis, nur mit der
 * Ungenauigkeit des WorkManager-Intervalls (bis zu ~15 Minuten) statt exakt. Für ein
 * Stunden-Zeitfenster ist das eine vertretbare Toleranz, kein spürbarer Sicherheitsverlust.
 *
 * **Baseline wird beim Aktivieren sofort gesetzt** (s. [de.ble1st.warden.ui.SettingsScreen]/
 * [de.ble1st.warden.ui.WardenStatusActivity]): verhindert, dass [AutoRebootDecision.shouldReboot]
 * mit `lastSeenUnlockedMillis = null` je in die Verlegenheit kommt, aus einer echten Ungewissheit
 * heraus einen sofortigen, überraschenden Reboot direkt nach dem Einschalten der Funktion
 * auszulösen.
 *
 * **Kein Reset der Baseline nach einem ausgelösten Reboot:** bleibt das Gerät danach weiter
 * unbeaufsichtigt/gesperrt (der eigentliche Zielfall — verloren/gestohlen), löst der nächste
 * periodische Lauf erneut aus, sobald WorkManager nach dem Neustart wieder anläuft — ein
 * abgeschaltetes/verlorenes Gerät zyklisch in den BFU-Zustand zurückzudrängen ist beabsichtigtes
 * Verhalten, kein Bug.
 */
class AutoRebootController(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun checkAndMaybeReboot() {
        val threshold = AutoRebootStorage.loadThresholdHours(context) ?: return
        val keyguardManager = context.getSystemService(KeyguardManager::class.java) ?: return
        val isLocked = keyguardManager.isKeyguardLocked
        val now = System.currentTimeMillis()

        if (!isLocked) {
            AutoRebootStorage.saveLastSeenUnlockedMillis(context, now)
            return
        }

        val shouldReboot = AutoRebootDecision.shouldReboot(
            isLockedNow = true,
            lastSeenUnlockedMillis = AutoRebootStorage.loadLastSeenUnlockedMillis(context),
            nowMillis = now,
            thresholdMillis = threshold * MILLIS_PER_HOUR,
        )
        if (!shouldReboot) return

        val logStore = wardenAuditLog(context)
        try {
            val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
            dpm.reboot(admin)
            logStore.append(Log.WARN, TAG, "Auto-Reboot ausgelöst — Gerät seit >= ${threshold}h ununterbrochen gesperrt")
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "Auto-Reboot fehlgeschlagen: $e")
        }
    }

    private companion object {
        const val TAG = "AutoReboot"
        const val MILLIS_PER_HOUR = 60 * 60 * 1000L
    }
}
