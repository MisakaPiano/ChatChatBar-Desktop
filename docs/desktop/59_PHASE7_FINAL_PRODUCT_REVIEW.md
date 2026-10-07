# Phase 7 Final Product Closure evidence

Start: `a0918deab8348ed0c2c70442b08e9312b28de247`, same `feature/phase7-image-novelai` branch. Status: **IN PROGRESS / NOT ACCEPTED / NOT MERGED**.

## Scope and formal mapping

The third manual UX HOLD supersedes presentation-ready status in `53`; that document and its package are historical evidence. This closure follows user-authorized `54` / `56`. `55` is the explicit deferred owner map. No Phase 8 or merge.

| Formal CCB 1.4.1 owner | Desktop adaptation |
|---|---|
| `NovelAiDesignScreen` reply card and conversation | `DesktopDesignField` retains TextFieldValue selection/composition and delayed echoes; structured target/base/ordered-character cards with per-module Copy; existing Apply/Retry/Regenerate/Edit-and-branch callbacks |
| `ImagePromptToolScreen.CharacterCardImport` | Formal label and purpose description, card-name selector, no internal reference-source list exposed |
| `ImagePromptToolScreen` dynamic Generate control | Shared `NovelAiImageCostEstimator` result only; free/Anlas/encoding/Vibe labels, busy progress and unavailable state |
| `ImagePromptToolScreen.OutputPanel` | Adaptive aspect-fit result viewport, lazy horizontal current/recent history filmstrip, selection/viewer, same History reuse callbacks |
| Studio Prompt/token/status controls | Three action levels, recognizable icons, grouped account status, two token bars using existing tokenizer counts |
| Chat composer/session settings | Attachment/fullscreen/send action rail; 112dp previewable attachment strip with hover ×; Basic/Context/Images/Advanced settings tabs and global save/cancel footer |
| Desktop diagnostic tool | Tools → Advanced / Diagnostics → Main-chat Prompt Inspector; retained inspector runtime |

Formal authority remains `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; approved `ace632c...` safety exception remains unchanged. Current runtime, repository, Prompt Designer and HTTP generation ownership is retained. No sharedCore/Android production changes are intended.

## Failure and editor boundary

`DesktopDesignInput` keeps selection/composition across recomposition and queued local repository echoes, replacing text only on a distinct external authority update. New-conversation/turn identities isolate editors. Enter and Shift+Enter insert newlines; Send explicitly submits. Normal keyboard/IME candidate confirmation never sends a design request.

Failure UI uses fixed categories: model unavailable, authentication, network, provider, malformed/incomplete response, cancelled, unknown. Shared transport's fixed HTTP status prefix is inspected without publishing its body. Shared terminal refusal and cancellation types survive the adapter, preventing a new fallback/retry path. The controller writes only safe category text to failed turns; failed design never applies to the Studio draft. Retry and branch still call the existing shared conversation repository and turn runner.

Background remains immediate-save via the existing controller; tab switching never owns a new draft. Other settings stay in the controller's session draft and keep Save/Cancel/leave confirmation behavior. The Desktop-private character background library and Entity/Package meaning are unchanged.

## Local functional acceptance

`runPhase7FinalProductFixture` is a test-classpath-only manual window. It uses a new temporary profile, empty in-memory SecretStore and a fake text transport through the real shared Designer, turn runner and repositories. No actual design-model/image transport is selected. Open AI Design, enter Chinese, select SUCCESS, send, Copy a module, Apply, edit/branch; choose an error category, send and Retry after returning to SUCCESS. The original Studio draft remains intact on failure. Closing removes only this fixture's temporary profile.

The production EXE has no fixture mode. Actual Windows IME manual acceptance remains user-owned in `57`; automated TextFieldValue composition/selection checks are not an OS-IME signoff.

## Validation

Focused **PASS: 5 suites / 77 tests / 0 failures / 0 errors / 0 skips**, including 15 Final Product tests. Desktop affected compile PASS; BUILD SUCCESSFUL in **55s**. Evidence: `app/desktopApp/build/phase7-final-product-evidence/focused-final/` (XML/counts/log).

Coverage: composition/selection/delayed local echo vs external replacement; fake auth/network/provider/malformed/incomplete/terminal classification with no error body/cause leak; shared auxiliary envelope equality and terminal refusal/cancellation; shared design runner success/Copy/Apply/retry/branch/regenerate; cost/encoding/Vibe labels and token thresholds; current/recent order and wheel/selection; 1280/1600/700 layouts; 112dp hover removal/preview/zero-height empty; tab keyboard navigation, preserved draft and background/opacity persistence after Cancel; existing chat/controller/fullscreen regressions.

Development findings were investigated: status-prefix regex escaping was corrected; invalid empty-message error fixtures now use valid auxiliary envelopes; generated role IDs and revision/time stamps are checked by behavior rather than independent constructor equality; the session fixture creates a real persisted session; Copy navigation traverses selectable text; the old collapsed-layout source guard now checks the always-visible action rail. A test returning an exception value was corrected to Unit for JUnit discovery. No production check was waived. Initial failed XML/log: `focused-first-failed/`; compile/development logs remain `app/desktopApp/build/phase7-final-product-*.log`.

Rendered local fixture evidence (no generated account image):

- [Studio 1280](refs/phase7-final-product/validation/studio-1280.png), [1600](refs/phase7-final-product/validation/studio-1600.png), [700](refs/phase7-final-product/validation/studio-700.png)
- [Structured design](refs/phase7-final-product/validation/structured-design.png), [filmstrip after scroll](refs/phase7-final-product/validation/filmstrip-scrolled.png)
- [Session image tab](refs/phase7-final-product/validation/session-images.png), [keyboard tabs](refs/phase7-final-product/validation/session-tabs.png), [attachment hover](refs/phase7-final-product/validation/attachments-hover.png)

Full Desktop rerun, replacement package and launch smoke: pending. Shared/Android production diff is empty; conditional affected shared/Android gates are N/A.

## Deferred / safety

`55` assigns provider drift to `49` + `14` + release gate `20`; complete Studio presets and global IA to P17; Moments to P13; Community P14; OS/installer integration P15. This task implements none of them. No model/sampler/API capability, pricing algorithm, tokenizer, Prompt literal, Entity/Package, History/Guidance or automatic eligibility changes.

NovelAI real generation remains **1/8**, this task **0 additional requests**. All new functional validation is local/fake. The old acceptance package is preserved; a new isolated package will be recorded here.
