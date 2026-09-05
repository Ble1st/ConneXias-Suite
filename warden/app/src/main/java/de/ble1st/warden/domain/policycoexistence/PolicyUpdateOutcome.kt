package de.ble1st.warden.domain.policycoexistence

/**
 * Wie eine von Warden gesetzte Geräterichtlinie beim System tatsächlich gelandet ist — die
 * framework-freie Übersetzung von `android.app.admin.PolicyUpdateResult` (Tier 3 der DPC-Recherche,
 * 2026-09-05).
 *
 * **Warum dieses Enum keine Zahlenwerte trägt:** die `RESULT_*`-Konstanten sind Android-Konstanten,
 * und die `domain`-Schicht importiert nichts aus Android. Die Zuordnung Zahl → Enum passiert
 * deshalb genau einmal, an der Systemgrenze in
 * [de.ble1st.warden.admin.WardenPolicyUpdateReceiver] — hier steht nur, *was* die Fälle bedeuten
 * und welcher davon ein Problem ist. Dieselbe Decision/Executor-Trennung wie überall sonst.
 *
 * **Warum es das überhaupt braucht.** Seit Android 14 kann mehr als ein Admin dieselbe Richtlinie
 * setzen ("policy coexistence"); das System löst den Konflikt selbst auf und teilt dem
 * unterlegenen Admin das Ergebnis über einen Broadcast mit. Warden hat diesen Broadcast bisher
 * nicht empfangen — ein `Safeguard.isActive()` fragt zwar immer den echten DPM-Zustand ab, aber
 * genau der ist im Konfliktfall der des *anderen* Admins, und Warden konnte "greift nicht" nicht
 * von "wurde überstimmt" unterscheiden. Auf dem physischen Testgerät ist das kein Randfall: dort
 * läuft `com.samsung.android.kgclient` als zweiter aktiver Admin neben Warden.
 *
 * **[KONFLIKT_ANDERER_ADMIN] ist der einzige Fall, der von einem anderen Admin handelt** — die
 * übrigen drei Problemfälle sind Grenzen des Geräts bzw. des Systems und stehen hier mit, weil sie
 * über denselben Broadcast kommen und für den Nutzer dieselbe Frage beantworten ("warum ist der
 * Schalter an, aber nichts passiert?").
 */
enum class PolicyUpdateOutcome(val label: String, val isProblem: Boolean) {
    GESETZT("gesetzt", isProblem = false),
    ZURUECKGENOMMEN("zurückgenommen", isProblem = false),
    KONFLIKT_ANDERER_ADMIN("von einem anderen Geräteadmin überstimmt", isProblem = true),
    HARDWARE_GRENZE("von der Hardware nicht unterstützt", isProblem = true),
    SPEICHERGRENZE("Richtlinienspeicher des Systems voll", isProblem = true),
    UNBEKANNT("aus unbekanntem Grund fehlgeschlagen", isProblem = true),
}
