package de.ble1st.warden.integrity

/** Ein einzelner Root-/Custom-ROM-Hinweis, s. [RootIndicatorScanner]-Klassendoc. Kein
 * Nachweis, nur ein Indiz — jedes einzelne Kriterium ist für sich genommen umgehbar
 * (versteckte/umbenannte su-Binaries, Magisk Hide/Zygisk, ein neu signierter Build mit
 * `release-keys` trotz Root), zusammen aber ein brauchbares Warnsignal für das
 * Bedrohungsmodell "wurde dieses Gerät nach der Provisionierung manipuliert". */
enum class RootIndicatorSignal {
    SU_BINARY_FOUND,
    MAGISK_PACKAGE_FOUND,
    TEST_KEYS_BUILD,
}
