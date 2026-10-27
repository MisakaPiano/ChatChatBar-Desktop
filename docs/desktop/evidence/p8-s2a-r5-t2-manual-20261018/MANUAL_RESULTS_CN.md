# T2 实际人工验收记录（部分完成后暂停）

用户文字与9张用户提供截图；没有自动截图。完整项目保守统计：4 PASS、1 FAIL、20 NOT TESTED。部分子项通过而完整流程未完成的行仍为NOT TESTED，不代表用户完全没有操作。

| ID | 状态 | 实际观察／证据与限制 |
| --- | --- | --- |
| W01 | PASS | User confirmed normal interactive CCB interface and both T2 structured/freeform synthetic cards. |
| W02 | FAIL | Controls are visible (previous visibility observation retained), but user identified and exact-source review confirmed voice title/category hierarchy does not match official1.4.1 top-level voice module. |
| W03 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W04 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W05 | NOT TESTED | PARTIAL: user reports all TTS choices can save; four values visible and s2.1-pro then s2-pro observed. Ordinary tag model T2 chat selected. Auxiliary-only tag and null-follow-session not yet confirmed. Auxiliary-only tag selection now confirmed; null-follow-session still NOT TESTED. |
| W06 | PASS | Stale t2-missing-model visibly remains unavailable with no automatic substitution; audiobook changed to on with dirty warning, then returned to off and dirty warning disappeared after discard instruction. |
| W07 | NOT TESTED | PARTIAL: ordinary audiobook enable/save/reenter passed in supplied images and synthetic persisted field is true. TTS/tag changes and concurrency not yet manually verified. |
| W08 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W09 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W10 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W11 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W12 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W13 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W14 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W15 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W16 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W17 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W18 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W19 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W20 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W21 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W22 | NOT TESTED | Pending user operation/signature; no UI capture used |
| W23 | PASS | User reported normal close; retained-handle watcher confirmed owned Launcher31172 exited0. |
| W24 | PASS | Owned child19392 loaded jvm.dll and actually exited0; all owned app processes exited. |
| W25 | NOT TESTED | PARTIAL: independent JVM refused ownership while app ran (expected75), then acquired and released same root after exit (0). Reopening and checking retained UI values was not performed because defect halted gate. |

W23/W24为用户正常关闭报告加真实进程句柄证据，不是界面内容签署。T1技术测试仅作关联依据，不替代T2人工状态。
