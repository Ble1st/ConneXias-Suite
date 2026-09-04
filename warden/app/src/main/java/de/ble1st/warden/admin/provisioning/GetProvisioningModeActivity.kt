package de.ble1st.warden.admin.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Pflicht-Handler für die QR-/Managed-Provisionierung seit Android 11+ (Meilenstein A.4):
 * Der Setup-Wizard startet nach Download/Installation der DPC-APK diese Activity über
 * [DevicePolicyManager.ACTION_GET_PROVISIONING_MODE], um den Provisioning-Modus zu erfragen.
 * Ohne diesen Handler bricht die Provisionierung nach dem Download kommentarlos ab
 * ("Fehler beim Einrichten des Arbeitsgeräts") — empirisch auf dem Testgerät beobachtet.
 *
 * Warden kennt genau einen Modus: Fully Managed Device (Device Owner). Es gibt keine UI und
 * keine Auswahl — im Unterschied zu TestDPC, das dem Nutzer DO/PO zur Wahl stellt.
 *
 * Fail-Safe (CLAUDE.md Invariante 6, sinngemäß): Erlaubt der Wizard den Fully-Managed-Modus
 * nicht (Allowed-Liste vorhanden, aber ohne ihn), wird abgebrochen — nie stillschweigend in
 * einen anderen Modus (z. B. Work Profile) ausgewichen.
 *
 * Absichtlich `android.app.Activity` + `Theme.NoDisplay` (Muster: TestDPCs
 * ProvisioningSuccessActivity): `finish()` fällt noch in `onCreate`, es wird nie etwas gezeichnet.
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val allowedModes = intent.getIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
        )
        val fullyManagedAllowed = allowedModes == null ||
            allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)

        if (fullyManagedAllowed) {
            Log.i(TAG, "Provisioning-Modus gewählt: FULLY_MANAGED_DEVICE (allowedModes=$allowedModes)")
            val result = Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
            setResult(RESULT_OK, result)
        } else {
            Log.e(
                TAG,
                "FULLY_MANAGED_DEVICE nicht in erlaubten Modi ($allowedModes) — Abbruch statt Ausweichmodus.",
            )
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private companion object {
        const val TAG = "WardenProvisioning"
    }
}
