# Isolated signing laboratory

This is an engineering experiment, not a user APK or an update for the first
delivered LAN preview. That first APK has signer `244957b1...`; this experiment
uses a separate lab-only identity and cannot provide a same-app-ID update for it.
No device installation, uninstall, data clearing, real-root change, public
release or delivery action is included.

`apk_v2_local.py` reads a frozen APK and prepares a **public detached v2 block**.
It writes no APK. The RSA-2048 key is generated locally and persisted only in
Windows user-scope DPAPI protection, outside Git. Separate local processes
reopen that identity and must produce identical deterministic public receipts.
No private key or protected-key file is included in this repository, requests,
workflow environment, logs, caches or artifacts. This lab identity is not a
production signing identity or a key backup/recovery policy.

The independent cloud job verifies exact original artifact provenance and APK
hash, assembles the new signing block, checks the expected resulting APK hash,
then uses Android SDK `apksigner`, `aapt` and `zipalign`. It also flips one
protected byte and requires official verification to fail. The evidence
artifact contains reports only. It does not contain the assembled lab APK.

The limited ZIP32/v2 format follows the [AOSP v2 specification](https://source.android.com/docs/security/features/apksigning/v2).
JAR-signed inputs, ZIP64 and old signing blocks other than v2/padding refuse.
This does not prove physical device upgrades, original-key recovery or a
data-preserving transition from the already delivered preview. Those remain
separate work. A later real candidate must also pin its actual source, manifest,
signer and version, with independent verification of that exact candidate.
