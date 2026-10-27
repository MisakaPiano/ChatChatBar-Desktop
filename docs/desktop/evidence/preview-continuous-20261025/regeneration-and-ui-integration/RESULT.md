# Independent follow-up of broad headless failures

The earlier broad run remains a genuine 247-suite / 2,150-test RED. This follow-up uses a separately retained test-only init script with `forkEvery=1` to isolate each class in a new JVM. Native OS-window/Robot suites are not part of these runs; offscreen Compose input stays inside its synthetic scene. No real archives, credentials, DPAPI context, Provider or user root is used.

| Run | Suites/tests | Failures | Exit |
|---|---:|---:|---:|
| Independent original follow-up | 7 / 59 | 9 | 1 |
| First correction plus new sibling-turn case | 7 / 60 | 6 | 1 |
| Further test alignment | 7 / 60 | 2 | 1 |
| Final, including independent Memory safety/lifecycle | 9 / 67 | 0 (zero errors/skips) | 0 |

## Actual production defect

`planRegeneration` initially removed its target, but `planCurrentUser` reloaded the complete repository candidate set. Whole-turn expansion could then reintroduce an earlier target when a later user was selected as current. The reloaded candidate universe now excludes that target before expansion. Existing regeneration tests plus a new linked sibling case verify that the old target body is absent, other replies in its source turn remain, the latest user remains current and the persisted target is unchanged. No Prompt literal, shared boundary policy, memory persistence or source-turn identity was changed.

## Test repairs and scope

- Flat-menu tests follow the accepted reply/segment/message rows and their accessible descriptions, including disabled repair actions while derived data is unverified. Repair/Stop/restore, whole-vs-segment scope and exact stored-message assertions remain.
- User image messages have Copy/Edit footer controls and a header-owned message context menu; they do not require the retired assistant More button. Per-image menu isolation, destructive-action guards, header/image/footer placement and multiple/omitted-image checks remain.
- Offscreen tool scrolling now advances the scene's frame timestamp. The original geometry/visibility and actual Apply action assertions are unchanged; the static default timestamp had prevented scroll animation from advancing.
- Narrator tests use a fresh local UI selection and current accessible option identity. The compact narrator pill and absence of generation/detail actions when no batch exists are checked; explicit user selection, disabled-before-selection, Escape and zero-network assertions remain.
- The Phase-5 fake server scripts main-chat responses only. Its synthetic session now explicitly disables long-term-memory background work, rather than comparing a read-only inspector (which deliberately omits runtime memory) with a full memory-enabled generation. Request-time role evidence uses the actual relevant settings. Wire authentication, private-key exclusion, persisted/reopened conversation, second main-chat request, inspector/direct-plan consistency and HTTP role adaptation are still verified. Memory is tested independently in the final run; no production Memory option was disabled.

The first follow-up no longer reproduced the earlier cross-suite `UncaughtExceptionsBeforeTest` in `DesktopFakeChatRuntimeTest`. Remaining non-native failures were individually corrected as above; this does not turn the fourteen native/headless failures into PASS or claim a complete rerun of all 2,150 tests. Studio results are not counted. Raw commands, source snapshots, original failures and JUnit XML are retained per run.
