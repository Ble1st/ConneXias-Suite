package de.ble1st.files.data.share

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Was eine fremde App per `ACTION_GET_CONTENT` angefragt hat. `null` bedeutet: diese
 * Activity-Instanz läuft normal, nicht als Datei-Picker.
 *
 * [mimeTypes] ist die Liste der akzeptierten Typmuster (`image/` + `*`, `application/pdf`,
 * [MimeTypeFilter.WILDCARD] — zur getrennten Schreibweise s. dortigen Klassendoc),
 * zusammengesetzt aus `Intent.getType()` und dem optionalen `EXTRA_MIME_TYPES`-Array. Leer
 * bedeutet "keine Angabe" und wird wie [MimeTypeFilter.WILDCARD] behandelt.
 */
data class PickSpec(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
) {
    /** True, wenn der Aufrufer wirklich einschränkt — [MimeTypeFilter.WILDCARD] und "keine
     * Angabe" sind keine Einschränkung und sollen die Liste nicht anfassen. */
    val hasTypeRestriction: Boolean
        get() = mimeTypes.isNotEmpty() && mimeTypes.none { it == MimeTypeFilter.WILDCARD }
}

/**
 * Ob die aktuelle Activity-Instanz gerade als Datei-Picker für eine fremde App läuft
 * (ACTION_GET_CONTENT — analyse.md Abschnitt 5: "Files ist kein Datei-Picker für andere Apps").
 * [de.ble1st.files.MainActivity] setzt das bei onCreate/onNewIntent (Singleton statt Compose-State,
 * weil beide Callbacks außerhalb der Composition laufen — dasselbe Muster wie [IncomingShare]).
 * [de.ble1st.files.nav.FilesNavHost] schaltet [de.ble1st.files.ui.browser.FileBrowserScreen]
 * dadurch von "Betrachter öffnen" auf "Uri an den Aufrufer zurückgeben" um, sobald aktiv — der
 * bestehende Datei-Browser ist selbst schon der Picker, es braucht keine zweite UI dafür.
 *
 * **Erweitert 2026-09-03 um Typ-Filter und Mehrfachauswahl** (analyse.md Abschnitt 6.2). Vorher
 * war hier nur ein `Boolean`: die App gab jeden beliebigen Dateityp zurück, unabhängig davon,
 * wonach der Aufrufer gefragt hatte, und immer nur genau eine Datei. Eine App, die `image/` + `*`
 * anfragt und daraufhin eine `.apk` bekommt, ist kein fehlendes Komfortmerkmal, sondern ein
 * gebrochener Intent-Vertrag — der Aufrufer verlässt sich darauf und prüft in aller Regel nicht
 * nach.
 */
object PickRequest {
    private val _spec = MutableStateFlow<PickSpec?>(null)
    val spec: StateFlow<PickSpec?> = _spec

    fun setFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_GET_CONTENT) {
            _spec.value = null
            return
        }
        // EXTRA_MIME_TYPES hat Vorrang vor getType(), wenn beides gesetzt ist — so sieht es der
        // Android-Vertrag vor (getType() ist dann üblicherweise "*/*" als Sammelangabe für
        // Empfänger, die das Extra nicht auswerten).
        val extraTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty()
        val singleType = intent.type?.let { listOf(it) }.orEmpty()
        _spec.value = PickSpec(
            mimeTypes = (if (extraTypes.isNotEmpty()) extraTypes else singleType).filter { it.isNotBlank() },
            allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        )
    }
}
