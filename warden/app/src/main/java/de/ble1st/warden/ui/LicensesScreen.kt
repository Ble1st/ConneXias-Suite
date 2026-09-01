package de.ble1st.warden.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ble1st.warden.R

/**
 * Zeigt `res/raw/third_party_licenses.txt` an — dieselbe Textbasis wie `THIRD_PARTY_LICENSES.md`
 * im Repo-Root, hier als reines Textasset statt Markdown, damit sie in einem einfachen
 * scrollbaren [Text] lesbar bleibt (keine Markdown-Rendering-Abhängigkeit für einen einzigen
 * Bildschirm). Beide Dateien werden bewusst parallel gepflegt: das Repo-File ist die
 * maschinenlesbare Quelle (generiert aus `cargo metadata`/`libs.versions.toml`, s. dortiges
 * Klassendoc), dieses Asset die für Endnutzer erreichbare Kopie — Apache-2.0 §4(d)/BSD-3-Clause
 * verlangen Attribution *erreichbar für den Empfänger der Binary*, ein Repo-File allein reicht
 * dafür nicht, sobald eine APK weitergegeben wird.
 *
 * Laden erfolgt synchron beim ersten Composition (kleine Textdatei, keine Netzwerk-/DB-I/O) —
 * kein `LaunchedEffect`/Koroutine nötig, im Unterschied zu den DPM-/PackageManager-Ladepfaden
 * anderswo in der App (s. Architektur-Review F-2), die echte Blocking-I/O sind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember {
        context.resources.openRawResource(R.raw.third_party_licenses)
            .bufferedReader()
            .use { it.readText() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
            )
        },
    ) { padding ->
        Text(
            text = licenseText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
