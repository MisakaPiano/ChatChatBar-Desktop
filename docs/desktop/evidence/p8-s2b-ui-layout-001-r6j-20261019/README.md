# R6J — controlled human input capture

Status: **INCONCLUSIVE / HOLD**. One manual launch; no retry. No product repair or source candidate commit.

## Observed

- Preparation compiler and deterministic protocol checks passed; frozen phase-A evidence is preserved byte-for-byte in [phase-A-preparation](phase-A-preparation/README.md).
- Same-run actual `armed.txt` and `FISH_R6J_ARMED` were verified before the drag instruction. Baseline range was 0, recorder registered and owned window valid.
- User said: “已拖动一次并释放，先保持窗口”. This is an action report, not a signed scroll-success result.
- The physical Press at (1132,184) reached the owned SkiaLayer. Read-only Win32 sampling confirmed owned hit root/foreground and matching pointer coordinates with left button down.
- Recorded initial thumb was (1120,155,8,104). Press was outside that measured rectangle. The dedicated human recorder immediately entered HELD with raw classification `MANUAL_HIT_MISS`.
- The independent existing owned-AWT observer retained 1 Press, 30 Drag and 1 Release. Do not confuse its counts with the truncated human recorder counts (1/0/0), or double-count duplicate R4 log lines.
- No CAPTURE_COMPLETE was produced; the capture verifier correctly rejected completion. After-range and after-first-item are absent. No success is inferred.
- Recorder held the window for its bounded confirmation period. It then closed with `CONFIRMATION_TIMEOUT`, cleanup=true. No finish.request was written and no user finish permission was fabricated. Manual JavaExec exited 1 after cleanup, not a JUnit result or proven product crash.
- Post-run inspection found no Java/javaw/relevant runner process. Catalog1 / HTTP0 / binding0; real Fish GET0/TTS0. No Robot, JUnit, bounded, packaging or distribution root-lock gate executed.

## Interpretation and limits

The Press did not satisfy the recorder's measured-thumb containment condition. This does **not** independently prove the user missed the actual visible thumb. Whether measured thumb origin/hit geometry matches the native visual target remains UNKNOWN. Complete physical-scroll acceptance is INCONCLUSIVE, not PASS or product failure.

R6F/R6G historical Robot Press0 causes remain UNKNOWN. Earlier Native PASS and all historical RED remain unchanged. UI-LAYOUT-001 remains HOLD; WIN-AUTO-001 and WIN-BRAND-001 remain OPEN.

Project may consider a narrow review of the thumb measurement versus actual layout before any next attempt. No such repair or repeat was performed here.

## Evidence

- [Raw control records](phase-B-physical-input/manual-control/f45e6c7a-1aa2-4152-8499-4d6669fffd47/result.txt)
- [Raw stdout](phase-B-physical-input/02-human-physical/gradle.stdout.log), [stderr](phase-B-physical-input/02-human-physical/gradle.stderr.log)
- [Derived analysis](phase-B-physical-input/analysis.json), [separate user record](phase-B-physical-input/user-record.json)
- [Post-run resources/process/source inspection](phase-B-physical-input/AFTER-B-preflight.json)
- [SHA-256 manifest](manifest.json)

Three engineering drafts remain unstaged/uncommitted. The four user-owned files remain unread/unmodified/untracked. Only this evidence directory may be committed. No CURRENT, baseline, parity, Prompt, Entity or runtime change.
