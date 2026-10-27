# Old-writer fencing result

**NO ENFORCEABLE PRODUCTION FENCE PROVEN. RELEASE BLOCKER RETAINED.**

Actual `DesktopDataRootOwnership` is a cooperative lock lifetime. The process-local
coordinator and bootstrap sibling lock govern cooperating callers of their own
protocols; neither forces an old EXE to read a new marker or coordinate secrets.
LR5 already observed a noncooperating same-user writer mutate a synthetic payload,
then fail verification. Detection afterward is not prevention or safe business
admission. LR6 did not rerun that experiment or execute any old binary.

Windows LockFileEx protects a specified byte region; it is not a recursive root
authorization mechanism. New-format marker, copied UUID, known process list,
code signature or launcher-only check cannot prevent direct same-user file I/O.
[Microsoft LockFileEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex).

| Candidate | Result / limitation |
|---|---|
| New JSON marker, mutex, versioned path | Cooperative only; unaware writer may ignore it |
| Same interactive-user DACL/readonly flag | Owner may change permissions; not an enforceable boundary against that principal |
| Fresh root under same user | Narrows accidental collision, not a root/secret ownership barrier |
| Signed installer/launcher allowlist | Supports provenance; no direct-write prevention by itself |
| Separate principal owning NEW root + protected authority and restrictive IPC | Conditional recommendation; could deny ordinary old-user direct writes, requires new I/O architecture and isolated proof |
| Separate test/login account deployment | Alternative boundary; affects usability/protector/Portable migration and needs explicit decision |

Candidate threat envelope must state ordinary unaware old EXE and cooperating
concurrency vs malicious same-user injection/admin/VM-disk rollback. The proposed
broker does not automatically defeat all hostile same-user code, stolen authorized
client capabilities, administrators or offline disk replacement. Denial of direct
writes, IPC protocol admission, cached key revocation and downgrade/install paths
must each be tested. No principal/service/ACL was installed in LR6.

OLD legacy keys/root remain original retained data. Protecting immutable escrow
is a distinct future participant; merely warning users not to open old roots does
not satisfy R03. If the chosen deployment cannot enforce the accepted threat
model, retain BLOCKED and revise architecture rather than relax the requirement.
