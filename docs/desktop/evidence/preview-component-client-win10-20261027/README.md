# Win10 first-install component-client refusal: bounded correction

Source parent: `a6adfb225505d2b69d6184c51a997abb34b4af53`. No Kotlin/runtime, Prompt, credentials, data-root, MSI payload, Component GUID/KeyPath or directory-ownership contract changes. Fixed1102/1103 files remain unchanged and are not rerun as installs.

## Root cause and evidence attribution

The user supplied one distinct DL1/Win10 build19045 diagnostic output, pasted repeatedly with the same timestamp. Count it once, not as three executed tests. The independent metadata-only MSI reached `CcbResolveInstallDirectory`: its first client's index0 returned **2**, followed by component rejection and native1603. Product/feature states remained -1, related products empty and no program directory created. The first component's row layout had passed; the remaining217 rows had not yet been traversed. This is before directory resolution, old-product removal or rollback.

The old `component_clients()` accepted259 (`ERROR_NO_MORE_ITEMS`) but treated every other nonzero result as an opaque failure. That is the exact source branch responsible for this observed rejection. Win32 code2 is `ERROR_FILE_NOT_FOUND`; the evidence does **not** establish which internal Windows Installer registry lookup returned it, nor does2 alone prove that a component has no clients. The documented MsiEnumClientsEx return table does not promise2 as a successful empty result. This patch therefore does not normalize it unconditionally.

DD1 is also Windows10 build19045. The difference is not demonstrated to be an OS-major-version difference. DD1 has27034 native component instances in the observed scope; its primary calls on these synthetic targets returned259, whereas the clean guest returned2. Do not infer global registry health, all-user absence or a specific missing internal registry key from those facts. AppSearch1402/Win32-2 for the version registration key is separately compatible with first installation; it is not access-denied or old1307 evidence.

## Minimum behavior change

For an index0 client query returning2 in a **non-maintenance** install, require an independent, complete `MsiEnumComponentsExW` enumeration of the same current-user/machine scope. Only if the exact target component is absent from that completed inventory may this individual component be treated as having no registered clients. A single invocation-local inventory is reused and freed on all outcomes; no persistent state or all-user enumeration is introduced. It is bounded at65536 entries. Errors (including2), malformed GUID/context, incomplete enumeration, allocation failure, bound exhaustion or a registered target continue to refuse.

| Situation | Candidate admission |
|---|---|
|Fresh install, client259|Existing behavior unchanged|
|Fresh install, client2 at index0|Independent complete absence required|
|Maintenance (`Installed` nonempty), client2|Refuse, even if an empty inventory is injected|
|2 after an already enumerated client|Refuse|
|Upgrade shared component|Still requires the exact selected old product in user-unmanaged context|
|Upgrade newly introduced component reporting2|Independent absence required; old-package/directory ownership checks still run afterwards|
|Foreign/machine/managed client, unknown identity, other query failure|Refuse|

Production refusals now add fixed stage/native-status/ordinal diagnostics, preserving the legacy summary code for compatibility. Successful use of the extra proof emits `CCB_COMPONENT_ABSENCE_VERIFIED raw_status=2 count=...`. No SID, path, account, secret or unrelated component IDs are logged by this addition. These are observations at the existing admission/revalidation points, not a new cross-process CAS or global-consumer guarantee.

## Actual validation, not cumulative historical totals

- User-provided original RED: DL1 diagnostic native1603, CLIENT_ENUM2/index0. It is not a Codex-run JUnit result.
- New source compiled production/test DLLs with MSVC `/W4 /WX`; actual Windows PowerShell5.1 run: **7 suites /38 tests /0 failures/errors/skips /exit0**. The previous33 tests remain plus5 new JUnit methods with multiple negative subcases. Tests use synthetic MSI tables and injected client/inventory callbacks.
- Separate actual DD1 native inventory probe: injected primary code2, actual `MsiEnumComponentsExW` calls returned27034 successes and one259. The old DLL rejected; the candidate C policy accepted only after this completed inventory. One snapshot was reused across218 synthetic targets. The source MSI hash was unchanged. This is **mixed injected/native** evidence, not a reproduction of the guest's primary API response.
- The raw probe accidentally labelled a field `notWindows10Evidence=true`; its own OS output correctly says19045. The preserved scope correction clarifies **NOT DL1 GUEST EVIDENCE** without rewriting the raw record.
- Separate actual DD1 service-side metadata-only candidate MSI: only `CcbResolveInstallDirectory` in ExecuteSequence, no InstallInitialize/File/Media/Feature/service/registration/publication actions. Native exit0 and `CCB_DIRECTORY_RESOLVED`; no program directory, both product/feature states remain -1 and no related product. This is not application installation acceptance.
- Delivery helper: PS5.1 AST parse and actual DD1 host refusal passed. It embeds a233472-byte synthetic metadata-only candidate, requires the exact existing helper and a fresh local diagnostic root, and neither reruns1102 nor changes its failure directory. Generated MSI/helper stay local; the receipt identifies their bytes/hashes.

`RAW_SHA256.json` pins the retained XML, commands, exits, compile logs, snapshots and native receipts. Source blob identity is separate from platform result provenance. The full DD1 service log contains OS account identifiers and stays locally ignored, along with the original guest failure. Only selected fixed guard-stage lines are shared as an explicitly **derived** excerpt with its own identity ledger. No user SID or backup contents are included. The earlier diagnostic harness construction failures remain local and are not fabricated as38-test RED.

## Remaining native gate

DL1 execution of this candidate, its independent inventory API behavior, full fresh installation,1102→1103 upgrade, rollback and cleanup remain **NOT TESTED** for this correction. If the guest's independent enumeration itself fails or finds the target registered, the candidate must refuse and report the specific stage. No guard disablement, installer retry, scene deletion, system-setting change, desktop merge, release or Preview/P11 PASS occurs here.

Sources: [MsiEnumClientsExW](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msienumclientsexw), [MsiEnumComponentsExW](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msienumcomponentsexw), [Win32 codes0–499](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-).
