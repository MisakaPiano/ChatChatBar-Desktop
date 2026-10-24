# Native Windows taskbook — NOT EXECUTED

Every row below is **NOT_TESTED / ENVIRONMENT_NOT_PROVEN** in LR6. This is an exact
future test protocol, not a list of invented passing test methods or a runnable
driver that already exists. No new native protector adapter was implemented.

## Prerequisite receipt and repeatable order

1. Operator supplies a purpose-built throwaway Windows VM and snapshot using
   already authorized infrastructure. Record opaque guest/snapshot identity,
   guest-only disposable principal, disk attachments and ownership. Reject host
   passthrough user directories, existing profiles, shared credential folders,
   reparse-root escapes or unreviewed provisioning. Do not create a host account
   or enable Hyper-V to satisfy this step. Keep the full identity receipt private.
2. Before starting a guest driver, independent controller verifies the guest
   receipt, explicit synthetic-root creation token and allowlisted native adapter
   SHA. Driver must reject host/default-profile mode, arbitrary paths, real archive
   inputs and absent receipt. Environment-variable strings alone are not proof.
   Pin guest identity through the established VM control channel.
3. Capture guest OS build, filesystem/volume properties and JDK17 version; establish
   baseline snapshot and fixed OLD/NEW fake payloads/two fake protected keys.
   No provider/network/authentication endpoint is allowed. Public logs use only
   fixed phase, capability and result codes; private handle/context evidence stays
   in guest-owned receipts. Never log tokens, plaintext digests, SID or paths.
4. Implement/review an opt-in guest adapter that wraps native file-ID/volume handles,
   CryptProtectData/UnprotectData, write/flush/replace/readback, cooperating locks,
   and explicit injected faults. Freeze source before each round. Parent records
   actual child exit separately from test assertions. No production classes wired.
5. Run each case in a fresh synthetic generation/root. Parent verifier after restart
   independently pins identity, validates receipts and every participant; it must
   not consume an earlier in-memory outcome. Retain failed state, original logs,
   API error categories, source, true JUnit XML and exit codes. A nonzero child exit
   is not itself a passing assertion.
6. Only after process cases pass, use the explicitly approved **guest-only** reboot
   or forced power-off controller and retained snapshot. Capture controller and
   guest receipts. A child halt cannot stand in for these cases; storage caches
   and virtual-disk behavior bound any result. Never reboot the host.
7. Stop driver, verify no running child/handle, retain source/results and check
   guest unchanged outside test roots. VM/snapshot destruction is a separate
   lifetime/owner decision, not automatic LR6 cleanup. Do not run historical CCB
   EXEs/installers even inside this guest without separately bounded permission.

## Cases and oracle

| Case | Operation / fault | Required oracle; still NOT TESTED |
|---|---|---|
| B01 native root | Pin volume + FILE_ID_INFO + ordinary directory, move path, replace directory, reparse/clone | Handle identity, generation and authenticated owner agree; foreign/unavailable never absence |
| B02 protector | Protect fake OLD/NEW under disposable principal; new process unprotect/compare internally | Correct principal round-trip, protected ciphertext retained; no plaintext output |
| B03 context change | Separate disposable guest profile/account, unavailable/moved/copied protector | Explicit UNAVAILABLE/FOREIGN; no new empty key/store; roaming/profile behavior observed, not assumed |
| B04 two keys | KEY_FIRST, DATA_FIRST, second-key error, any participant failure | No business admission until all NEW or proven OLD; OLD blobs retained |
| B05 flush | File write → native file flush → close/readback; directory-handle flush capability/error | Record actual supported/error codes; unsupported publication path BLOCKED |
| B06 publication | Required same-volume atomic replace + head CAS; cross-volume requested move | No copy fallback into authority; cross-volume reject; fresh reader validates complete chain |
| B07 halt | Child hard exit before/after intent, receipt and head publication | Real child exit + independent reader; ambiguous/lost receipt blocks; no physical-crash claim |
| B08 OS reboot | Controlled guest OS restart at same named stages | New OS session reader, immutable OLD evidence; incomplete or unsupported guarantee blocks |
| B09 power cut | Approved VM-only forced power-off at named stages | Hypervisor receipt and bounded virtual-disk evidence, not hardware proof |
| B10 corrupt receipt | Missing/partial/duplicate/unknown/versioned/MAC-invalid/conflicting receipt | No repair by trusting phase flag; preserve evidence and BLOCKED |
| B11 external mutation | Modify target/key/head/generation between observation and CAS | Mismatch caught while pinned; no overwrite and no resume after indeterminate commit |
| B12 consumer conflict | Roots A/B same Model ID, different endpoints; offline C | Explicit conflict/unknown; no global consumer closure inference or shared-key fallback |
| B13 Portable/clone | Move/copy root, detach source disk, lost authority/profile | Unsupported/foreign/ambiguous; user-guided rebind only under future approved policy |
| B14 old writer surrogate | Cooperating lock, then noncooperating same-user file writer | Cooperating success is not fencing; failure to deny direct new-root write is release blocker |
| B15 broker boundary | Separate principal DACL/IPC/client protocol, revoked lease/cache | Only if separately provisioned/approved; unreadable/unsupported old client rejected; actual old EXE NOT AUTHORIZED |
| B16 capacity/cancel | Reserve peak bytes, space exhaustion during prepare/flush/head, cancellation each phase | Prior immutable evidence retained, no half-success; after-publication ambiguity requires restart recovery |
| B17 Community | Missing sink, expiry, subject/issuer mismatch, refresh/logout conflict | No network/session activation; unsupported participant blocks whole restore |
| B18 sealed ownership | Voice + three independent Moment media, backup/GC/Portable/reexport/P13 rollback | Exact bytes/length/graph retained across independent adapters, no task/autoplay/deletion |

Power-loss persistence, native account binding, trusted MAC/anti-replay authority,
old-writer exclusion and production adapter participation remain separate gates.
The cleanup tool's five ordinary Windows file guard tests are not any B01–B18.
