package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R

/**
 * Gemeinsamer Icon+Headline+Text-Baustein für Fehler-/Leerzustände ("weitere App-UI-
 * Verschönerungen", 2026-08-22, Punkt 6 "bessere Fehler-/Leerzustände") — löst die bisher rein
 * textuellen Fail-Safe-Meldungen ab (z. B. `AppManagementScreen`/`SecurityScannerScreen`s
 * `loadFailed`-Zeilen, `SafeguardsScreen`s `state.locked == null`-Zeile). Gleiche Fail-Safe-
 * Bedeutung wie zuvor (ein `T?`-Lesefehler darf nie wie "alles in Ordnung"/"leer" aussehen), nur
 * mit einem Icon als zusätzlichem, auf einen Blick erfassbarem Signal statt reinem Fließtext.
 *
 * Nur `Icons.Filled.Warning`/`CheckCircle` — beide Teil von `material-icons-core` (kein
 * `ErrorOutline`, das läge in `material-icons-extended`, das dieses Projekt bewusst nicht einbindet,
 * s. [MenuComponents.kt]-Kommentar).
 */
@Composable
fun ErrorStateRow(
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    /** Vorschlag V-6 (2026-08-29): optionaler zweiter Versuch. Ein fehlgeschlagener Ladevorgang
     * war bisher eine Sackgasse — der einzige Weg zu einem neuen Versuch war, den Bildschirm zu
     * verlassen und wieder zu betreten. Gerade die häufigste Ursache (DPM-Aufruf schlägt fehl,
     * weil das System kurz nach dem Boot noch nicht so weit ist) ist die, die beim zweiten Versuch
     * schlicht funktioniert. `null` lässt die Schaltfläche weg — für Fehlerzustände ohne
     * wiederholbaren Ladevorgang. */
    onRetry: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
        )
        Column {
            Text(text = headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            onRetry?.let {
                TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.status_state_row_retry_action))
                }
            }
        }
    }
}

/** Für einen echten (nicht fehlerhaften) Leerzustand — z. B. "keine Funde", "keine Root-
 * Indikatoren". Neutrales Icon/Farbe statt Fehlerrot, damit "leer, aber in Ordnung" nicht wie ein
 * Fehler aussieht. */
@Composable
fun EmptyStateRow(headline: String, detail: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
        )
        Column {
            Text(text = headline, style = MaterialTheme.typography.titleSmall)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
