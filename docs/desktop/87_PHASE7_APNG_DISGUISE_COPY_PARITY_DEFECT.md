# Phase 7 — APNG Disguise Result Copy / File Share Gap (87)

**OPEN — USER GROUP 11 FAIL / SOURCE VERIFIED / NOT MERGED**

## Observation and authority

- Exact user Windows production SHA: `698f0cd55e0ae370650fac012cdb50f0dd7e033f`. User's complete one-launch evidence is `86`; group 11 FAIL detail: **“伪装后没有复制功能，只能选择保存”**.
- Desktop `DesktopImageToolsDialog.kt`: `APNG 伪装` writes APNG bytes into `current`. It calculates `static = DesktopImageEditing.requireStatic(current).isSuccess`. APNG `acTL` is rejected by `requireStatic`, disabling `复制结果` via `enabled = !busy && static`; `另存结果` remains available.
- Desktop `DesktopImageViewer.kt::copyDesktopImage(bytes,file?)`: animated data must have a real readable file path for `javaFileListFlavor`. Simply making the existing disabled button clickable with `file=null` will fail.
- Official upstream `SaltyFishOTL/ChatChatBar` baseline **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**: `ImageMosaicEditor.kt::ApngProcessingResultDialog` offers `保存到相册` and `直接分享`; `ImageActions.kt::shareFromImageLongPress` shares GIF/APNG as **file/octet-stream**, not flattened static bitmap.
- This is a narrow missing **Desktop platform-equivalent share/copy** behavior, not proof of privacy PNG metadata-strip failure. Keep `66` K items unsigned according to `86` and `79`.

## Minimal fix contract

**Goal:** after APNG disguise or restoration, users can copy/share the result as a **file** in addition to saving, without manually saving first. Preserve the actual encoded animation, frames, loop/timing and disguise marker. State clearly that Windows file-paste support depends on the destination app.

Allowed code areas: `DesktopImageToolsDialog.kt`, a narrowly scoped clipboard staging/helper (reuse `copyDesktopImage` when safe), and focused tests. Preserve existing static `复制结果`, Save, undo/redo, Cancel, source immutability and current result error handling.

The file used for Windows clipboard must exist while another app pastes it. Avoid deleting it immediately when the dialog closes or abandoning unbounded temp files. Define bounded retention and safe cleanup with explicit app ownership; never overwrite/delete user images, real data directories, or unrelated temp/cache. Failures must leave the source/result unchanged and report an accurate message.

**Do not:** remove the `static` guard without implementing file copy; convert APNG to static PNG; rewrite the codec, Prompt/Package/Entity/storage contract; change provider API or spend AI credits; rerun Native Robot/HWND or historical synthetic-scroll RED; write upstream or merge `desktop`.

Expected focused evidence: static clipboard path unchanged; exact output bytes preserved in staged .png/.apng file with `acTL`/marker and hash; clipboard exposes valid readable file-list flavor; GIF-derived and restored animations remain animated; file-retention/cleanup safe; JDK17 compile + relevant tests + diff-check. Project must review actual GitHub diff. Later real Windows paste validation, if necessary, is a **focused fix acceptance**, not another 18-group suite.

**P7 FAIL REMAINS OPEN UNTIL REMEDIATED AND REVIEWED.**
