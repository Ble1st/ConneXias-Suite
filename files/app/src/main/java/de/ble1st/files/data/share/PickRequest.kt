package de.ble1st.files.data.share

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ob die aktuelle Activity-Instanz gerade als Datei-Picker für eine fremde App läuft
 * (ACTION_GET_CONTENT — analyse.md Abschnitt 5: "Files ist kein Datei-Picker für andere Apps").
 * [de.ble1st.files.MainActivity] setzt das bei onCreate/onNewIntent (Singleton statt Compose-State,
 * weil beide Callbacks außerhalb der Composition laufen — dasselbe Muster wie [IncomingShare]).
 * [de.ble1st.files.nav.FilesNavHost] schaltet [de.ble1st.files.ui.browser.FileBrowserScreen]
 * dadurch von "Betrachter öffnen" auf "Uri an den Aufrufer zurückgeben" um, sobald aktiv — der
 * bestehende Datei-Browser ist selbst schon der Picker, es braucht keine zweite UI dafür.
 */
object PickRequest {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun setFromIntent(intent: Intent?) {
        _active.value = intent?.action == Intent.ACTION_GET_CONTENT
    }
}
