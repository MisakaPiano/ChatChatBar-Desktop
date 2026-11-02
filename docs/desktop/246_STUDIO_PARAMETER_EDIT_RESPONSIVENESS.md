# Studio 参数编辑响应 / 2026-11-02

用户反馈：高级参数拖动、输入数字均能生效，但存在秒级延迟和松手后继续变化。优先于后续功能，云端验证，不在本机运行 Gradle/JVM 或操作日常资料。

实际路径为 Slider/NumericInput → Panel 的 scope.launch → controller.edit → 通用 action mutex。旧 action 每次普通参数编辑均读取撤销／checkpoint 的图片候选，保存后向图片清理器传入全部候选。只要存在有效旧图片候选，清理器就申请独占维护并扫描所有实体 JSON 的引用；后续数值事件等待同一 action mutex。界面没有滑块动画，当前合法 Steps 范围仍为 1..50，不能以收费阈值或截图中的28为模型最大值。

本次最小修复：普通 edit 在既有 mutex 中完成原有 updateDraft／flush／失败回滚，只将本次草稿移除的图片路径交给原清理器。数值、Prompt及仅改变参考强度不移除路径，因此不再读取无关撤销／checkpoint或全库扫描。实际移除图片仍在成功保存后检查全部持久引用，撤销／checkpoint／历史保全仍由既有 cleaner 负责。replace／undo／history 等事务保持原广泛候选路径。清理失败仍明确提示并保留旧文件，不掩盖持久化失败。

受控回归使用合成资料和不允许网络的账户回调：

- 草稿、撤销和 checkpoint 都保留图片引用，将清理回调故意阻塞。连续修改 Steps29..50／CFG／Rescale 应完成并可从独立 repository 重新读取，无清理调用。
- 移除基图必须在可回读的新草稿提交后仍调用原图片候选清理。
- 根切换进入 RESTART_REQUIRED 后，参数保存被拒绝、原草稿回滚且门禁不绕过。

CI86／41328305888 精确0640752b完整SUCCESS。Windows307suite／2410项＝2391PASS＋19SKIP、0FAIL／ERROR，新增三项全部PASS，独立production JAR物理代理5PASS，NTFS共享读取7PASS；Linux共享1135／Android582全PASS。普通参数受控case的XML时间0.044秒，不是现场帧时或任意资料规模的速度保证。

同一测试文件通过临时request叠加在未修改的e5bf7509449ac50ce94726b6f5e19c02b659b3b6：3项中仅普通参数case出现预期AssertionError，另2项PASS、0ERROR，XML时间5.183秒并包含准确阻塞标记。旧源生产文件未覆盖；测试hash与0640752b的Windows CRLF检出字节相同，LF原始字节另记。该旧controller.edit／action与公开1229同段一致。独立artifact13093385062原XML／摘要已取证，request随后移除，原反证保留。实际用户操作仍待验，现有滑块键盘、精确数值、持久化和图片清理回归均在完整批次中保留。

冻结修复源064已在用户本版明确许可后公开为1230，发行430962166四资产及说明读回，signed14-release-r02以metadata commit8c2c24b发布，root1保持。保留的旧1225更新器已实际匿名完成公开13→14发现、下载175758480字节及第二次验包，原安装包hash ff9a8a…a98匹配；未执行安装或访问日常H。真人参数响应与重开复测仍待用户自行更新反馈。模型能力、免费资格、模型Prompt、原参考图、历史和真实账户保持。
