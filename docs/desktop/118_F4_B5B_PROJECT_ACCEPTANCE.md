# F4-B5B Project Acceptance

日期：2026-10-15。裁定：**F4-B5B QUALIFIED ENGINEERING ACCEPTED**，由 Project 独立作出；本文件只登记既有验收，不重跑实现或测试。

## 审查身份与证据

- 源码：`46598a9edec0edbef04c88184020bf16572c05aa`，9 文件，+824/-6。
- 原证据：`a42e5d336fd7f0497f630b9c692e7ce59dbfeaf9`。
- 最终 XML 字节保全证据：`fec4b0c84d76f5b027076d510749c0aec7d36a3f`。
- Project 独立复核 GitHub 真实提交链、九份源码 Git blob、44 份历史和最终 JUnit XML 的 SHA-256 及测试结果。证据目录：`evidence/f4-b5b-derived-safety/`。
- Desktop 最终 **8 suites / 105 tests PASS**；SharedCore 最终 **8 suites / 65 tests PASS**，最终 failures/errors/skips 均为 0。
- 受影响 Desktop、SharedCore、Android 编译 PASS；增量任务的实际执行状态以原始证据为准。
- 历史 RED、编译失败和 XML 换行补正完整保留；不覆盖候选 README、117 原始审计或旧 XML。

## 限定验收范围

严格有界 inventory、数据根 ownership、任务准入、顶层 exclusive 内重新证明与 Repository CAS、提交 receipt 保全获得限定工程验收。只有经严格证明没有受影响可变派生状态的第一类生产场景可取得 VERIFIED_SAFE；预检不能作为长期缓存证明，最终证明至 CAS 的写入屏障必须保留。

已存在 Memory、voice、非空 SaveSlot、未知 RAG/派生关联及不完整任务仍拒绝。APPLIED、STOPPED 部分提交和 RESTORED 均须遵守准入；D-039、notice-only、Conflict/StorageFailure/CommitUncertain 的区分保持。阻塞文件 I/O 无物理绝对超时保证，不将未确认提交当作完整成功或自动重试。

## 未验收边界

Windows 人工、全量回归、打包和实际付费 Provider **NOT RUN / 未验收**；完整 F4 parity、自动修复及正式集成仍 PENDING。正式 validated upstream baseline 仍为 ChatBar 1.4.1，D-036 Prompt 例外不变。D-040 仍为 P11 APPROVED / NOT IMPLEMENTED；P8 Fish S2A、P9/P10、P11 与 Public Preview Gate 不因本验收获得新的完成声明。
