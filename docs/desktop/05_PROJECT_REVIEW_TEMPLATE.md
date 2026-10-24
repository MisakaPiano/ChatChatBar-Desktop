# Project implementation audit

依据 [01_PROJECT_INSTRUCTIONS.md](01_PROJECT_INSTRUCTIONS.md) / D-046，在实现后阶段性审计真实 commit/PR diff；不充当下一 slice、跨 phase、测试、集成或 Preview 发布的前置批准。

检查目标行为、CCB Package/Entity/最终 Prompt 语义、平台适配、与改动相称的验证、CURRENT/parity/compat 准确性。复用有效历史证据，不重复完整审计或无关测试。

输出：
- 已审范围与实际依据；
- 可复现缺陷及具体修复建议；
- 可留已知问题；
- 需用户做的必要人工验收；
- 审计结论：PASS / PASS WITH FOLLOW-UP / CHANGES REQUIRED。

CHANGES REQUIRED 描述缺陷，不自动撤销执行授权；Codex 直接修复并继续。不要把“未经 Project 签字”作为技术缺陷，不冒称未实施/未测试内容已通过。
