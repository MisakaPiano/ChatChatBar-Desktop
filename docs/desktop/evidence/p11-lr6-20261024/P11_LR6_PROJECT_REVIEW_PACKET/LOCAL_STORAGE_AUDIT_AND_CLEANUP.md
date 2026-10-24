# Local storage audit and cleanup — first LR6 priority

Authority: 197–199 plus the human's later **two-directory exception** in
[the supplemental receipt](STORAGE_USER_SUPPLEMENTAL_AUTHORIZATION.md).
The exception does not retrospectively broaden 199 or permit another cleanup.

## Results

- Repository initial census: **29,521,759,319 logical bytes**. Initial volume free
  at census completion: **740,913,152 bytes**. The earlier lightweight preflight
  free reading was 777,711,616 bytes; these are different observations.
- The four exact compiler scratch directories in 199-A were absent.
- 199-B: 1,325 raw LR5 items + seven tool identities matched SHA/length and HEAD
  blobs; all three sandbox file sets/bytes matched accepted final-state ZIPs.
  Their 3,664 files / 508,355 logical bytes were nevertheless **retained**:
  no complete exclusive-use/alternate-stream ownership proof was made for them.
  No quota was used to weaken this decision.
- The separately approved AVD exception removed **50 files / 8,110,071,350
  logical bytes**. Each environment retains one read-only file, together
  **131,072 bytes**. Directory shells holding those files remain. No attribute
  or permission change, elevated deletion, force-unlock or process termination.
- First actual apply returned **exit2** at a read-only pstore file after five
  files / 69,406,721 logical bytes had disappeared. This was a **partial cleanup**,
  not a successful full delete. Original log/exit and source are retained.
- Revised plan checked every remaining file against original identities;
  read-only entries were explicitly retained. Second apply: **exit0**, 45 files /
  8,040,664,629 logical bytes. Immediately measured free bytes:
  **322,097,152 → 8,299,012,096**, delta **7,976,914,944**.
- Final per-file reconciliation against original manifest proves the aggregate
  50 deleted files and both retained unchanged read-only identities.
  **All 647 historical evidence files / 8,341,335 bytes remain matched to HEAD**:
  646 exact raw blobs, one canonical CRLF text match. No original archive, XML,
  command log, source, or application package was deleted.

Logical bytes are not allocated bytes. AVD sparse/compressed files and unrelated
volume activity prevent attributing all free-space movement solely to this task.
The second-apply interval delta is separately reported, not misrepresented as
the entire task's physical allocation savings. The matching enumeration census,
`LOCAL_DISK_AUDIT_AFTER-ENUMERATION.json`, measured **21,486,390,458 logical bytes**
and **8,221,466,624 free bytes** later. Its raw logical difference from the first
census is 8,035,368,861 bytes, not the deletion ledger's 8,110,071,350: retained
LR6 ledgers/source snapshots and non-atomic measurement have their own lifetime.

An intermediate direct-`lstat` census (`LOCAL_DISK_AUDIT_AFTER.json`) is explicitly
**INCOMPLETE**, not a claim that more files were deleted: thousands of old build
files denied direct attribute queries while enumeration metadata remained readable.
Its 21,191,008,776-byte subtotal must not be used for savings. Original output was
retained. Enumeration metadata is sufficient for a logical-size census but does
not prove object identity; actual cleanup separately required direct pinned
native handle identities. The final census change did not relax that delete guard.

## Measurement / deletion boundaries

The local exact path/identity CSV and dry-run manifests are Gitignored under this
task's own `build/p11-lr6-private-space`. Public reports contain aggregate owners
and the explicitly approved paths only. No symlink/reparse/mount descendants were
traversed. Global Gradle/Maven/IDE caches and every outside-repo directory were
OUT_OF_SCOPE / NOT MEASURED. The phone export folder was never read or cleaned.

Deletion used same-runtime absolute paths inside the repo, native object identity,
exclusive file handles, directory handles denying rename/delete, alternate-stream
checks and a fresh process-name screen. Actual deletion was by pinned file handle,
followed only by bottom-up removal of now-empty known directories. This is ordinary
permission cleanup evidence, **not** LR6-B protected-context/durability evidence.

Saved emulator disks/RAM were discarded under explicit consent; they were not
copied, falsely called redundant raw evidence or declared losslessly archived.
The surviving archives and historical tests permit reconstruction of synthetic
tests, not resurrection of that exact VM snapshot.

## Remaining recommendations — NOT deleted

The large initial `desktopApp/build/other` aggregate was 27,688,802,932 bytes,
including those AVDs, copied official workspaces and many packaged app layouts.
Root-level unknown files, installed/review packages, private witnesses, LR2–LR4,
backups/journals, all Git and original evidence remain protected. Repository-local
`app/.gradle` measured 20,368,234 bytes; expensive offline rebuild risk makes it
recommendation-only. Git packs measured 238,225,565 bytes, other objects
17,159,936 bytes, other Git metadata 3,697,744 bytes; **no gc/prune/repack**.
The pre-existing Git temporary-object warning was retained, not “fixed” by deletion.

Use [REMAINING_APPROVAL_REQUIRED](REMAINING_APPROVAL_REQUIRED.md) for a future
precise retention decision; no broad build-directory or historical Git deletion
is recommended. Local file names and private children are not published.

## Actual tool tests

Initial audit-policy run: **12 tests, eight PASS / three FAIL / one ERROR, exit1**.
Windows `DirEntry.stat()` returned inode/link count zero while direct `lstat()`
provided identity. Guard was retained; per-path query fixed the mechanism.
Second run: **12/12 PASS / exit0**. Cleanup extension: **17/17 PASS / exit0**;
after read-only skip correction **17/17 PASS / exit0** again; after separating
census metadata from cleanup identity **17/17 PASS / exit0**. These are Python
unittest methods, not JUnit or additional old LR5 tests. No Gradle/GUI/Android
tests ran. Original outputs and pre-run exact source snapshots are retained.

Skill review: no existing full-backup/SecretStore feature behavior changed;
one-time tooling is documented under `tools/desktop/interop/lr6/README.md`.
