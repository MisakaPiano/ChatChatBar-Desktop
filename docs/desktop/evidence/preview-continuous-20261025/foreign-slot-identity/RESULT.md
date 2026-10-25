# Independent identities for foreign SaveSlot restore

Actual authorized real-root diagnostic: a new isolated target's prepared restore contained 32 voice-anchor paths already owned by the original conversation. The existing session journal refused before an intent authorized any live replacement. This is a Desktop planning defect, not evidence of corrupt Android data. Raw input/IDs/logs stay private.

An independent synthetic child-JVM reproduction failed at the same session-journal expected-state check. RestorePlan previously changed session/voice IDs but retained source message IDs. Anchor IDs are global hashes of message/version, so they collided. The journal's refusal is preserved.

The planner now reuses the existing shared SessionCopyPolicy when source session differs or a strict live observation finds source message IDs used by another session. The latter also handles legacy library envelopes already rebound to the target. Service observes under normal coordinator admission with strict path/schema/count checks. Message/generated-from/RAG references and the resulting anchor namespace become independent; explicit versions/source-turn evidence and original source bytes remain intact. Same-session noncolliding restore retains message IDs. No official serializer, Prompt, Android policy or journal expected-state validation is relaxed.

|Actual run|Suites|Tests|Failures/errors/skips|Exit|
|---|---:|---:|---|---:|
|RED|1|1|1 / 0 / 0|1|
|first fix|3|25|0 / 0 / 0|0|
|final affected regressions|6|45|0 / 0 / 0|0|

The final run covers actual separate-JVM foreign restore, byte/hash-stable original session inventory, retained historical ID excluded from current playback, whole-root graph admission, legacy1–7 foreign imports and unchanged same-session IDs, plus SaveSlot/Journal/Archive/Mapping/MergeRoot regressions. Seven legacy variants are inside one JUnit method, not seven extra tests. Counts are per run.

This proves cooperating-process synthetic behavior, not global OS consumer closure or physical Android. The real isolated target retry follows this checkpoint. Installer rollback, oversized in-place safety capture and GUI navigation limitations remain independently recorded; no Preview PASS.
