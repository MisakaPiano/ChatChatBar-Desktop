# Decisions required — no defaults adopted

| Decision | Why required before live implementation |
|---|---|
| A/B topology and global/singleton scope | A needs all-consumer closure including offline/unknown roots. B requires migration and a durable namespace. T0 prefers B for further study, does not choose it. |
| Root/store identity and clone/rebind policy | Logical UUID/path alone allowed a real clone failure. Native file IDs change on copy/restore and cannot alone supply account/protector provenance. Approve persistence, duplicates, relocation and account binding semantics. |
| Legacy shared keys and retention | Decide ownership/consent, reversible transfer, old-key lifetime, backup ownership and orphan handling. Offline roots are not deleted; no automatic old-key removal or silent replacement. No retention duration is inferred. |
| Account conflicts | Same Model ID is not the same provider/endpoint/account. Global NovelAI/Fish/SiliconFlow and legacy/singleton Embedding need explicit mapping and replacement consent. Community still has no protected target. |
| Authority/discovery/receipts | Choose protected authoritative intent location, authenticated evidence, generations/fencing, receipt-loss disposition and startup discovery for every affected consumer. Unkeyed test hashes are insufficient security. |
| Recovery policy | ALL_OLD is import not applied; choose approved forward/rollback/cancel rules, ambiguous completion and lost evidence handling. Preserve source/old/new material; never reconstruct missing authority from guesses. |
| Downgrade enforcement | An old/noncooperating writer can bypass a new marker/lock. Need enforceable supported-version boundary and actual disposable-root old-binary evidence. |
| Windows durability/protection/capacity | Separate authorization for native protector/profile/SID, disk full, reboot/power loss, directory persistence, removable/cross-volume cases and safe rollback reserve. No real key/account work authorized here. |

First recommended production proposal (NOT AUTHORIZED): internal read-only target identity/consumer evidence adapter, still unwired; native identity and account binding observations, unknown/failure/generation-change rejection; no source/target key writes, durable registry or transaction. Project should freeze its exact evidence/threat model before coding. G2C remains unauthorized.
