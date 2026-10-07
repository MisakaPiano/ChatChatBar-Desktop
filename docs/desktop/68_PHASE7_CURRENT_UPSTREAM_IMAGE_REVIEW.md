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
| `148b3a9` | Enhance models/options and Upscale protocol; Desktop product wiring still pending |
| `b6d6832` | Shared privacy PNG encoder and container stripper; Android and Desktop raster adapters |
| `01b6b72` | Persisted enabled roles, active-character request/validation/token ownership |
| `c69afe2` | OFF/REPLACE/APPEND metadata role import |
| `cb9c0f7` | Bounded alpha-stealth decoder, ordinary valid metadata precedence, platform original-raster callbacks |
| `559f83f` | Shared History visible-album range-selection policy; Desktop grid wiring still pending |

`ace632c` safety exception already exists and is unchanged. Release, voice, Moments feature changes,
home changes and other Prompt drift remain excluded. The Moments import change only routes its existing
metadata read to the Android raster adapter after shared extraction.

Focused validation: shared 7 suites / 35 tests; Desktop 2 suites / 23 tests; Android 1 suite / 30 tests.
All passed, zero failures/skips. Desktop/shared/Android affected compiles passed. These are core-focused
results, not final closure evidence. Logs: `app/desktopApp/build/phase7-current-upstream-core-focused.log`.

## Still required

All remaining product wiring in 62–63 (AI Design preflight/chat, role cards/positions, tag caret,
postprocessing, ingress/APNG, viewer, History/result pane and auxiliary-window shell), corresponding
integration tests, final full regression, new isolated distributable/blank-profile launch smoke,
CURRENT documentation reconciliation and completed review evidence.

Manual acceptance checklist: `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`; not yet accepted.
