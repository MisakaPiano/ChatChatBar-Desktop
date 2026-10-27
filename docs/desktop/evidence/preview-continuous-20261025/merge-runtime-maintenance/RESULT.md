# Temporary task admission for merge publication

No manufactured RED. New tests use real DesktopTaskRuntime: held task cancellation/final-write drain before maintenance, new admission/mutation refusal inside maintenance, resumption after prepublication failure, and permanent shutdown precedence. Existing task/coordinator/root migration suites rerun; exact counts in RESULT.json. A drain timeout keeps admission stopped rather than certifying a snapshot. No OS-wide lock, native DPAPI or power-off claim.
