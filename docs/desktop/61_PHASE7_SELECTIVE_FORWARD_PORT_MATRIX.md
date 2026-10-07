# 61 — Phase 7 Selective Forward-Port Matrix

This is the implementation matrix for official 1.4.1→1.4.4 image changes.

| Capability | Upstream source | Desktop target | Parity target |
|---|---|---|---|
| Character center data | `354f151` | sharedCore exact data/policy | EXACT |
| `useCharacterPositions` | `354f151` | shared settings/request path | EXACT |
| Position editor | `354f151` | Desktop native dialog/canvas | EQUIVALENT |
| Card negative on import | `1874eb3` → final `b1bb01f` | shared Studio draft/import | EXACT |
| Clear final semantics | `b1bb01f` | shared clipboard/clear + Desktop UI | EXACT |
| Enhance policy | `148b3a9` | shared JVM policy + Desktop adapter/UI | EXACT/EQUIVALENT |
| Upscale request protocol | `148b3a9` | shared/JVM HTTP service | EXACT |
| Before/after comparison | `148b3a9` | Desktop mouse-friendly comparison | EQUIVALENT |
| Privacy PNG export | `b6d6832` | shared pure policy + Desktop raster adapter | EQUIVALENT with safety equivalence |
| Character enabled | `01b6b72` | shared draft/recipe/request | EXACT |
| Character visual collapse | user-approved Desktop behavior | Desktop presentation only | EQUIVALENT enhancement |
| Metadata OFF/REPLACE/APPEND | `c69afe2` | shared import policy + UI | EXACT |
| Alpha stealth metadata read | `cb9c0f7` | shared parser + Desktop pixel adapter | EQUIVALENT/EXACT protocol |
| History range select | `559f83f` | shared policy + Desktop Shift/range UI | EQUIVALENT |
| Prompt safety literals | `ace632c` | already present | EXACT selective forward-port |

## Source-sharing rule

When upstream logic is JVM-neutral:
- move/share it in `sharedCore`;
- do not keep a second Desktop policy.

When Android source depends on Android Bitmap/Context:
- extract the pure policy/protocol;
- implement only the raster/file/dialog adapter on Desktop.

Examples:
- character position normalization → shared exact
- metadata merge → shared exact
- range selection → shared exact
- postprocess cost/geometry → shared exact
- privacy bit-clearing policy → shared exact if practical
- Android Bitmap decode/save → platform adapter

## Serialized compatibility

Official current fields have defaults:
- character `enabled = true`
- character `center = null`
- generation setting `useCharacterPositions = false`

Do not create a new Package schema for these Studio-private persisted draft/history fields.

Old 1.4.1 Desktop data must decode with official defaults.

## Request truth

For request-affecting forward ports:
- final serialized NovelAI request is truth;
- UI labels are not proof;
- request-shape tests must cover enabled roles and coordinates;
- disabled roles must be absent from positive and negative character arrays;
- use_coords false unless explicitly enabled and there is at least one active role.

## No general provider expansion

Do not change:
- Studio model enum;
- sampler enum;
- model-specific sampler filtering;
- general Curated support.

The current official Upscale endpoint's `nai-diffusion-5-curated` string is an isolated current-upstream protocol detail, not a Studio capability declaration.
