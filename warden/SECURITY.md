# Security Policy

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

In scope: the app itself (`app/`) and the bundled crypto engine (`rust/engine`).

Out of scope: issues that require the device to already be compromised at a level that
supersedes Device Owner control (e.g. a rooted/unlocked bootloader with an attacker-controlled
OS image), and social-engineering scenarios not tied to a specific code defect.

## Response

This is a single-maintainer project without a fixed SLA. Reports are read and triaged as
time allows; there is no bug bounty.
