# Security Policy

Gilt für alle vier Apps der ConneXias Suite (Warden, ConneXias Files, ConneXias Kamera,
ConneXias Galerie). Die Datei lag bis 2026-09-04 unter `warden/SECURITY.md` und galt damit
formal nur für eine der vier Apps — GitHub liest sie ohnehin nur im Repo-Root, in `.github/`
oder in `docs/`, der alte Ort war also für die Weboberfläche unsichtbar.

## Reporting a vulnerability

Please do not open a public issue for security-relevant bugs.

Use GitHub's private vulnerability reporting instead: open the "Security" tab on this
repository and select "Report a vulnerability". This creates a private advisory visible
only to the maintainer until it is resolved.

Include, where applicable:

- affected component/file and, if known, commit or version
- steps to reproduce, or a minimal proof of concept
- impact (what an attacker gains, and under what preconditions — e.g. physical access,
  existing Device Owner status, unlocked vs. locked device)

## Scope

In scope: all four apps in this repository (`warden/`, `files/`, `camera/`, `gallery/`),
including Warden's bundled crypto engine (`warden/rust/engine`) and the Sentinel companion APK
(`warden/sentinel`), and the cross-app intent contracts between them (see the suite overview in
`README.md`).

Out of scope: issues that require the device to already be compromised at a level that
supersedes Device Owner control (e.g. a rooted/unlocked bootloader with an attacker-controlled
OS image), and social-engineering scenarios not tied to a specific code defect. Also out of
scope are the limitations that are already documented as such in `analyse.md` section 6 —
they are known and deliberate, not undiscovered defects. Two that get reported most often:
cleartext WebDAV is permitted on purpose (with a visible warning), and the local WLAN folder
share in ConneXias Files is HTTP without TLS by design.

## Response

This is a single-maintainer project without a fixed SLA. Reports are read and triaged as
time allows; there is no bug bounty.

## Verifying a release

Every release page carries a `SHA256SUMS.txt` and the SHA-256 fingerprint of the signing
certificate — the same certificate for all four apps. Verify both before installing an APK that
did not come from this repository's releases page:

```
sha256sum -c SHA256SUMS.txt
keytool -printcert -jarfile <app>.apk
```

An APK signed with a different certificate cannot update an installed one; Android refuses it.
A "fixed" build offered outside these releases and signed with another key is therefore never
an update — it is a different app.
