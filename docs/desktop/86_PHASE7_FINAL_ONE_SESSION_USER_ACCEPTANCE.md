# Phase 7 — Final One-Session Windows User Results (86)

**USER 18/18 REPORTED / 16 PASS / 1 FAIL / 1 NOT TESTED / PHASE 7 NOT ACCEPTED OR MERGED**

## Exact evidence

User-reported Windows source SHA: `698f0cd55e0ae370650fac012cdb50f0dd7e033f`. Canonical instructions: `84_PHASE7_SINGLE_SESSION_MANUAL_ACCEPTANCE_CN.md`. Codex packaging is independently recorded in `85` (210/210 SHA-256 per Codex); Project did not operate the executable or run new tests.

| Group | What user checked | Outcome |
| --- | --- | --- |
| 01 | AI Design UI / Auth / Chinese IME | **PASS** |
| 02 | Existing AI Design history, action placements | **PASS** |
| 03 | Character prompt cards/disabled/fold | **PASS** |
| 04 | Character positions | **PASS** |
| 05 | V5 allowance/cost UI | **PASS** |
| 06 | Tag completion/caret/fullscreen | **PASS** |
| 07 | History grid/Shift selection | **PASS** |
| 08 | Viewer Reset/Fit/title | **PASS** |
| 09 | Role metadata mode and safe import UI | **PASS** |
| 10 | Enhance/Upscale tabs/cost | **PASS** |
| 11 | Image tool privacy/local result | **FAIL** |
| 12 | Image guidance UI | **PASS** |
| 13 | Pending multi-image attachments | **PASS** |
| 14 | Clipboard/drop/APNG ingress | **PASS** |
| 15 | Advanced/Diagnostics | **NOT TESTED** |
| 16 | Session Settings tabs/dirty/cancel | **PASS** |
| 17 | Background library | **PASS** |
| 18 | Chat/persistence optional controls | **PASS** |

The only detailed user failure note: **“伪装后没有复制功能，只能选择保存”** (11). Source audit: after `DesktopImageTools.disguise`, `DesktopImageToolsDialog` computes `static=false` for APNG and disables its `复制结果` control; `另存结果` stays enabled. Desktop `copyDesktopImage` needs an actual file path to expose animation as a clipboard file. This is an APNG Copy/Share parity gap, **not** proof that ordinary privacy stripping itself is broken. Official Android 1.4.1 offers `直接分享` for APNG outputs, using file-type sharing to preserve animation. Remediation scope is recorded separately in `87_PHASE7_APNG_DISGUISE_COPY_PARITY_DEFECT.md`.

Group 15 is explicitly **NOT TESTED** by user (reason not supplied). N-01..04 must remain unsigned; six-width layout test evidence and source Inspector wiring are TECH-EVIDENCE, not actual Windows acceptance.

## Precise mapping and nonclaims

A group PASS cannot prove an optional or conditional operation from `84`: real paid AI generation, actual Enhance/Upscale results/Stop, real message Send, complete APNG upload, true imported role identity/alpha metadata, actual background persistence, exact numeric V5 allowance, or the optional application restart. Do not invent those or repeat frozen B1/B2/C/D/WIN/W2 flows.

**47 new directly covered UI item signatures**, added to the 12 previously signed = **59/115**. Group-to-item mapping:

- 10: `J-01`, `J-03`, `J-06`
- 13: `L-02`, `L-03`, `L-04`, `L-07`
- 14: `L-05`, `L-06`
- 16: `O-01`, `O-02`, `O-03`, `O-04`
- 01: `A-01`, `A-02`, `A-12`
- 02: `A-05`, `A-06`, `A-07`, `A-08`, `A-09`, `A-10`
- 03: `B-01`, `B-02`, `B-03`, `B-04`, `B-07`, `B-08`, `B-10`, `B-11`
- 04: `C-01`, `C-02`, `C-03`, `C-04`
- 05: `D-01`
- 06: `E-01`, `E-03`, `E-04`, `E-06`, `E-07`, `E-09`
- 07: `H-01`, `H-05`, `H-06`
- 08: `G-03`, `G-06`
- 09: `I-05`

**Partial rather than fully signed:** `B-05` (disabled-state Delete not exercised), `B-09` (no actual generation), `D-02/03` (no concrete populated numeric sample), `E-02` (all candidate metadata columns unconfirmed), `L-01` (RUNNING Stop not checked), `P-02` (Guidance runtime not checked).

**No new signature:** K-01..04 because group 11 FAIL; N-01..04 because group 15 NT; I-06/07, J-02/04/05, L-08..11, O-05, P-06/07 where conditional data/model/fixture details are missing. I-05 signs only mode menu visibility, not import durability. Structured positive Prompt I-02/03 remained untested previously.

Reconciled 115-item status: **59 SIGNED / 12 USER-EVIDENCED / 9 PARTIAL / 28 TECH-EVIDENCE / 7 OPEN**. The visually unsigned 56 are not 56 established bugs.

The entire one-launch user checklist is finished. **No more repeated 18-group or piecemeal Windows testing**, no real model calls, no production patch, no upstream sync and no `desktop` merge from this record. Historical Native/HWND and synthetic scroll RED remain historical RED.

**P7 OVERALL: NOT ACCEPTED / NOT MERGED.**
