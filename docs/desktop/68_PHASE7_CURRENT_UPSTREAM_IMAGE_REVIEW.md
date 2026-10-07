# Phase 7 current-upstream image closure — in progress

Start: `d9696f0a4e54fb5820118cbff8ecba8f33b3b27b`, same feature branch.
This is selective image-domain adoption, not complete 1.4.4 compatibility or baseline promotion.
Formal baseline stays `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
No real generation, Enhance, Upscale or AI Design request has been made. Live generation remains **1/8**.

## Core checkpoint

The following official changes are now shared JVM-neutral policy with Android/Desktop adapters:

| Official commit | Adoption |
|---|---|
| `354f151` | Character-position policy, defaults and positive/negative request coordinates; no identity-reminder Prompt adoption |
| `1a4e5a`, `1874eb3`, `b1bb01f` | Final current clear/card-negative behavior; intermediate behavior is superseded |
| `148b3a9` | Enhance models/options and Upscale protocol; Desktop product wiring complete in the following product checkpoint |
| `b6d6832` | Shared privacy PNG encoder and container stripper; Android and Desktop raster adapters |
| `01b6b72` | Persisted enabled roles, active-character request/validation/token ownership |
| `c69afe2` | OFF/REPLACE/APPEND metadata role import |
| `cb9c0f7` | Bounded alpha-stealth decoder, ordinary valid metadata precedence, platform original-raster callbacks |
| `559f83f` | Shared History visible-album range-selection policy; Desktop adaptive grid / Shift-range adapter complete |

`ace632c` safety exception already exists and is unchanged. Release, voice, Moments feature changes,
home changes and other Prompt drift remain excluded. The Moments import change only routes its existing
metadata read to the Android raster adapter after shared extraction.

Focused validation: shared 7 suites / 35 tests; Desktop 2 suites / 23 tests; Android 1 suite / 30 tests.
All passed, zero failures/skips. Desktop/shared/Android affected compiles passed. These are core-focused
results, not final closure evidence. Logs: `app/desktopApp/build/phase7-current-upstream-core-focused.log`.

## Product checkpoint — implemented, final full gate pending

Desktop product adapters now cover 62–63: exact-model/hydrated-credential preflight, conversational
AI Design with structured modules and bottom composer, independent role collapse/enable and position
canvas, V5 battery, caret-only full-tag inspection, imported-image postprocessing/privacy flow,
window-local paste/drop, ordered multi-picker/reorder/canonical APNG restoration, common viewer and
resizable auxiliary windows, newest-result/right-pane modes, adaptive History grid and explicit range
anchor, bottom-right composer/message image actions and same-row far-right Diagnostics.

Core checkpoint: `6329c5b`. Product focused gate: Desktop 8 suites / 81 tests; shared caret 1 / 2,
zero failures/errors/skips. Additional layout gate checks chat action bounds and the visible one-shot
range anchor. Integration tests use fake HTTP and local PNG/WebP/APNG fixtures, including cancellation,
no retry stacking and actual in-memory SecretStore repository hydration. No real API request.

Still required before readiness: final-source shared/Desktop/Android full regression and compiles,
new isolated distributable and blank-profile launch smoke, then CURRENT documentation reconciliation.
Manual checklist `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` remains pending; no acceptance is claimed.
Final product checkpoint validation: Desktop 3 suites / 18 tests PASS, zero failures/errors/skips; affected compile PASS. This includes final source changes and the new composer/message action bounds test. Final full regression follows on the committed source.
