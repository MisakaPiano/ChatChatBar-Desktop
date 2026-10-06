# 46 — Phase 7 Image-System Re-audit Matrix

Targeted vertical review against formal CCB 1.4.1 after the first Phase-7 manual acceptance exposed that horizontal feature completion did not prove end-to-end workflow parity.

Inspected Desktop: `feature/phase7-image-novelai @ 109403ca1dc1c037a85943985cd13981733aa58a`.

The original findings below are retained as the dated audit snapshot. The additive R2 response is recorded in the closure table at the end; do not interpret the old GAP labels as a later source inspection.

Audit labels here are not FEATURE_PARITY statuses:
- `CORE-PASS`: semantic/runtime core already reviewed and still sound.
- `R2-PRESENT`: current R2 implementation is visible in source; validation/manual acceptance pending.
- `GAP`: formal-baseline behavior missing or not wired.
- `VERIFY`: implementation exists but needs focused evidence.

| Workflow | Upstream authority | Current Desktop finding | Status / action |
|---|---|---|---|
| owned image lifecycle | image runtime/repos | strict authority + exact cleanup reviewed | CORE-PASS |
| Character images | Character editor/entity | first manual pass succeeded | CORE-PASS |
| crop/pan/wheel/recrop | image workspace | first manual pass succeeded | CORE-PASS |
| CCB PNG core | renderer/export | first manual pass succeeded | CORE-PASS; keep R2 refinements |
| composer attachment entry | `ChatComposer(onImage)` | R2 moved entry into composer | R2-PRESENT |
| multiple pending images | `selectedImages` list, repeated picker | Desktop still explicitly rejects second pending image | **GAP: support list; keep first-image request policy** |
| expanded composer images | fullscreen composer accepts image list | needs final R2 verification | VERIFY |
| edit-message image list | edit flow owns `editingImages` add/remove | Desktop `editMessage(id, content)` edits text only | **GAP: add/remove image list + safe cleanup** |
| direct multimodal | request policy | reviewed | CORE-PASS |
| linked vision | image understanding service | reviewed | CORE-PASS |
| Assistant manual Generate Image | `onGenerateImage` → `generateNovelAiImage` | `generateFromAssistant()` runtime exists but no UI call found | **GAP: wire message action** |
| per-run `生图要求` | upstream secondary generate dialog | no Desktop equivalent found | **GAP** |
| image task anchored to source message | `NovelAiGenerationCard` near anchor | TaskRuntime has targetMessageId; panel still shows generic NAI task near composer | **GAP: anchor UI to message** |
| automatic image policy | `AutomaticChatImagePolicy` | deterministic policy / no AI judge reviewed | CORE-PASS |
| automatic-image discoverability | chat/session UI | R2 compact image menu present | R2-PRESENT |
| generated-image regeneration | regeneration dialog | Desktop edit/regenerate exists | R2-PRESENT |
| individual chat-image deletion | generated/plain image action flow | no individual Desktop deletion path found | **GAP** |
| generated-image source linkage | `generatedFromMessageId` | reviewed | CORE-PASS |
| Studio one-workspace | `ImagePromptToolScreen` | R2 one workspace + result pane | R2-PRESENT |
| direct Studio Guidance action | Studio top-bar Guidance icon | R2 toolbar lacks direct Guidance; only lower/use-as route | **GAP: add direct Guidance entry** |
| image/history Use-as Guidance | Studio/history | R2 current-image/history path exists | R2-PRESENT |
| AI Design | `NovelAiDesignScreen` | R2 auxiliary conversation/settings/history present | R2-PRESENT |
| Prompt/tag authority | prompt designer/tag services | exact/shared authority reviewed | CORE-PASS |
| account load on Studio entry | Studio VM/screen | Desktop `load()` does not fetch account | **GAP** |
| V5 allowance display | account usage / Studio top bar | no equivalent V5 approximate display found | **GAP** |
| post-success local/reconcile account state | Studio account policy | no equivalent Desktop update found | **GAP** |
| immediate cost estimate | cost estimator | Desktop displays estimate | R2-PRESENT |
| generation settings | Studio | R2 kept in workspace | R2-PRESENT |
| continuous generation | Studio options | runtime exists | VERIFY UI |
| current/recent result | Studio output | R2 right pane exists | R2-PRESENT |
| History search | `NovelAiHistoryFilterPolicy` | no Desktop search UI found | **GAP** |
| History date filter | `NovelAiHistoryDateFilter` | no equivalent found | **GAP** |
| History folding/albums | `NovelAiHistoryFolding` | no Desktop fold UI found | **GAP** |
| History detail recipe | History detail dialog | Desktop shows limited model/seed/actions | **GAP** |
| History full/new-seed/seed-only | apply modes | Desktop supports three modes | CORE-PASS / UI VERIFY |
| missing-guidance exact reproduction | formal full-reproduction protection | Desktop applies then warns source missing | **GAP: block/confirm before degraded apply** |
| History Use-as | history screen | Desktop present | R2-PRESENT |
| History delete | history policies | Desktop exact-image multi-select delete exists | VERIFY equivalent ownership |
| current-image workspace | Studio tools | R2 current-image surface exists | R2-PRESENT |
| metadata import | PNG metadata | implemented | R2-PRESENT |
| reverse Prompt runtime | prompt-tool reverse pipeline | implemented | CORE-PASS |
| reverse stage/result/reasoning UI | formal tools dialog has separate status/result/reasoning | Desktop collapses content into status; no reasoning state found | **GAP** |
| reverse Stop/retry/apply | tools | present | R2-PRESENT |
| img2img/Precise/Vibe/inpaint | Guidance policies | semantic/runtime reviewed | CORE-PASS |
| Focus/mask undo | canvas policies | reviewed/focused tests | CORE-PASS |
| mosaic/tools | image processing | present | R2-PRESENT |
| APNG | codec/playback | reviewed | CORE-PASS |
| save/copy/reveal | image actions | present | R2-PRESENT |
| background quick control | chat/background | R2 present | R2-PRESENT |
| Desktop background library | downstream private state | strict private JSON + owned paths present | R2-PRESENT; final safety/manual review |
| background fallback precedence | session→Desktop preferred→official | current R2 implements | R2-PRESENT |
| new-chat Character preview | Desktop presentation | R2 intended/current source requires final check | VERIFY |
| Management Start Chat | existing session creation authority | R2 intended/current source requires final check | VERIFY |
| SecretStore | DPAPI | reviewed | CORE-PASS |
| real NAI safety | guarded smoke | remains 1/8; R2 must add 0 | CORE-PASS |

## Major conclusion

The first Phase-7 manual failure was not only presentation polish. The vertical review found formal user behaviors omitted by the first horizontal parity pass.

Highest-priority gaps:
1. multiple chat attachments;
2. editing image lists in existing messages;
3. Assistant-message manual Generate Image wiring;
4. per-run `生图要求`;
5. source-message-anchored image-task UI;
6. individual chat-image deletion;
7. direct Studio Guidance entry;
8. normal Studio account/V5 allowance state and reconciliation;
9. History search/date/fold/detail and exact-reproduction protection;
10. reverse-Prompt multi-stage/reasoning presentation.

Rows marked GAP belong to Phase-7 R2 scope rather than later polish.

Future upstream image syncs should update this matrix rather than recreate the audit from scratch.

## Additive R2 implementation / validation response

Current status: **PROJECT R2 HOLD / NARROW R1 IN PROGRESS** following review of `8a0d206` / source `1843dac`. The previous 969/685/9 tests and package/smoke remain historical evidence. Project accepted most closure but requires correction of missing-guidance reuse, model-filtered Use-as, terminal task controls and inactive Guidance labeling. Final R1 source, tests and replacement artifact are recorded in `44_PHASE7_R2_REVIEW.md`; user acceptance remains pending.

| Amendment / original GAP rows | Connected Desktop path | Focused verification |
|---|---|---|
| A1 multiple pending images; expanded-composer VERIFY | `pickImage` appends; inline/full composer use `DesktopPendingImageStrip`; accepted send persists the list | Repeated picks/removal/image-only persistence; direct multimodal and linked vision each persist two images while sending exactly one authoritative USER image |
| A2 edit-message image list | Edit modal retains existing references and transient additions; `DesktopChatImages.editMessage` publishes metadata/references before exact cleanup | Restart, obsolete candidate removal, retained metadata, stale/corrupt authority and indeterminate post-write retention |
| A3 Assistant manual Generate | `PrimaryMessageBubble` → `DesktopMessageImages` → `generateFromAssistant` | Real UI/service call-chain assertion and task admission; the invocation already existed in preserved R2 source, so no duplicate runtime was added |
| A4 per-run requirements | Secondary `生图要求…` → existing shared Designer `imageContentHint` / `finalPromptRequirement`; Generate saves only session preference; Cancel discards | One-run hint exclusion from persisted session, unrelated-field preservation, temporary preference option, shared-builder argument mapping |
| A5 source-message tasks | Manual/regeneration and automatic launches carry `targetMessageId`; message renders status/Stop/terminal; composer shows unanchored fallback only | Source/session/kind filtering and automatic launch anchor assertion |
| A6 individual chat-image deletion | Per-image confirmed action → `deleteImage`; path/metadata removal or last-empty-message deletion precedes cleanup | Generated image removal, last empty derived-message deletion, source reply retained |
| A7 direct Guidance | Studio toolbar `图像引导` plus Current/History Use-as actions → same guidance editor | Direct entry wiring plus existing local guidance-copy tests |
| A8 entry account / V5 allowance / post-success reconcile | `load` fetches account; toolbar Anlas/approximate V5; shared `NovelAiAccountUiState` local debit/reconcile; fetch failure visible | Injected fake account fetch, stale server response, acknowledged spending, error redaction/no false free; Android state tests |
| A9 History search/date/folding/detail/missing-guidance protection; reuse/delete VERIFY | `DesktopStudioHistory` uses shared positive filter/date/album policy; atomic per-depth preferences; full recipe detail; exact selection deletion. R1: missing-source FULL disabled, NEW_SEED and SEED_ONLY use shared warning confirmation; current/history Use-as filtered by selected model | Search excludes negative text, dates/albums/preferences/detail; R1 three-mode missing-source matrix and both model target sets; existing delete/deep-copy tests; Android filter tests |
| A10 reverse stage/content/reasoning | Existing shared callbacks → separate `reverseProgress`; Current Image shows full content, reasoning disclosure, stage, Stop/retry/candidate/Apply | Full content retained beyond prior 2 KiB tail, independent reasoning/stage, actual panel bindings |

Remaining VERIFY rows are exercised by the original R2 focused scene/controller/background/character tests and final Desktop full suite. Continuous controls remain in the main generation inspector; character summary and Start Chat use the existing session controller. Shared history/account declarations are exact extractions from formal `5e76a9c`; Android's history scope check uses equivalent null-safe access because the property is now cross-module. Prompt, Package, Entity, HTTP and automatic eligibility authority are unchanged. Real NovelAI count stays **1/8** with **0** added requests.
