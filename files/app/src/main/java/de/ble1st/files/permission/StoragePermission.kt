package de.ble1st.files.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * "Alle Dateioperationen unterstützen" (Nutzeranforderung) braucht Zugriff auf den kompletten
 * lokalen Speicher, nicht nur auf app-eigene Scoped-Storage-Verzeichnisse. Der passende
 * Berechtigungsmechanismus unterscheidet sich je API-Level:
 *
 * - API 30+ (Android 11+): MANAGE_EXTERNAL_STORAGE, eine "Sonderberechtigung", die nur über einen
 *   eigenen Settings-Bildschirm gewährt werden kann (kein normaler Runtime-Permission-Dialog).
 * - API 26–29: vor Scoped Storage genügt die klassische Laufzeit-Berechtigung
 *   WRITE_EXTERNAL_STORAGE für vollen java.io.File-Zugriff auf den primären externen Speicher.
 * - Genau API 29 (Android 10) ist eine dokumentierte Lücke: Scoped Storage greift dort bereits
 *   (targetSdk der App liegt weit über 29, `requestLegacyExternalStorage` wirkt nur bei
 *   targetSdk ≤ 29), MANAGE_EXTERNAL_STORAGE existiert dort aber noch nicht. Auf genau dieser
 *   einen, seit Jahren nicht mehr aktuell gehaltenen Android-Version bleibt der Zugriff auf
 *   app-eigene Verzeichnisse beschränkt — ein für v1 bewusst in Kauf genommener Rand-Fall statt
 *   zusätzlich einen kompletten zweiten SAF-Baum-Zugriffspfad nur dafür zu bauen.
 */
object StoragePermission {

    fun hasFullAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Intent für den API-30+-Pfad — direkt auf die Freigabeseite dieser App statt der generischen
     * Liste aller Apps mit Sonderberechtigung, damit der Nutzer nicht selbst danach suchen muss. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun manageAllFilesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

    const val legacyPermission: String = Manifest.permission.WRITE_EXTERNAL_STORAGE
}
