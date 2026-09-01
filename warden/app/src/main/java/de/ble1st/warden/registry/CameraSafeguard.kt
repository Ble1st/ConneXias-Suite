package de.ble1st.warden.registry

import android.content.Context

/**
 * Sperrt die Kamera geräteweit (Meilenstein C.2, Konzept Abschnitt 4: "Schalter: ..., Kamera,
 * ..."). Eine eigene DPM-API (`setCameraDisabled`/`getCameraDisabled`), kein
 * `UserManager.DISALLOW_*`-Wert — deshalb eine eigene Klasse statt einer
 * [UserRestrictionSafeguard]-Instanz.
 *
 * **Live-verifiziert und wirksam für gewöhnliche Apps (2026-08-30, Smoketest, physisches Samsung
 * SM-A156B) — eine erste Beobachtung desselben Tages war ein Fehlschluss.** `apply()`/`isActive()`
 * funktionieren nachweislich korrekt ([CameraSafeguardInstrumentedTest]). Ein erster Funktionstest
 * ausschließlich über die vorinstallierte Samsung-Kamera-App zeigte trotz aktiver Sperre eine echte
 * aktive HAL-Session (`dumpsys media.camera`, `Device status: ACTIVE`) — das legte zunächst nahe,
 * die Durchsetzung fehle komplett. Ein daraufhin gebautes, unabhängig signiertes Wegwerf-Test-APK
 * (eigenes Zertifikat, `CAMERA`-Laufzeitberechtigung, kein privilegierter Systemstatus) widerlegte
 * das: `CameraManager.openCamera()` scheiterte dort bei aktiver Sperre synchron mit
 * `CameraAccessException(CAMERA_DISABLED)` ("Camera disabled by device policy"), und dieselbe App
 * öffnete die Kamera bei deaktivierter Sperre anstandslos (`onOpened`) — eine saubere
 * Kausalitätsprobe in beide Richtungen. Die Stock-Kamera-App bleibt vermutlich wegen einer
 * OEM-seitigen Ausnahme für privilegierte Systemapps unberührt, das ist aber irrelevant für die
 * eigentliche Bedrohung (eine fremde, gewöhnliche App). `dumpsys device_policy` zeigt weiterhin zu
 * keinem Zeitpunkt einen kamerabezogenen Eintrag (weder im alten Feld-Dump noch im neuen
 * `PolicyKey`-Format) — das bleibt ein Diagnose-Blindspot, aber kein Hinweis auf einen echten
 * Durchsetzungsfehler mehr. Root-caused: die Sperre wirkt.
 */
class CameraSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setCameraDisabled(admin, true)
    }

    override fun revert() {
        devicePolicyManager().setCameraDisabled(admin, false)
    }

    override fun isActive(): Boolean = devicePolicyManager().getCameraDisabled(admin)

    companion object {
        const val ID = "camera_disabled"
    }
}
