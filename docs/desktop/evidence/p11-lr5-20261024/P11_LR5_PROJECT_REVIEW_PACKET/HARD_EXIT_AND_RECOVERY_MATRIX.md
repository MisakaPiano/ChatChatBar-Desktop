# Actual hard-exit and failure matrix

Final protocol-green:29 JUnit tests,144 child invocations,50 actual halt71 at12 distinct persisted before/after boundaries. Other grouped faults are loop cases, not added test counts. Full invocation-level TSV is CHILD_FAILURE_MATRIX.tsv.

| Cut case | Actual first child | Next independent child | Later readbacks |
|---|---|---|---|
|cut-AFTER_DATA|exit71/AFTER_DATA|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_INTENT|exit71/AFTER_INTENT|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_KEY1|exit71/AFTER_KEY1|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_KEY2|exit71/AFTER_KEY2|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_MOMENT|exit71/AFTER_MOMENT|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_PUBLICATION|exit71/AFTER_PUBLICATION|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_RECEIPT|exit71/AFTER_RECEIPT|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-AFTER_VOICE|exit71/AFTER_VOICE|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-BEFORE_INTENT|exit71/BEFORE_INTENT|exit2/RECOVERY_BLOCKED|none|
|cut-BEFORE_PUBLICATION|exit71/BEFORE_PUBLICATION|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-BEFORE_RECEIPT|exit71/BEFORE_RECEIPT|exit0/ALL_NEW|ALL_NEW, ALL_NEW|
|cut-BEFORE_REOPEN|exit71/BEFORE_REOPEN|exit0/ALL_NEW|ALL_NEW, ALL_NEW|

| Other exercised condition | Actual proof / outcome |
|---|---|
|Data-first / second key failure|Independent reads see NEW data/OLD keys or key1 NEW/key2 OLD; VERIFY blocks, later RECOVER complete synthetic NEW|
|Rollback|Partial key-first -> independent ROLLBACK ALL_OLD and VERIFY ALL_OLD, NEW snapshots retained; never import success|
|Lost receipt|INTENT with missing receipt replays only valid complete evidence; published NEW with receipt missing/corrupt blocks|
|Partial/corrupt prepublication receipt / OLD reverted under receipt|Final refusal preserves bad receipt/foreign state; this was an actual earlier RED|
|Missing/corrupt/future authority, corrupt snapshot, unknown authority/WAL|BLOCKED; no guessing pending absence or deleting evidence|
|Foreign target / changed source or OLD key|BLOCKED; foreign bytes retained; no speculative rollback overwrites|
|Generation/profile/protector/account/endpoint/purpose conflict|Synthetic declarations changed after INTENT; independent restart blocks|
|Third/unknown consumer; Community/unregistered participant|Incomplete registry/context or unknown participant blocks; no real global closure proved|
|Deleted/moved key/voice/Moment participant|Unavailable is not absent; blocks complete NEW. No physical disk detach tested|
|Clone|Copied same claim/context fails actual native directory witness in fresh reader; this particular clone check runs in parent JVM, not a clone child|
|Capacity / midwrite I/O|Calculated synthetic peak checked against injected0 capacity/local available space; forced1-byte write+IOException retains partial. No real full disk/reservation proof|
|Cancel|Before INTENT retains tentative evidence and blocks; after INTENT RESTART_REQUIRED then new child recovery. No business activation|
|Repeated crash/recovery|RECOVER child itself halt71 after voice; another independent child completes, repeated recovery/readback idempotent|
|Cooperating locks / noncooperator|Competing child gets LOCKED; parent process can alter target under held locks, later recovery blocks. This disproves treating these locks as universal fencing|
|Independent copies / size cap|Physical Files.isSameFile false and exact reopened OLD/NEW bytes; oversized metadata/target refused without publication|

NOT TESTED: native SID/DPAPI/account rebind; old EXE; reboot/power loss; halt inside atomic rename; atomic-move-unsupported volume; cross-volume/removable disk; real disk full/quota reservation; adversarial same-user full-authority rewrite/CAS/reparse race; authenticated absent credentials; general transaction chains; production root discovery, cutover and business hydration. All remain outside this synthetic acceptance scope.
