#!/usr/bin/env bash
# Baut connexias-engine für alle vier Android-ABIs und aktualisiert die generierten Artefakte in
# app/src/main/ UND sentinel/src/main/ (Plan-Entscheidung "Sentinel: eigenständige Kiosk-PIN-App",
# Abschnitt "Crypto-Sharing: Duplizieren" — Sentinel trägt eine eigene, unabhängige Kopie derselben
# UniFFI-Bindings/jniLibs statt eines gemeinsamen Moduls, s. dortiges build.gradle.kts). Manueller/
# skriptgesteuerter Schritt — nicht in den Gradle-Build integriert (kein automatisches
# Cargo-Invoke bei jedem ./gradlew build); bei Änderungen an der Rust-API dieses Skript erneut
# laufen lassen (übernommen vom ConneXias-Framework-Quellprojekt, s. dortige rust/build-android.sh).
#
# Voraussetzungen: cargo-ndk installiert (`cargo install cargo-ndk`), Rust-Android-Targets via
# rustup (aarch64-linux-android, armv7-linux-androideabi, i686-linux-android,
# x86_64-linux-android), Android NDK über ANDROID_NDK_HOME oder rustup/cargo-ndk-Autodetektion.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

APP_MODULE="../app/src/main"
SENTINEL_MODULE="../sentinel/src/main"
JNILIBS_DIR="$APP_MODULE/jniLibs"
KOTLIN_OUT_DIR="$APP_MODULE/java"
SENTINEL_JNILIBS_DIR="$SENTINEL_MODULE/jniLibs"
SENTINEL_KOTLIN_OUT_DIR="$SENTINEL_MODULE/java"
MIN_SDK=35

echo "== 1/3: Host-Debug-Build (unstripped, nur für Bindgen-Metadaten-Introspektion) =="
cargo build -p connexias-engine

echo "== 2/3: Kotlin-Bindings generieren =="
rm -rf target/bindings-kotlin
(cd engine && cargo run --bin uniffi-bindgen --features uniffi-cli --quiet -- generate \
    --library ../target/debug/libconnexias_engine.so \
    --language kotlin \
    --out-dir ../target/bindings-kotlin \
    --no-format)
mkdir -p "$KOTLIN_OUT_DIR" "$SENTINEL_KOTLIN_OUT_DIR"
cp -r target/bindings-kotlin/uniffi "$KOTLIN_OUT_DIR/"
rm -rf "$SENTINEL_KOTLIN_OUT_DIR/uniffi"
cp -r target/bindings-kotlin/uniffi "$SENTINEL_KOTLIN_OUT_DIR/"
echo "   -> $KOTLIN_OUT_DIR/uniffi/connexias_engine/connexias_engine.kt"
echo "   -> $SENTINEL_KOTLIN_OUT_DIR/uniffi/connexias_engine/connexias_engine.kt"

echo "== 3/3: Android-Cross-Compile (release, gestrippt) für alle vier ABIs =="
mkdir -p "$JNILIBS_DIR"
cargo ndk \
    -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -P "$MIN_SDK" \
    -o "$JNILIBS_DIR" \
    build --release -p connexias-engine
echo "   -> $JNILIBS_DIR/{arm64-v8a,armeabi-v7a,x86,x86_64}/libconnexias_engine.so"

# Gezielt nur libconnexias_engine.so je ABI kopieren, nicht das ganze $JNILIBS_DIR: seit die
# Netz-Sperre reaktiviert ist, landet dort (build-android-barbican.sh) auch
# libconnexias_barbican.so — ein pauschales `cp -r`/`rm -rf` würde das WireGuard/Firewall-Modul in
# die bewusst schlanke, eigenständige Sentinel-Kiosk-APK schleppen (s. CLAUDE.md "Sentinel: eigenes,
# unabhängiges Crypto-Duplikat" — kein anderer geteilter Code, erst recht kein VPN-Modul, das
# Sentinel nie aufruft).
mkdir -p "$SENTINEL_JNILIBS_DIR"
for abi_dir in "$JNILIBS_DIR"/*/; do
    abi="$(basename "$abi_dir")"
    mkdir -p "$SENTINEL_JNILIBS_DIR/$abi"
    cp "$abi_dir/libconnexias_engine.so" "$SENTINEL_JNILIBS_DIR/$abi/"
done
echo "   -> $SENTINEL_JNILIBS_DIR/{arm64-v8a,armeabi-v7a,x86,x86_64}/libconnexias_engine.so"

echo "Fertig."
