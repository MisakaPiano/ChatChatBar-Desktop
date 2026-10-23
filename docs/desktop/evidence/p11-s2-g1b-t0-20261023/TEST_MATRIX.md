# Executed matrix and fact levels

All PASS below describe synthetic test assertions, not P11/D-038/Windows release acceptance. New suite20 tests contains loops over both topologies and multiple cases; those loops are not additional JUnit tests. Final existing suites: G1A17, G2A plan17, intent20, WindowsSecretStore7, CommitJournal4, Startup20, DataOperationCoordinator14 =99.

| Scenario | Actual probe | Finding / level |
|---|---|---|
| Key first / data first | Child installs first real file, halt71; callback remains uncalled; another JVM recovers | A/B: SYNTHETIC_PROCESS_CRASH_RECOVERY_OBSERVED, ALL_OLD |
| All writes before receipt | Child leaves uniform NEW bytes then halt71 | Business still blocked, fresh recovery OLD; bytes alone are insufficient |
| Second key failure | First actual protected blob writer succeeds, injected failure before second key install | Mixed NEW/OLD observed; blocked; recovery OLD; evidence retained |
| Before intent | Prepared snapshots then halt71 without reservation | Verified baseline OLD usable, no live writes; preparation retained |
| After intent | Published reservation/intent then halt71 | Blocked until reverified recovery |
| Normal NEW / repeated startup | Complete actual data/key copies and receipt; fresh processes recover repeatedly | ALL_NEW reverified, evidence tree stable |
| Recovery hard exit | halt72 after first old-file installation; another child repeats recovery | Idempotent recovery OLD; no business callback while mixed |
| Decision published, no receipt | halt71 after decision | Fail-closed, even though all bytes NEW |
| Receipt corrupt/lost/conflicting | Actual file alteration/rename; old receipt copy retained | Fail-closed, no target overwrite |
| Receipt exists, decision lost | Actual decision rename, full NEW live files | Real RED exposed wrong rollback; final fail-closed |
| Intent/evidence unavailable | Actual intent rename or old evidence alteration | Unknown, not absent; fail-closed |
| Foreign data/generation/key/binding | Actual file write or real store save before recovery | CAS/binding mismatch blocks; foreign target remains unchanged |
| Same model ID, different endpoint | Real equal logical key addressing; synthetic B binding changed | A rejects shared overwrite; B's A operation leaves B key files unchanged |
| Offline B | Registered directory renamed into retained offline location | A refuses; B's A namespace completes, unavailable B bytes retained; simulated path loss only |
| Root clone replacement | Copy root ID/data to new directory at same path | Initial NIO identity RED; final native volume/file-ID rejects |
| Root switch / Portable relocation | Rename registered A, retain original; no automatic rebind | Blocked; real Portable runtime/migration NOT TESTED |
| Cooperating cross-process exclusion | Parent holds real coordinator/root locks, child consumes B | A child blocked; B independent namespace permits B OLD under A's exclusion scope |
| Legacy bypass | Real WindowsSecretStore primitive writes without joining test coordinator | UNSAFE_BYPASS_OBSERVED; candidate gate then blocks; actual old executable NOT TESTED |
| Unknown consumer C | Actual unregistered data file + shared new key | GLOBAL_CONSUMER_CLOSURE_NOT_PROVEN; topology A cannot infer absence of consumers |
| Context invalid / unsafe key target | Mismatched synthetic context input; key file replaced by retained copy + directory | Fail-closed; actual store read rejects unsafe target; no null/ABSENT inference |
| Community/no consent | Preparation requested with unmapped Community or false consent | Refused before snapshots/live writes; no substitute API-key target |

**NOT TESTED:** actual DPAPI/profile change, valid protected keys from another account, independent Windows SID/HKCU, VM reboot, power loss, physical media disconnect, true ACL denial, cross-volume publication, filesystem full, historical binary, real Portable/root migration, real .cbbackup source authentication in this lab, genuine Repository/Provider activation, concurrent malicious metadata forgery/check-use interference. These remain NEEDS_ADAPTER / PLATFORM_VALIDATION_REQUIRED, not synthetic Windows PASS.

The registry and context are test-controlled assertions; native root ID is observed, but global closure/protector identity is not. Neither protocol is production-approved.
