# R7 — packaged Windows manual gate

**R7 WINDOWS SCOPED ACCEPTANCE CANDIDATE / DECLARED OBSERVATION LIMITS / PROJECT REVIEW PENDING**.

Manual-start base `079428499fd3d4c36ef8ee2e6e60b3528d4787e4`; packaged build HEAD `bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5`; source candidate `ec32fdbc8383853b3024b585c19db0c3b840f47f`. Exactly one frozen R7 GUI instance and its loopback fake were launched after explicit readiness. No source or product Gradle changes, Robot, JUnit, bounded or R6 runner.

This directory is an append-only manual-stage supplement. The [preparation report](../README.md) and [original preparation manifest](../manifest.json) remain unchanged historical snapshots; this directory has its own manifest. Preparation NOT TESTED entries are not overwritten retroactively.

## User results and native observations

The user signed M1–M4 operation groups PASS; zero product FAIL reported. [Original records](manual-user-records.jsonl) retain exact answers/question IDs and later clarifications; collection UTC timestamps are not fabricated action timestamps. [Structured results](manual-results.json) distinguish human reports and technical proof.

- **M1 PASS:** actual Picker client344dp/DPI96; user saw filter floating over single-column results, focused tag input and reported first Escape dismisses panel, second closes Picker. Native parent disable/restore recorded.
- **M2 PASS reported:** user actually heard sample and Chinese canned generation; Stop icon+text visible and effective; waiting sample cancellation returned UI control. Two fake waiting sample bodies reached EOF/drain. **Caveat:** the post-report native snapshot was1064dp wide, not synchronous with compact-Popup playing. User's explicit compact-Popup report is retained, but synchronous native confirmation is NOT TESTED. No real TTS text-quality/content parity is claimed.
- **M3 PASS reported:** one requested physical scrollbar drag plus wheel, wide same-row pager, Enter→3 / click→2 / invalid4 rejected and new page starts top, narrow input/jump remain adjacent and accessible. Technical requests during the controlled page sequence were exactly[3,2], no4. Wide client1064dp; later narrow519dp. **Input-only limitation:** extra page1 queries appeared during the later editing interval; user acknowledged extra Enter/jump/query to fill omissions. Original count assertion/UNKNOWN and clarification are preserved; no automatic-query defect is proven, and strict input-only no-query is not independently verified. No repeat was requested.
- **M4 PASS:** titleR7, targetA and text retained across wide/narrow (sampled956dp→515dp); cached generation reused with disclosure, sampleA remained distinct; user closed Picker and reported editor usable/Alice unbound. Resize and cached replay each produced zero additional HTTP. Both persisted synthetic card hashes stayed equal.

See [actual geometry](geometry-observations.json), [request accounting](request-accounting.json), [limits](verification-limits.json). Owned foreground often false when user returned to chat; that is not proof of missing focus during their actions. No screenshot/semantic/offscreen state is substituted for hearing or human signatures.

## Single package and exit

All212 files were verified before launch and after exit against the [frozen distribution manifest](../distribution-manifest.json); no missing/extra/hash mismatch. Production source blobs/hashes and all historical evidence trees unchanged. [Final integrity](manual-final-integrity.json).

Native launcher PID4480 and JVM-host PID38324 both actually exited0. Production shutdown trace reached OWNERSHIP_RELEASED / MAIN_RETURNED / SAFE_EXIT_HANDOFF. User signed normal main-window closure without exception; application stayed open for1577.394s, with no automatic GUI timeout. The raw watch close-marker field remains null because no marker was written before user closure; it is not rewritten.

Active independent root acquire was refused75. After exit, independent acquire+release succeeded0. fake exit0/drained; ephemeral private key/truststore deleted. No preview MP3/.part remained, no Java/owned app process remained. Data, frozen package and fixed audio fixtures were retained; no root lock deletion or force kill. [Process result](runs/manual01/process-result.json), [cleanup](exit-lock-cleanup.json).

## Actual requests

Manual fake **catalogGET8 / sampleGET3 / cannedTTSPOST2**; two sample-body drain records are separate. Preparation fake **2/1/1** is kept separate. Real Fish **GET0/TTS0** throughout. All logged sample requests had no secret header; fake never forwards upstream. Extra operations and counts are preserved, not erased or turned into a proven client retry. No system-wide packet capture was performed.

## Validation issues retained

The read-only native geometry observer initially failed on PowerShell array subtraction. Only ignored build helper parentheses were corrected; [original error](window-observer-initial-error.json), original/fixed scripts and [delta](read-only-observer-fix.patch) retained. No product input or source change. The M3 event-count assertion was a validation observation with intervening user submissions, not a new JUnit/product RED. Both issues remain visible.

User also asked about sample transcript preview. Current cards display service sample.text at most2 lines/ellipsis; R7 fake text is a descriptor, not a verbatim audio transcript. No expanded transcript/automatic transcription claim or opportunistic UI/fixture change.

Historical R6D/R6E NativePASS, R6E overall cmd.exeRED, R6F/R6G Robot Press0RED/UNKNOWN, R6I/R6J INCONCLUSIVE and user scroll reports, old symlink SKIPPED/NOT TESTED remain intact. WIN-AUTO-001 and WIN-BRAND-001 remain OPEN. No full bounded GREEN, UI closure, desktop merge, release or later Phase.

Only this evidence supplement may be committed. Four user-owned files remain unread/unmodified/untracked/unstaged. No executable/JAR/audio/profile/credential/private TLS material is included. **STOP and return to Project for independent scoped adjudication, including the declared observation limits.**
