# Remaining large files — recommendation only

No deletion beyond the two specifically approved AVDs is authorized by their
retirement. Sizes below are initial logical bytes, not guaranteed physical savings.

| Category / task owner | Initial bytes | Retention reason / decision required |
|---|---:|---|
| Unified-acceptance preparation installer/package subtree | 1,965,294,195 | KEEP_RELEASE; choose exact versions to retain and an archive location before any removal |
| Completion task packaged/frozen pair | 1,601,092,619 | KEEP_RELEASE / review binaries; identify required installed demonstrations and reproducibility |
| Two copied official interop/POC workspaces | 1,296,769,604 | May contain APK/build/test material; not in 199 allowlist; inventory non-generated additions first |
| Selected official source tar in interop workspace | 157,798,400 | Keep source identity/rebuild dependency; no implied archive deletion permission |
| Private LR2 witness aggregate | 600,728,309 | KEEP_PRIVATE; no content inspection or automatic cleanup |
| Compose packaging outputs | 312,315,593 | KEEP_RELEASE; establish which packaged app remains needed |
| Repository-local Gradle cache | 20,368,234 | Recommendation only; offline rebuild risk; no global-cache permission |
| Three accepted LR5 synthetic sandboxes | 508,355 | Bytes match accepted snapshots but complete active-handle/stream ownership unproved; retained |
| Read-only residual files from approved AVD retirement | 131,072 | Retained without changing attributes/permissions; no further intervention needed for space |

Other old packages and unknown project notes remain. No new cleanup approval is
requested by this report; exact owner/lifetime selection should precede a future
bounded request. Historical evidence itself is about 94.5 MB in the initial tree,
not the primary source of the 29.5 GB checkout. Future evidence policy can cap
new checkpoint size and retain immutable source identities, raw originals and
reproduction manifests; never rewrite already accepted history to save space.
