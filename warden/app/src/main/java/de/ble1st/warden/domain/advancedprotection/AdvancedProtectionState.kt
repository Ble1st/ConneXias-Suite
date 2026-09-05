package de.ble1st.warden.domain.advancedprotection

/**
 * Zustand von Androids "Erweitertem Schutz" (Android Advanced Protection Mode, AAPM — ab
 * Android 16 / API 36), gelesen über [de.ble1st.warden.integrity.AdvancedProtectionReader].
 *
 * **Warum das für Warden relevant ist (2026-09-05, Tier-1 der DPC-Recherche):** AAPM zielt
 * ausdrücklich auf dieselbe Nutzergruppe wie Warden — gefährdete Personen, Journalist*innen,
 * Aktivist*innen — und schaltet systemseitig genau die Dinge scharf, die Warden sonst einzeln
 * nachbaut: 2G aus, Sideloading blockiert, MTE an, forensisches Logging an. Warden soll das weder
 * doppeln noch ignorieren, sondern **wissen**, ob es an ist.
 *
 * **Der dritte Wert ist der wichtige.** [NICHT_VERFUEGBAR] heißt "diese Android-Version kennt AAPM
 * nicht" (alles unter API 36) und ist ausdrücklich *nicht* dasselbe wie [AUS]. Das Zielgerät läuft
 * noch Android 15; würde Warden dort "Erweiterter Schutz: aus" anzeigen, klänge das nach einer
 * abgeschalteten Funktion statt nach einer, die es auf diesem System schlicht noch nicht gibt —
 * und der Sicherheits-Score dürfte dafür erst recht nichts abziehen (dieselbe Haltung wie bei
 * [de.ble1st.warden.domain.encryption.KeystoreSecurityLevel.UNKNOWN]: Unsicherheit wird nicht
 * bestraft).
 */
enum class AdvancedProtectionState(val label: String) {
    /** AAPM ist aktiv. */
    AN("Erweiterter Schutz aktiv"),

    /** AAPM wird von diesem Android unterstützt, ist aber ausgeschaltet. */
    AUS("Erweiterter Schutz aus"),

    /** Vor Android 16 — die Plattform kennt AAPM nicht. Kein Mangel des Geräts. */
    NICHT_VERFUEGBAR("Erst ab Android 16 verfügbar"),

    /** Ab Android 16, aber der Lesevorgang schlug fehl (fehlende Berechtigung, Systemdienst nicht
     * erreichbar). Fail-Safe: nicht als "aus" ausgeben. */
    UNBEKANNT("Nicht auslesbar"),
    ;

    /** `true` nur beim tatsächlich gelesenen [AN] — die drei anderen Werte dürfen nirgends als
     * "Schutz vorhanden" durchgehen. */
    val isActive: Boolean get() = this == AN

    /** `true`, wenn die Plattform AAPM überhaupt kennt — steuert, ob die UI die Zeile als
     * handlungsrelevant oder nur als Hinweis darstellt. */
    val isSupported: Boolean get() = this == AN || this == AUS
}
