# Component1 fixed-pair guest first-install entry

Scope: standalone Windows PowerShell5.1 orchestration for the existing fixed synthetic1102/1103 pair. No Kotlin/runtime, MSI bytes, production data, credentials, installer transform or directory guard changed. This checkpoint adds no new native installation PASS.

The user supplied successful DL1 clone output: independent copy inactive/Saved, original failure VM Off, old checkpoint list and disk length/mtime unchanged, export retained. Source disk content hash was not measured. Exact VM IDs and private paths are retained only in the ignored local receipt. This is user-provided evidence, not Codex direct observation. Saved state was not removed.

## Actual execution

All three rounds ran the real Windows PowerShell5.1 executable on DD1:

```powershell
& 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File tools/desktop/windows/Test-Comp1FreshGuest.ps1 -Evidence $freshEvidence
```

`$freshEvidence` was a new `guest-helper-tests-01`, `-02` or `-03` directory under the existing ignored component-contract fixture root. Each invocation redirected stdout/stderr, captured actual `$LASTEXITCODE`, and copied both test/source files before execution. Raw files and hashes are attached without normalization.

| Execution | Tests | PASS | FAIL | Exit | Actual reason |
|---|---:|---:|---:|---:|---|
|01|16|14|2|1|Tiny-JAR tests could not resolve ZipArchiveMode without explicit Compression assembly loading.|
|02|16|14|2|1|Actual PS5.1 JAR reader selected the wrong String.Split overload.|
|03|16|16|0|0|Explicit assemblies and PowerShell `-split` fixed both failures.|

All rounds have zero skipped/errors in the emitted JUnit; caught assertion/runtime failures are represented as failures. Do not sum the rounds as48 accepted tests. Installer, VM and registration boundaries in this suite are fake. The two tiny-JAR cases use the actual identity reader with independently constructed resources.

Additional actual DD1 observations, outside the16 tests:

- A read-only integration attempt refused a missing staged MSI in the handoff folder; it did not install. Raw refusal is retained. The original transfer ZIP stores both MSIs at its root; DD1 source MSIs remain in their separate A/B build directories. No duplicate333MB staging directory was created just for this check. Full pair verification already ran on DL1, per the user's earlier output; it will run again at the guest entry.
- Actual C# P/Invoke registration reader compiled/executed on DD1: no related synthetic products and both fixed product/feature states -1. This is current-context observation, not global/all-user absence or DL1 guest proof.
- Calling the actual entry on DD1 with its confirmation switch refused `INTERACTIVE_HYPERV_GUEST_REQUIRED`, exit1, before installer invocation. No persistent execution-policy changes occurred.

## Contract and limitations

The entry verifies four fixed hashes (MSIs, metadata identity, existing verifier), then invokes that exact static verifier. It requires user confirmation of the new isolated guest and rejects known host names, non-Hyper-V machine model, service identities, existing synthetic product/feature state, old output and insufficient target space. It installs1102 once, leaves verbose log/result/file hashes locally, and requires complete native registration and the single packaged synthetic1102 identity before reporting observed success.

The VM model and guest UUID snapshot cannot independently establish the selected host-side VM ID. The user must open the new independent copy. No requirement or claim of all-user consumer closure is introduced. Read-only native API behavior on DD1 is not proof of live guest Installer/custom-action behavior.

No CCB launch,1103 upgrade, repair, uninstall, reboots, failure retries, saved-state removal or cleanup occur. Timeout preserves the process/scene and instructs against retry. A nonzero result preserves logs/partial program inventory; no rollback success is inferred. Old1307/Return5 remains unresolved pending actual guest evidence. Existing ZIP and MSI hashes remain fixed; the helper is delivered separately.

Static skill review: release Skill and Windows README updated because this is a new reusable entry. User/private VM clone receipts, raw archives, credentials and user data were excluded from this evidence. `RAW_SHA256.json` and `SOURCE_IDENTITY.json` identify this checkpoint; final Git commit is reported by the delivery receipt, not self-referentially embedded here.
