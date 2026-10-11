# Phase 7 W3 — Image Import Entry User Acceptance and Structured Prompt Source Audit

**W3 I1 USER WINDOWS PASS / I2 + I3 NOT TESTED / PHASE 7 NOT ACCEPTED**

## Identity and evidence ownership

- User Windows acceptance source: `50a7caf573827b402ddc16622941c2f18fcd3e12`; current feature branch `feature/phase7-win-auxiliary-ux`.
- User report date: **2026-10-12**.
- User gave the following explicit results: **I1 PASS, I2 未测, I3 未测**. Project did not operate the GUI. No separate proof of user clipboard interaction, role metadata import mutation, network use, screen capture or display dimensions was supplied.
- This is a docs-only evidence record. Production source, tests, upstream baseline, signed slices and protected branches remain unchanged.

## User check and `66` mapping

| W3 user check | Original Phase-7 checklist items | User outcome | Documentation decision |
| --- | --- | --- | --- |
| I1 — 图片 metadata 导入入口 | I-01 no isolated PNG metadata import button in the main Studio Prompt; I-04 metadata selection in the imported-image tool | **PASS** | **I-01 and I-04 SIGNED** |
| I2 — 结构化 Prompt 复制与粘贴 | I-02 copy structured positive Prompt; I-03 paste structured positive Prompt | **NOT TESTED** | I-02/I-03 **not signed** |
| I3 — 角色 metadata 导入模式 | I-05 role metadata OFF/REPLACE/APPEND | **NOT TESTED** | I-05 **not signed** |

I-06 role ID/enabled/center preservation and I-07 ordinary/alpha-stealth metadata decoding keep their pre-existing technical-evidence status; I1 PASS does not prove actual imports/mutations.

## Verified implementation: structured positive Prompt actually exists

Project independently inspected the exact accepted production tree at `50a7caf...` before making any acceptance claim:

- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopNovelAiStudioPanel.kt`, Prompt section lines 94–100: visible actions **`复制正向 Prompt`**, **`粘贴覆盖`** and `清空`. Copy calls `desktopCopyText(d.copyPositivePrompt())`; Paste reads OS string clipboard and calls `controller.replace { NovelAiStudioPromptClipboard.apply(text, it) }`.
- `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/image/NovelAiStudioPromptClipboard.kt`: `encode` emits readable `【基础】`, optional `【画风】`/`【补充】`, and ordered `【角色 N】` sections. `apply` parses/validates this format and retains legacy `chatbar-positive-prompt-v1` JSON support. This is an editor interchange format, **not** the serialized model request or an authorized official Prompt-text change.
- `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/image/NovelAiStudioModels.kt`: `copyPositivePrompt()` delegates to the shared encoder. By default `copyPositivePromptIgnoreStyle = true`, configurable through Studio Settings **`复制时忽略画风`**. Negative Prompt is not copied as a positive Prompt.
- `app/app/src/test/java/com/example/chatbar/domain/image/NovelAiStudioModelsTest.kt`: tests exist for copy/apply round-trip, multiline/escaping, optional style, unchanged negative/settings, legacy JSON and malformed input/role-count rejection. These are **existing code and test cases**; no new test run or final-source all-green result is claimed.

Thus the old `66` English checklist wording `Copy structured positive Prompt` and `Paste structured positive Prompt` is a **behavioral description, not the literal button label**. It was incorrect to ask the user to find those English-labelled controls without first providing the real UI entry point.

Implementation/source evidence moves I-02 and I-03 from `OPEN` to `TECH-EVIDENCE` in `79`; it must not turn the user's I2 **NOT TESTED** into PASS. If later checked manually, use a disposable Studio draft because Paste is intentionally an overwrite operation.

## Phase boundary

- No new Codex request, Gradle build, production/source modification, upstream sync, paid AI/NovelAI request or data migration is performed by this docs-only audit.
- Original D HWND/Chrome and WIN synthetic scroll RED remain historical RED, unaffected by this W3 result.
- Only I-01/I-04 are newly signed in `66`. The other 103 checkboxes retain their actual evidence statuses. Phase 7 **IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP**.
