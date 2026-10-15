# P11-S0 Project Acceptance

日期：2026-10-15。**READ-ONLY SAVE SLOT INSPECTOR — QUALIFIED ENGINEERING ACCEPTED**，由 Project 独立裁定。本文件仅登记既有验收，不重新实现或运行已验收测试。

## Source and evidence

- 初始源码 `d93eaf51d9ac13cd29cc97fe1b8b13e8a7d6a50a`；初始证据 `16b7fdea9d78d098ae2986675f4fef4dc70ad82a`。
- R1 源码 `e8855db66e2669cb089eca3935e8f2b2bfe83f5d`；R1 证据 `94310f85549d3be906d70553ecfbba7e81f8d58a`。
- Project 已独立核对 R1 两份源码 Git blob、原始 XML 和日志 SHA-256，以及官方 1.4.1 ChatViewModel 的 `audioPath → voice.id` 优先级。
- 初始限定验证：Desktop 4 suites / 48 tests、SharedCore 3 suites / 8 tests、Android 1 suite / 7 tests PASS。
- R1 实际执行：Desktop Inspector 1 suite / 23 tests PASS，0 failures/errors/skips；SharedCore / Android 复用前证据，未重新执行，不混计本轮数量。
- 原始证据在 `evidence/p11-s0-inspector-20261015/` 与 `evidence/p11-s0-r1-legacy-audio-20261015/`；历史 HOLD、SDK/编译 RED、筛选器记录及全部原始证据保留。

## Qualified boundary

验收范围为有界外部文件只读检查器和 OPEN picker adapter，**不是导入或恢复**。官方 SaveSlot Entity 零差异移至 sharedCore（Git blob `02bbd09d50dc501947f4b831ba6a8213d73418de`）；Android 继续使用单一共享 schema，平台恢复逻辑未迁入 Desktop。

合成 legacy 1–7 与 v8 ZIP 覆盖内容识别、严格 JSON/JSONL、CRC、声明数量、身份/版本/媒体引用、资源和分配预算、取消与源文件 SHA-256 保全。R1 为 schema 5–7 补齐 legacy 语音关联 fallback，保留 path 优先、既有身份校验及 v8 独立音频校验。schema 1–4 仍有保守兼容限制，不声明完整旧版恢复 parity。Base64 只作有界语法检查，不解码/播放，不证明 codec 有效。

所有夹具均为 SYNTHETIC，**不是经官方 Android 导出器产生的 Golden**。正式管理页 UI、真实 Windows 人工、Android ↔ Desktop 真实往返、导入恢复、全应用备份和完整 P11 仍 PENDING；`imported=false`、`desktopCanRestore=false` 不变。P9 索引发布、P10、Fish、D-040 实施与 Public Preview 状态不提升。正式 validated upstream baseline 仍为 1.4.1，Prompt/D-036 不变。
