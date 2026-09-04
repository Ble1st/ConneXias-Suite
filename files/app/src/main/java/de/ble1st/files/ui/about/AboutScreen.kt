package de.ble1st.files.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.files.R

/**
 * Über-Bildschirm — in allen vier Suite-Apps nach demselben Muster gebaut (Warden hatte als
 * einzige bereits einen Lizenz-Bildschirm; die drei Compose-Apps hatten bis 2026-09-03 gar keinen
 * Ort, an dem Version, Lizenz oder Fremdbibliotheken auffindbar gewesen wären).
 *
 * Die Versionsnummer kommt aus `PackageManager` statt aus `BuildConfig`: `buildConfig` ist in
 * dieser App nicht aktiviert, und der Wert aus dem Paketmanager ist ohnehin der verlässlichere —
 * er beschreibt die tatsächlich installierte APK, nicht den Stand, gegen den kompiliert wurde.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    // Name **und** Code: bei einem Sideload-Vertrieb ohne Store ist der Versionscode die
    // eindeutige Angabe, mit der sich ein Fehlerbericht einer konkreten APK zuordnen lässt —
    // zwei Builds können denselben Namen tragen, der Code ist je Release eindeutig.
    val versionLabel = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // PackageInfoCompat statt info.longVersionCode: das gibt es erst ab API 28,
            // minSdk dieser App ist 26.
            val code = PackageInfoCompat.getLongVersionCode(info)
            info.versionName?.let { "$it ($code)" }
        }.getOrNull().orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, versionLabel)) },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                text = stringResource(R.string.about_suite),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            )
            HorizontalDivider()
            // Der Update-Weg bei Sideload-Vertrieb. Die App prüft **nicht** selbst auf neue
            // Versionen: das bräuchte Netzwerkzugriff plus einen regelmäßigen Aufruf an GitHub,
            // also genau die Art stiller Verbindung nach außen, die diese Suite nicht haben soll
            // (die Kamera hat aus demselben Grund gar keine INTERNET-Berechtigung). Stattdessen
            // ein Verweis, den der Nutzer selbst auslöst und der im Browser landet — die
            // Releases-Seite trägt APK, SHA256-Summen und den Zertifikat-Fingerabdruck.
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_updates_title)) },
                supportingContent = { Text(stringResource(R.string.about_updates_body)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openReleasesPage(context) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_license_title)) },
                supportingContent = { Text(stringResource(R.string.about_license_body)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_licenses_third_party)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses),
            )
        }
    }
}

/** Zeigt `res/raw/third_party_licenses.txt` als reinen, scrollbaren Text — dieselbe Textbasis wie
 * `THIRD_PARTY_LICENSES.md` im App-Ordner, hier als Textasset statt Markdown, damit kein
 * Markdown-Renderer für einen einzigen Bildschirm nötig wird (dasselbe Muster wie Wardens
 * `LicensesScreen`).
 *
 * Beide Dateien werden bewusst parallel gepflegt: Apache-2.0 §4(d) verlangt, dass die Attribution
 * für den *Empfänger der Binary* erreichbar ist — eine Datei im Repository allein genügt dafür
 * nicht, sobald eine APK per Sideload weitergegeben wird.
 *
 * Synchrones Laden beim ersten Komponieren: eine kleine Textdatei aus den eigenen Ressourcen, kein
 * Netzwerk und keine Datenbank. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    // LocalResources statt LocalContext.current.resources: Letzteres ist nicht
    // konfigurationsbewusst und liefert nach einem Sprach-/Themenwechsel unter Umständen den
    // alten Stand (Lint: LocalContextResourcesRead).
    val resources = LocalResources.current
    val licenseText = remember(resources) {
        resources.openRawResource(R.raw.third_party_licenses)
            .bufferedReader()
            .use { it.readText() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_licenses_third_party)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Text(
            text = licenseText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

/** Öffnet die Releases-Seite im Browser. Kein eigener Netzwerkzugriff: der Intent geht an die
 * Standard-Browser-App, diese App sieht die Antwort nie. Ohne installierten Browser (auf einem
 * gehärteten Gerät durchaus möglich) bleibt es bei einem Hinweis statt einem Absturz. */
private fun openReleasesPage(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_updates_no_browser, Toast.LENGTH_LONG).show()
    }
}

private const val RELEASES_URL = "https://github.com/Ble1st/ConneXias-Suite/releases"
