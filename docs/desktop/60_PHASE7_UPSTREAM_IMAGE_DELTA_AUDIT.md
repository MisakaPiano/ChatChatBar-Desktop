# 60 — Phase 7 Upstream Image-Domain Delta Audit

## Audit boundary

Baseline:
`1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

Observed:
`1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`

Range:
18 upstream commits.

This audit classifies every commit in the range. It does **not** promote the validated baseline.

## Commit classification

| Upstream commit | Upstream purpose | P7 disposition |
|---|---|---|
| `354f15166d8bc0462cb87d62a0ba4613794560a3` | 新增生图设定角色位置 | **FORWARD-PORT NOW** |
| `1a4e5a14aa1c741f5288e2ad39d1b9b4344053d9` | 调整生图工作室提示词清除规则 | **FORWARD-PORT NOW**, but use final 1.4.4 semantics after `1874` + `b1bb` |
| `ace632cce58a3b5a57711e31990165d6a14e1c0f` | 优化提示词破甲 | **ALREADY FORWARD-PORTED** as authorized Prompt safety exception (`bc2571e...`) |
| `148b3a9637eadf577afbb4947158dd6f80e17f4f` | 新增图片增强放大与滑动对比 | **FORWARD-PORT NOW** |
| `b6d68320b02a564afe4582bdc5f25978a57f4ea9` | 修复图片元数据与像素隐写泄漏 | **FORWARD-PORT NOW** |
| `01b6b729496fef4ba6114decd799a015d79d13d0` | 添加角色提示词折叠与暂停生图 | **FORWARD-PORT NOW** |
| `c69afe2fe30576ff410b5d060e0d652fc061edd1` | 支持图片角色提示词追加导入 | **FORWARD-PORT NOW** |
| `07ebfaf14a34b82de517b08709589a621b92dcb2` | 朋友圈相册历史与筛选定位 | **P13 Moments**, not P7 |
| `32906a76f2b85fce68285a146092ad91fd3f75e1` | 朋友圈编辑与人物提示词辅助 | **P13 Moments**, not P7 |
| `cb9c0f7ae228174af38e1cdbd6fa0a48d1743f49` | 透明度通道读取生图元数据 | **FORWARD-PORT NOW** |
| `467138a4ff241e95b018ce00d940c339033af8a3` | Release 1.4.2 | N/A release marker |
| `559f83f0c1011a7fa4b8e0f64d5b2ea147544e59` | 段落版本 + 历史图片范围多选 | **FORWARD-PORT image-history part only**; unrelated chat part excluded |
| `26caca0efc46efc8eb738f4e6dbfd022f18aedcb` | Release 1.4.3 | N/A release marker |
| `ed5d12728331eb9af42ac9ebfe60519f3929782a` | 语音容错/重试 | Voice domain, not P7 |
| `1874eb3ee965b232684a205790a8fcc6c1bed9f4` | 角色卡负面词应用/恢复默认 | **FORWARD-PORT NOW**, superseded in UI by `b1bb` final semantics |
| `b1bb01f7f2035b87a9fbb7d54254378df76b420b` | 负面词恢复默认合并到清空 | **FORWARD-PORT NOW — final 1.4.4 behavior** |
| `41476f42b879f08c0378d31f3fde7c7df3cda3ff` | 新建聊天图标区分 | Home UI, not P7 |
| `550409689df8c51f459fb50b4e04c8ac2fa4bf35` | Release 1.4.4 | N/A release marker |

## Official image deltas to port

### A. Character positions — `354f151...`

Current upstream authority:

- `NovelAiCharacterPromptDraft.center: DesignedCharacterCenter?`
- `NovelAiGenerationSettings.useCharacterPositions: Boolean = false`
- AI design does **not** invent centers.
- Position mode is generation-setting ownership.
- V4.5 custom coordinates snap to 5×5 centers:
  `10%, 30%, 50%, 70%, 90%`
- V5 uses continuous normalized 0..1.
- `use_coords` becomes true only when:
  - user opted in;
  - active character list is nonempty.
- Positive and negative character captions use the same center.
- History / draft / metadata import preserve positions.
- Focused Inpaint positions are relative to the request crop.

Desktop requirement:
reuse official policy in shared/JVM-neutral code where possible; Desktop supplies a native dialog/canvas.

### B. Current clear/card-negative behavior — `1a4e5a` + `1874eb3` + `b1bb01f`

Final 1.4.4 behavior, not intermediate behavior:

Character-card Prompt import:
- nonblank card style replaces Studio style;
- card `defaultImageNegativePrompt` becomes Studio base negative;
- blank card negative uses the current app default;
- card character image prompts remain AI Design reference catalog only.

Clear:
- clears style/base/extra/characters;
- clears legacy design inputs/conversion snapshot;
- restores base negative from currently imported card when available;
- otherwise restores app default negative;
- preserves generation settings and reference/guidance state;
- current 1.4.4 has no separate “恢复默认负面词” button: this behavior is merged into Clear.

Do not change Prompt literal text.

### C. Image Enhance / Upscale — `148b3a9...`

Official current user function.

Image Tools exposes one Enhance/Upscale workflow with:
- Enhance tab
- Upscale tab
- before/after draggable comparison
- retained successful result per tab
- cancellable work
- result save/share/apply-as-new-tool-input
- no mutation of Studio draft/history merely by postprocess

Enhance:
- recognized full-model NovelAI metadata required;
- 1× / 1.5× / 2× / Max where allowed;
- strength/noise;
- uses request-only enhance options;
- Max geometry follows official current policy;
- cost uses official current policy.

Upscale:
- POST `/ai/upscale`
- provider model string `nai-diffusion-5-curated`
- sigma 0
- 2× output
- current official pixel/cost tiers
- no automatic retry stacking.

This hardcoded Curated use belongs specifically to the official Upscale protocol and does **not** authorize general Curated model support in Studio.

No real provider call is required for P7 validation.

### D. Privacy export — `b6d683...`

Official safety fix.

“完成” and “去除元数据” image-tool exports must remove:
- container metadata;
- recognized and unrecognized LSB carrier payload bits used by NovelAI stealth metadata.

Output:
- privacy PNG copy;
- source retained;
- minimal orientation only where needed;
- no silent animation flattening.

Desktop implementation may use AWT/JVM adapters, but the safety policy must be equivalent.

### E. Character enabled/pause — `01b6b72...`

This is **official current upstream**, not a Desktop invention.

Data:
`NovelAiCharacterPromptDraft.enabled: Boolean = true`

Authority:
`activeCharacters = characters.filter { enabled }`

Only active characters participate in:
- final request plan;
- token counting;
- character-limit validation;
- position generation;
- explicit Studio-Prompt attachment to AI Design.

Disabled characters remain in:
- draft;
- order;
- Prompt text;
- negative;
- center;
- history/undo.

Old payload default = enabled.

The current Android UI conflates visual fold and enable. Desktop will preserve the official `enabled` semantic but separate visual collapse from it; see `62`.

### F. Metadata character import mode — `c69afe2...`

Official current modes:
- OFF
- REPLACE
- APPEND

APPEND:
- preserves existing role IDs/order/enabled/centers/content;
- appends new imported enabled roles;
- one undo transaction.

Missing character metadata:
- no-op.

Explicit empty character list:
- clears only in REPLACE mode.

### G. Stealth alpha metadata read — `cb9c0f7...`

`NovelAiPngMetadataReader` must:
- prefer usable ordinary PNG Comment/Source;
- if missing/invalid, try alpha-LSB:
  - `stealth_pngcomp`
  - `stealth_pnginfo`
- column-major pixels;
- MSB-first;
- unsigned 32-bit bit length;
- bounded metadata and inflation;
- read original full pixels without modifying source;
- share the same metadata authority for Studio import / regeneration / Enhance.

Desktop needs equivalent pixel extraction.

### H. History range selection — image part of `559f83f...`

Official current behavior:
- history remains newest-first;
- grid tiles;
- long-press selects tile/album;
- repeat long-press on fully selected tile marks one range start;
- next click selects inclusive visible range;
- folded albums select all members in visible order;
- previous selection order retained/deduped;
- anchor is one-shot;
- filter/group/history refresh/back/reset clears pending range anchor.

Desktop should use mouse-equivalent interaction:
- right-click/long-press equivalent is not mandatory;
- Shift-click is a reasonable Desktop equivalent **if** the same range policy is preserved;
- range-start indication remains explicit.

## Already handled

`ace632c...` Prompt safety literals were explicitly authorized and already forward-ported earlier. Do not re-edit them.

## Not image-domain P7

Moments, voice, Home icon/release markers remain with their existing owner phases.

## Baseline rule

After these selective ports:
- `10_UPSTREAM_BASELINE.json` remains 1.4.1 until a formal sync/validation window.
- `14_UPSTREAM_COMPAT.md` must record each selective official image forward-port.
- Do not claim whole-repo 1.4.4 compatibility.
