package de.ble1st.warden.domain.appmanagement

/**
 * Welchen OS-Mechanismus Warden zum "Einfrieren" einer App benutzt (2026-09-05, Tier-2 der
 * DPC-Recherche — Auswahlmenü statt fest verdrahteter Reihenfolge).
 *
 * **Vorgeschichte:** [de.ble1st.warden.appmanagement.AppFreezeManager] versuchte seit 2026-08-22
 * fest "erst `setApplicationHidden`, bei Fehlschlag `setPackagesSuspended`". Das war die richtige
 * Antwort auf die dokumentierte OS-Lücke (Hide scheitert still für Apps, die einen
 * `DeviceAdminReceiver` deklarieren, und für debuggbare Apps), nahm dem Nutzer aber die
 * Entscheidung ab — und die beiden Mechanismen unterscheiden sich sichtbar:
 *
 * - **Verstecken** (`setApplicationHidden`) lässt die App spurlos aus Launcher, Übersicht und
 *   Benachrichtigungen verschwinden. Wirkt gründlich, ist aber für Mitbenutzende des Geräts
 *   verwirrend: die App ist einfach weg, ohne Erklärung.
 * - **Suspendieren** (`setPackagesSuspended`) lässt das Symbol stehen, graut es aus und zeigt
 *   beim Antippen einen Systemdialog ("App ist pausiert"). Für eine *Sicherheits*-Maßnahme oft die
 *   bessere Wahl — wer das Gerät benutzt, erfährt, dass etwas absichtlich blockiert ist, statt eine
 *   App für kaputt zu halten. Meldet außerdem als Einziger echte Fehler zurück (die Liste der
 *   nicht suspendierbaren Pakete) statt eines nackten `false`.
 *
 * [AUTOMATIK] ist der Default und entspricht exakt dem bisherigen Verhalten — ein Versionswechsel
 * darf das Verhalten bestehender Installationen nicht stillschweigend ändern.
 */
enum class FreezeMethod(val label: String) {
    /** Erst verstecken; nur falls das (still) fehlschlägt, suspendieren. Bisheriges Verhalten. */
    AUTOMATIK("Automatisch (verstecken, sonst suspendieren)"),

    /** Nur `setApplicationHidden`. Scheitert bei Geräteadministrator-Apps still — genau der Fall,
     * für den es [AUTOMATIK] gibt; als bewusste Wahl trotzdem zugelassen. */
    NUR_VERSTECKEN("Nur verstecken"),

    /** Nur `setPackagesSuspended`. Sichtbar für Mitbenutzende, mit echter Fehlerrückmeldung. */
    NUR_SUSPENDIEREN("Nur suspendieren (App bleibt sichtbar, ausgegraut)"),

    /** Beide gleichzeitig. Gründlichste Variante: greift auch dann, wenn einer der beiden
     * Mechanismen später vom System oder einem anderen Admin zurückgenommen wird. */
    BEIDES("Beides gleichzeitig"),
    ;

    val usesHide: Boolean get() = this != NUR_SUSPENDIEREN
    val usesSuspend: Boolean get() = this != NUR_VERSTECKEN

    /** Nur bei [AUTOMATIK] ist Suspendieren ein *Fallback*; bei [BEIDES] wird es unbedingt
     * angewandt, auch wenn Verstecken bereits erfolgreich war. */
    val suspendOnlyAsFallback: Boolean get() = this == AUTOMATIK

    companion object {
        val DEFAULT: FreezeMethod = AUTOMATIK
    }
}
