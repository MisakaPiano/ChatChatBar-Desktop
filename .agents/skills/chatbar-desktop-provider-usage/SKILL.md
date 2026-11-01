---
name: chatbar-desktop-provider-usage
description: Maintain Desktop numeric provider-usage custody, physical-attempt capture, attribution and statistics UI.
---

# Desktop Provider Usage

- `DesktopProviderUsage.kt` owns typed numeric records, cumulative counter replacement, query filters, checked Long totals and atomic local custody in `diagnostics/provider-usage`. This local directory is excluded from portable full backups. Missing counters remain null; reported zero is retained. Cache counts are annotations, not additions to prompt tokens.
- `DesktopProviderUsageOwner` coalesces pending snapshots by physical attempt on an IO writer. Failed writes keep the latest pending record and expose a save error; read errors remain independent. Close drains after all producer transports/editors and before the data gate closes. Process death can leave RUNNING records; they remain uncertain and never imply no billing.
- After committed root maintenance sets RESTART_REQUIRED, final close uses `withLocalDiagnosticsShutdownDrain` solely for the old root's local statistics. It holds exclusive admission, keeps normal admission closed and preserves RESTART_REQUIRED on release. It is not an entity write or a new provider request; the root lock is still held. This avoids stranding an asynchronous numeric tail during restart.
- `ProviderTransportDiagnostics.onAttemptStarted` marks actual transport attempts: one per main-chat retry, one per isolated stream/complete call. `DesktopUsageAttemptRecorder` allocates independent request/attempt UUIDs and replaces cumulative SSE reports. Retry notices and UI stream collectors do not add counters a second time.
- `DesktopTransportDiagnosticsOwner` optionally receives a numeric sink. Its bounded raw request/response records remain process-local and are never serialized as statistics. Clearing diagnostic rows does not erase active numeric capture.
- `DesktopUsageContext` carries immutable actual task/session/card identities separately from diagnostic UI groups. Moments sets a coroutine `DesktopUsageParent`; `moments:JUDGE/GENERATE` are display groups, not real session IDs. Named account ID/provider come from the actual resolved model credential attribution, not names or URL parsing.
- Current capture covers main chat, character/format/world-book AI and Moments text/debug through the connected diagnostics owners. Complete NAI, voice, embedding and other provider coverage remains separate work; do not claim full billing coverage. `DesktopProviderUsagePanel` is under management's other settings, with time/source filters and per-model/provider/account groups. No monetary estimate is inferred.
- Tests: `DesktopProviderUsageTest` (unknown/zero/partial, retry dedup, overflow, reopen), `DesktopProviderUsageCaptureTest` (actual synthetic HTTP modes/retry, diagnostic clear, private-body exclusion and failed-write recovery).
