package de.ble1st.warden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Geteilte Bausteine für die gruppierte Dashboard-Menüstruktur (s. UI-Review 2026-08-21) — löst
 * die bisherige flache Stapel-aus-[androidx.compose.material3.Button]s ab, gemeinsam genutzt vom
 * Dashboard ([WardenStatusActivity]) und den Untermenüs ([SafeguardsScreen], [SettingsScreen]).
 * Bewusst eigene, kleine Zeile statt `androidx.compose.material3.ListItem` — `ListItem` bringt
 * eigene Höhen-/Padding-Konventionen mit, die für die dichte, zweizeilige "Titel + Subtitel +
 * Chevron"-Zeile hier mehr Anpassung brauchen würden, als sie Wiederverwendung sparen.
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

/**
 * Eine Menüzeile: optionales Kürzel-Tag + Titel + optionaler Untertitel + optionales
 * Zahlen-Badge (z. B. Scanner-Funde-Anzahl) + Chevron. `badge = null` blendet das Badge komplett
 * aus statt eine "0" zu zeigen — ein Fund-Zähler von 0 ist ein Nicht-Ereignis, kein
 * anzeigewürdiger Zustand.
 *
 * `tag` (z. B. "SG", "AV") statt eines Icons — aus dem HTML-Mockup übernommen (s. dortiges
 * Klassendoc "Frei wählbar"/Terminal-Theme): das Projekt bindet bewusst nur
 * `material-icons-core` ein (kein `material-icons-extended`, s. `libs.versions.toml`-Kommentar),
 * das für die meisten hier gebrauchten Konzepte (Schild, Apps, Verlauf, Wiederherstellung) kein
 * passendes Icon enthält. Ein Text-Kürzel in einer kleinen Box braucht kein einziges zusätzliches
 * Icon und bleibt im selben Monospace-Terminal-Stil wie der Rest der App.
 *
 * **Vorlesehilfe (Vorschlag U-7, 2026-08-29):** die Zeile setzte vorher
 * `contentDescription = "$title, $subtitle"` über ein einfaches `semantics {}` — mit zwei Folgen.
 * Erstens fehlte das [badge] darin komplett: die Fund-Anzahl des Sicherheits-Scanners, also genau
 * die Information, wegen der die Zeile überhaupt Aufmerksamkeit verdient, war für TalkBack
 * unsichtbar. Zweitens *überschreibt* ein einfaches `semantics {}` die Kinderknoten nicht, sondern
 * tritt neben sie — Titel und Untertitel wurden dadurch doppelt vorgelesen.
 * `clearAndSetSemantics` ersetzt den Teilbaum stattdessen vollständig durch eine einzige,
 * vollständige Beschreibung.
 */
@Composable
fun MenuRow(title: String, onClick: () -> Unit, subtitle: String? = null, badge: String? = null, tag: String? = null) {
    val spoken = buildString {
        append(title)
        subtitle?.let { append(", ").append(it) }
        // "3 Funde" statt eines nackten "3" — die Zahl allein sagt einer Vorlesehilfe nichts.
        badge?.let { append(", ").append(it).append(" Funde") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tag?.let { TagBadge(it) }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        badge?.let { Badge { Text(it) } }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagBadge(text: String) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
