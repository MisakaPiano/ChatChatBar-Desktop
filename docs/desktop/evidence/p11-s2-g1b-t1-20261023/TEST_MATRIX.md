# T1 executed coverage map

Actual commands, source snapshots, exits and original XML are separated by round under raw. Counts and RED/GREEN attribution appear in execution-summary.json and PROJECT_HANDOFF; history is not accumulated.

| Scenario | Independent observation/assertion | Fact level |
|---|---|---|
| Two explicit native roots | Actual native volume/file IDs differ; both directory baselines stable; configuration provenance synthetic; closure/context/account UNKNOWN | SYNTHETIC_RUNTIME_OBSERVED + WINDOWS_NATIVE_ATTRIBUTES_OBSERVED |
| Missing baseline | No native object association -> UNKNOWN | SYNTHETIC_RUNTIME_OBSERVED |
| Clone same content/UUID | Actual copied file bytes retain UUID; new directory object rejected against original native witness | SYNTHETIC_RUNTIME_OBSERVED |
| Data/store replacement | Original directory retained by rename; newly created same-path directory conflicts | SYNTHETIC_RUNTIME_OBSERVED |
| Offline B | Rename B; declaration remains UNAVAILABLE, never ABSENT | SYNTHETIC_RUNTIME_OBSERVED; physical disconnect NOT TESTED |
| Undeclared C | Create unregistered synthetic directory; no enumeration; cannot exclude C | GLOBAL_CLOSURE_NOT_PROVEN |
| Equal Model ID different endpoint/provider/remote model | Explicit G2A identities differ; conflict in declared consumers | SYNTHETIC_RUNTIME_OBSERVED |
| Distinct credential directories | Still reports logical account mismatch; no topology selection inferred | SYNTHETIC_RUNTIME_OBSERVED |
| NovelAI/Fish/SiliconFlow | Always UNKNOWN secret relation; global range explicitly unproved | NEEDS_ADAPTER |
| Duplicate key + Community | Duplicate conflict cannot be downgraded by unsupported family; Community no sink | SYNTHETIC_RUNTIME_OBSERVED |
| Duplicate root/name collision/invalid account | Explicit conflicts, no overwrite candidate | SYNTHETIC_RUNTIME_OBSERVED |
| Generation/endpoint change | Reader returns two different snapshots -> CONFLICT | SYNTHETIC_RUNTIME_OBSERVED; OS CAS NOT TESTED |
| Mid-observation directory replacement | Real move/create during metadata callback; second native pass conflicts | SYNTHETIC_RUNTIME_OBSERVED |
| Native-reader error | Injected read failure -> UNAVAILABLE, sanitized | SYNTHETIC_RUNTIME_OBSERVED; real ACL NOT TESTED |
| Store/configuration unavailable | Actual store-directory rename plus injected metadata error -> UNAVAILABLE, no ABSENT | SYNTHETIC_RUNTIME_OBSERVED; no actual SecretStore API call |
| Cancellation | No partial result; fixed cancellation; directory movable afterwards (no retained handle) | SYNTHETIC_RUNTIME_OBSERVED |
| Out-of-sandbox/relative/missing/file paths | Refused; missing path not created | SYNTHETIC_RUNTIME_OBSERVED |
| Unknown/invalid/over-limit declarations | Incomplete, including missing model account and negative generation | SYNTHETIC_RUNTIME_OBSERVED |
| Data/store overlap | Explicit conflict, no schema/namespace created | SYNTHETIC_RUNTIME_OBSERVED |
| Read-only/privacy | Full test-owned file inventory SHA before/after equal; object rendering omits path/account/body/native ID | SYNTHETIC_RUNTIME_OBSERVED |
| G2B -> G2A metadata boundary | Existing Inspector verifies independent synthetic code84/v1 source; T1 cannot supply usable target admission; source bytes unchanged | SYNTHETIC_RUNTIME_OBSERVED |
| Selected existing regression | G2A, G2B, native read, resolver/store, ownership/startup/journal/G1A/T0 | See actual final XML, not historical totals |

No fake protection or synthetic generation is attributed to actual DPAPI/CAS. T0 re-execution is an existing isolated child-process regression, not a new production transaction or T1 durable-restart capability. No T1 hard-exit protocol is needed: T1 writes no intent or durable state. Reboot/power-loss/old-binary/real Provider checks remain NOT TESTED.
