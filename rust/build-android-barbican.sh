#!/usr/bin/env bash
# Baut connexias-barbican ("Netz-Sperre", 2026-08-27) für alle vier Android-ABIs und aktualisiert
# die generierten Artefakte in app/src/main/ — anders als build-android.sh (connexias-engine) OHNE
# Sentinel-Duplikat: Sentinel hat keinerlei Netzwerk-Rolle (s. rust/Cargo.toml-Kommentar). Manueller/
# skriptgesteuerter Schritt — nicht in den Gradle-Build integriert; bei Änderungen an der
# Barbican-Rust-API dieses Skript erneut laufen lassen.
#
# Voraussetzungen: identisch zu build-android.sh — cargo-ndk installiert, Rust-Android-Targets via
# rustup, Android NDK über ANDROID_NDK_HOME oder rustup/cargo-ndk-Autodetektion.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

APP_MODULE="../app/src/main"
JNILIBS_DIR="$APP_MODULE/jniLibs"
KOTLIN_OUT_DIR="$APP_MODULE/java"
MIN_SDK=35

echo "== 1/3: Host-Debug-Build (unstripped, nur für Bindgen-Metadaten-Introspektion) =="
cargo build -p connexias-barbican

echo "== 2/3: Kotlin-Bindings generieren =="
rm -rf target/bindings-kotlin-barbican
(cd barbican && cargo run --bin uniffi-bindgen --features uniffi-cli --quiet -- generate \
    --library ../target/debug/libconnexias_barbican.so \
    --language kotlin \
    --out-dir ../target/bindings-kotlin-barbican \
    --no-format)
mkdir -p "$KOTLIN_OUT_DIR"
rm -rf "$KOTLIN_OUT_DIR/uniffi/connexias_barbican"
cp -r target/bindings-kotlin-barbican/uniffi "$KOTLIN_OUT_DIR/"
echo "   -> $KOTLIN_OUT_DIR/uniffi/connexias_barbican/connexias_barbican.kt"

echo "== 3/3: Android-Cross-Compile (release, gestrippt) für alle vier ABIs =="
mkdir -p "$JNILIBS_DIR"
cargo ndk \
    -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -P "$MIN_SDK" \
    -o "$JNILIBS_DIR" \
    build --release -p connexias-barbican
echo "   -> $JNILIBS_DIR/{arm64-v8a,armeabi-v7a,x86,x86_64}/libconnexias_barbican.so"

echo "Fertig."
