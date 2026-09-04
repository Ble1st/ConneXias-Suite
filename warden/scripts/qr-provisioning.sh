#!/usr/bin/env bash
# analyse.md 7-16: erzeugt die JSON-Nutzlast für Android Enterprise QR-Code-Provisionierung
# (Device-Owner-Ersteinrichtung ohne ADB — Nutzer tippt 6× auf denselben Punkt des
# Willkommensbildschirms eines frischen/werksgesetzten Geräts, Android 7+; ab Android 9 öffnet
# das den eingebauten QR-Leser automatisch, kein Zusatz-App nötig). Der gescannte QR-Code trägt
# genau dieses JSON, base64/base64url-frei als reiner Text — die meisten QR-Generatoren (z. B.
# `qrencode`) nehmen beliebigen UTF-8-Text als Eingabe.
#
# WICHTIG — zwei verschiedene "Checksummen", nicht verwechseln:
#   - PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM (hier verwendet): SHA-256 über das
#     Signaturzertifikat der APK, Base64url-kodiert OHNE Padding. Das ist derselbe Fingerabdruck,
#     den auch docs/RELEASE-SIGNING.md und release.yml ausgeben (`SHA256:`-Zeile von
#     apksigner/keytool) — nur anders kodiert (Base64url statt Hex mit Doppelpunkten).
#   - Das veraltete PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM (SHA-1 über die gesamte APK-Datei)
#     wird hier bewusst NICHT erzeugt — auf modernen Android-Versionen nicht mehr verlässlich
#     unterstützt, und die Signature-Variante ist ohnehin die empfohlene.
#
# Voraussetzung für den echten Härtefall (analyse.md 7-14): die hier referenzierte APK muss mit
# dem in docs/RELEASE-SIGNING.md beschriebenen Produktionsschlüssel signiert sein, nicht mit einem
# Wegwerf-Testschlüssel — der Fingerabdruck landet sonst auf jedem damit provisionierten Gerät und
# lässt sich (analyse.md 7-14-Fund) nur per Werksreset wieder loswerden.
set -euo pipefail

usage() {
    cat <<'USAGE'
Verwendung: qr-provisioning.sh --apk <pfad-zur-release-apk> --download-url <url> [--out <datei.json>]

  --apk           Pfad zur signierten Warden-Release-APK (Warden UND Sentinel müssen laut
                   docs/RELEASE-SIGNING.md ohnehin denselben Schlüssel tragen; hier zählt nur der
                   Fingerabdruck, die heruntergeladene Datei selbst ist immer die Warden-APK).
  --download-url  Wo das Gerät die APK während der Provisionierung herunterlädt (https, muss vom
                   Gerät ohne bestehende Anmeldung erreichbar sein — z. B. eine GitHub-Release-
                   Asset-URL). http lässt sich nur mit zusätzlichem
                   PROVISIONING_ALLOW_OFFLINE-artigem Risiko nutzen, hier nicht unterstützt.
  --out           Datei, in die das JSON geschrieben wird (Default: stdout).

Beispiel:
  ./qr-provisioning.sh \
    --apk ../app/build/outputs/apk/release/Warden-release-1.0.apk \
    --download-url https://github.com/Ble1st/ConneXias-Suite/releases/download/warden-v1.0.0/Warden-release-1.0.0.apk \
    --out warden-provisioning.json

  # QR-Code daraus erzeugen (qrencode, sudo apt install qrencode / brew install qrencode):
  qrencode -o warden-provisioning.png < warden-provisioning.json

  # Danach: Gerät werksseitig zurücksetzen (oder ein frisches Gerät nehmen), auf dem
  # Willkommensbildschirm 6× auf denselben Punkt tippen, den QR-Code scannen.
USAGE
}

APK=""
DOWNLOAD_URL=""
OUT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --download-url) DOWNLOAD_URL="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unbekannte Option: $1" >&2; usage; exit 1 ;;
    esac
done

if [[ -z "$APK" || -z "$DOWNLOAD_URL" ]]; then
    usage >&2
    exit 1
fi
[[ -f "$APK" ]] || { echo "APK nicht gefunden: $APK" >&2; exit 1; }
case "$DOWNLOAD_URL" in
    https://*) ;;
    *) echo "Warnung: --download-url ist kein https-Link ($DOWNLOAD_URL) — die meisten Geräte lehnen den Download während der Provisionierung dann ab." >&2 ;;
esac

# apksigner bevorzugt (versteht v2/v3-Signaturschemata korrekt), keytool nur als Fallback für
# reine v1(JAR)-Signaturen — dieselbe Reihenfolge, mit der dieses Repo APKs schon in der 7-14-
# Verifikation geprüft hat.
find_apksigner() {
    local candidate
    for candidate in "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner \
                      "${ANDROID_SDK_ROOT:-/nonexistent}"/build-tools/*/apksigner; do
        [[ -x "$candidate" ]] && { echo "$candidate"; return 0; }
    done
    command -v apksigner 2>/dev/null && return 0
    return 1
}

CERT_SHA256_HEX=""
if APKSIGNER=$(find_apksigner); then
    CERT_SHA256_HEX=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
        | grep -m1 -i "certificate SHA-256 digest" | sed 's/.*digest: *//')
fi
if [[ -z "$CERT_SHA256_HEX" ]]; then
    CERT_SHA256_HEX=$(keytool -printcert -jarfile "$APK" 2>/dev/null \
        | grep -m1 "SHA256:" | sed 's/.*SHA256: *//')
fi
if [[ -z "$CERT_SHA256_HEX" ]]; then
    echo "Konnte SHA-256-Zertifikat-Fingerabdruck nicht ermitteln (weder apksigner noch keytool verfügbar/erfolgreich)." >&2
    exit 1
fi


# Hex -> Bytes -> Base64url ohne Padding, per python3 statt xxd/openssl: xxd ist nicht auf jedem
# Entwicklungsrechner vorinstalliert (z. B. minimale Distros ohne vim-common), python3 praktisch
# überall vorhanden und in diesem Repo ohnehin schon Werkzeug (uniffi-bindgen-Tooling).
CHECKSUM=$(python3 -c "
import base64, sys
hex_digest = sys.argv[1].replace(':', '').strip()
print(base64.urlsafe_b64encode(bytes.fromhex(hex_digest)).decode().rstrip('='))
" "$CERT_SHA256_HEX")

COMPONENT="de.ble1st.warden/de.ble1st.warden.admin.WardenDeviceAdminReceiver"

JSON=$(cat <<JSONEOF
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "$COMPONENT",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "$DOWNLOAD_URL",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "$CHECKSUM",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
JSONEOF
)

if [[ -n "$OUT" ]]; then
    echo "$JSON" > "$OUT"
    echo "Geschrieben nach $OUT" >&2
else
    echo "$JSON"
fi

echo "" >&2
echo "Zertifikat-SHA-256 (Hex, zur Gegenprobe gegen docs/RELEASE-SIGNING.md/die Release-Notizen): $CERT_SHA256_HEX" >&2
