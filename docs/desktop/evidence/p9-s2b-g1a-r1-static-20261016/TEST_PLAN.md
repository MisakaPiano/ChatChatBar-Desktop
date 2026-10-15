# G1A-R1 pending validation plan

All entries below are test SOURCE, NOT executed evidence. No JVM/Gradle process was started for R1.

## R1-1 — DesktopApplicationLifecycleTest

Nine added test methods cover:
- Suppression-disabled close: explicit close-outcome carrier; exact original as cause/primaryFailure and exact closeFailure field; ownership remains held.
- Suppression-disabled initialization primary with disabled, normal, and drain-timeout close failures.
- Normal primary identity and existing primary/secondary suppression order.
- Normal primary with disabled close: original identity, existing order, appended fallback diagnostic only.
- Cancellation primary with disabled close, and cancellation thrown by close.
- Same instance from initialize and close: disabled exception, cancellation, normal exception; no self suppression.
- Confirmed suspending close: disabled or cancellation primary preserved; ownership release follows actual close completion.
- Subsequent successful lifecycle not contaminated by an earlier incomplete outcome.

Existing normal lifecycle, timeout, cancellation, backup/draft/coordinator suppression, initialization and same-instance assertions remain in source.

## R1-2 — DesktopProcessLifecycleTest

- Update the two real-container writer-drain fixtures to acquire ownership BEFORE constructing the container, then initialize/start tasks and close through the lifecycle owner.
- Use a fixture-owned SupervisorJob/IO scope, explicit Deferred handles and release signals. An implicit runBlocking child join must not defeat teardown's timeout.
- finally signals the synthetic gates and joins the lifecycle with a 15,000 ms timeout INSIDE NonCancellable. It does not issue a second unbounded container.close().
- A separate added test uses a 50 ms injected budget and an explicit gate to verify incomplete teardown diagnostics and original failure cause, without cancelling/claiming a pending writer completed.
- If lifecycle completion or coordinator CLOSED is not confirmed, retain the scope/root/any held ownership, record incomplete drain, and fail diagnostically. Do not delete even a root whose lifecycle is still queued before lock acquisition.
- These are test-fixture deadlines, not production I/O timeouts. Actual blocking file I/O has no physical absolute cancellation guarantee.

## Future authorized window only

Compile Desktop main/test, then run DesktopApplicationLifecycleTest and DesktopProcessLifecycleTest, followed by the previously approved G1A focused scope if Project permits. Preserve every real RED/log/XML in a new directory. This plan does not authorize running those steps now.
