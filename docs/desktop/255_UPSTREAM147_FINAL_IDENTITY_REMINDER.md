# 255 主聊天末尾身份提醒：用户批准的 1.4.7 措辞

用户在协调对话对单项原文／diff／影响明确回复“采纳此项上游措辞”；回执为本地 `20261102-coordinator-upstream147-remaining-prompt-diff.json` 的 `decisionRequest/A`，问题 `call_ad54a240b17f49128f411a8ee4973856/0`。批准仅及本项，不包含尚待回答的头像正负提示词 B，也不改变公开1230冻结包。

固定来源为正式1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17`。唯一物理owner是共享 `MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT`；Android `PromptTemplates` facade继续委托，头部目录符号及用途未变，不复制第二份文本。

```diff
-CCB大师只在幕后。下次不要在正文中暴露CCB大师身份，也不要再解释或确认，保持中文思考，直接开始写即可。
+CCB大师只在幕后。绝对不要在正文中暴露CCB大师身份，也不要再解释或确认，保持中文思考，直接在回复开始写作即可。
```

仅将“下次”改成持续适用的要求，并明确直接在回复开头写作。请求角色、顺序及缓存边界不变：当前用户正文 → 后置要求／可选强后缀 → assistant确认 → 本条user提醒。提醒不进入稳定前缀；无当前用户时不新造末尾消息。HTTPS保留角色，显式许可的HTTP仍由原CleartextHttp策略适配中段system，而最后一条保持user。已有模型配置的自定义字段优先级不改。

验证复用原共享assembler／Android facade／Desktop实际请求检查，并补 `MainChatRequestAssemblerTest.finalIdentityReminderSurvivesSerializedHttpsAndOptedInHttpForEveryRequirementPosition`：START／END／BOTH三位置、HTTPS和显式HTTP的最终序列化JSON中仅一份权威提醒、最后user、前一条assistant、强后缀相对位置和当前正文不重复。不锁定可编辑自然语言原文，不读预设。源码待精确云端检查；不将现有106指南运行视为本项已通过，未向真实Provider发请求。
