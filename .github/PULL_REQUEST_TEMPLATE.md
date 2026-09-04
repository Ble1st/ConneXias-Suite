## Was und warum

<!-- Kurz: was ändert sich, und aus welchem Grund. Bei einem Befund aus analyse.md dessen
     Nummer nennen (z. B. "2-14"). -->

## Betroffene App(s)

<!-- Warden / Files / Kamera / Galerie. Jede App ist ein eigenes Gradle-Root-Projekt — ein PR,
     der drei davon anfasst, sollte begründen, warum die Änderungen zusammengehören. -->

## Geprüft

- [ ] `./gradlew lint` im betroffenen App-Ordner ist grün
- [ ] `./gradlew testDebugUnitTest` ist grün
- [ ] Bei Änderungen an Intent-Verträgen, Routen, Prefs oder Compose-Dialogen: ein
      `androidTest` deckt die Änderung ab (`./gradlew connectedDebugAndroidTest`)
- [ ] Bei Warden zusätzlich: `cargo fmt --all --check`, `cargo clippy`, `cargo test` in
      `warden/rust`
- [ ] Neue nutzersichtbare Texte liegen in `res/values/strings.xml`, nicht als Literal im Code
- [ ] Der Changelog-Abschnitt der betroffenen README ist ergänzt (und, falls es ein
      Audit-Befund ist, `analyse.md`)
