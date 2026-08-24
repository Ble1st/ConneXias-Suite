package de.ble1st.warden.failsafe

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.crypto.Engine
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Meilenstein D.3 (Konzept Abschnitt 9): "Bei Erfolg: `clearUserRestriction()`, PIN-Reset —
 * **nie** `wipeData()`." Wrappt die drei zusammengehörigen `DevicePolicyManager`-Aufrufe für den
 * Reset-Password-Token-Mechanismus (`setResetPasswordToken`/`isResetPasswordTokenActive`/
 * `resetPasswordWithToken`) — Konzept Abschnitt 9, "Ergänzend": "`setResetPasswordToken()`
 * getrennt vom Wartungsschlüssel" (dem Ed25519-Offline-Schlüssel aus D.1/D.2).
 *
 * Kein [de.ble1st.warden.domain.registry.Safeguard] (kein `apply`/`revert`/
 * `isActive` — ein Credential-Reset ist eine einmalige Aktion, keine idempotente An/Aus-Maßnahme).
 * Baut Device-Owner-Zugriff direkt statt über `DpmSafeguard` zu erben, aus demselben Grund.
 *
 * **Token-Persistenz:** das Token selbst ist ein echtes Geheimnis (wer es kennt, kann bei
 * aktiviertem Token das Gerätepasswort zurücksetzen) — [EnvelopeFile]-geschützt wie alles andere
 * im Projekt. Wird beim ersten Aufruf erzeugt und danach wiederverwendet (dasselbe "einmal
 * erzeugen, dauerhaft wiederverwenden"-Muster wie [EnvelopeFile]s eigener DEK-Umgang).
 *
 * **Bewusst nirgends live mit echtem [reset] getestet** (weder hier noch in einem Instrumented
 * Test): `resetPasswordWithToken` ändert das tatsächliche Sperrbildschirm-Credential des
 * Testgeräts — ein Fehlschlag/Absturz mitten im Testlauf könnte das Gerät aussperren, ähnliches
 * Risiko wie bei `DISALLOW_FACTORY_RESET`/`DISALLOW_SAFE_BOOT` (Meilenstein C.2/C.5, s. dortige
 * Klassendocs). [ensureActive] (setzt/aktiviert nur das Token, ändert das Passwort **nicht**) ist
 * dagegen risikofrei und wird live getestet (`:warden-app`s androidTest).
 */
class DeviceCredentialResetter(
    private val context: Context,
    private val tokenFile: EnvelopeFile,
) {
    private fun admin(): ComponentName = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    private fun devicePolicyManager(): DevicePolicyManager =
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }

    private fun loadOrCreateToken(): ByteArray =
        if (tokenFile.hasStorage()) {
            tokenFile.read()
        } else {
            Engine.generateDek(RESET_TOKEN_LENGTH_BYTES).also { tokenFile.write(it) }
        }

    /** Setzt/aktiviert das Reset-Token beim `DevicePolicyManager`. Ändert **kein** Passwort —
     * risikofrei, live testbar (s. Klassendoc). Liefert `true`, wenn das Token danach aktiv ist. */
    fun ensureActive(): Boolean {
        val token = loadOrCreateToken()
        devicePolicyManager().setResetPasswordToken(admin(), token)
        return devicePolicyManager().isResetPasswordTokenActive(admin())
    }

    /** Setzt das Gerätepasswort über das zuvor aktivierte Token neu. **Ändert das echte
     * Sperrbildschirm-Credential** — nur vom bereits verifizierten [OfflineFailsafeExecutor]
     * aufzurufen, nie direkt (s. Klassendoc zum Testrisiko). */
    fun reset(newPassword: String): Boolean {
        val token = loadOrCreateToken()
        return devicePolicyManager().resetPasswordWithToken(admin(), newPassword, token, 0)
    }

    companion object {
        private const val RESET_TOKEN_LENGTH_BYTES: UInt = 32u
    }
}
