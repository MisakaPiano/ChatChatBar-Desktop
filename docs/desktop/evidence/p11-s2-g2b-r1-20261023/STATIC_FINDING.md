# R1 static finding before correction

Project review change request; source control `40f0f3dec9b6f1448314ca25804bb9091e7751d2`, feature phase8-fish-audio. Local/origin matched; tracked worktree clean, six known protected untracked files retained unread/unhashed/unmodified/unstaged. Only this task was observed active at this cwd. Remote desktop remains5ef95428; no reset/force or branch movement.

Project independently reviewed prior G2B boundaries, six source identities,15 XML/179 tests and17 raw SHA/length samples. Those results are not new executions here. R1 remains the same bounded authorization, with only census source/test (optional fixture) and new evidence permitted. Full-backup skill read; the correction does not change its entry points/lifecycle and does not require a skill edit outside this exact scope.

Before repair, DesktopCredentialSourceCensus.kt line54 accepts expiresAtMillis if missing or jsonPrimitive.longOrNull is nonnull. That accessor can parse a JSON string's content; it does not itself enforce a numeric JSON token. Official145 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`, domain/community/CommunityModels.kt, declares `expiresAtMillis: Long = 0L`. R1 requires missing/default or valid numeric Long only, rejects string/null/boolean/fraction/containers/overflow, retains unknown-field rejection. No sign/domain restriction beyond the official Long type is invented.

This static finding is not an executed RED. New independent code84/v1 wire regressions will be run against unchanged production first; their actual outcome is retained separately. Original G2B24-test RED and179-test GREEN remain untouched. No real data/DPAPI, Inspector/restore/Schema/Prompt edits, G2C, desktop merge or HOLD promotion is authorized.
