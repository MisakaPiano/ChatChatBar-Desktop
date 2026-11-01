# B3 世界书 AI 候选策略共享

2026-11-01。在B2封面／头像与取消修复2fd7402a／CI60运行期间，独立提取世界书候选、checkpoint与response类型、临时研究session类型，以及原候选约束／显式选中应用策略。原Android服务保留相同API、批次、模型、JSON修复、研究和任务包装，仅代理纯策略；原Android测试继续经过facade。没有移动或改写世界书Prompt，没有据此宣称桌面入口已完成。

共享策略按原规则保留每批5条、创建最多50条、创建仅写名称与关键词而正文空白、补空仅作用于选中且仍为空的已知目标；现有条目ID、正文及其他行为字段保留。I盘实际块对比已确认除缩进／跨模块可见性以外，原companion实现逐字保持。新增两个内联夹具检查约束→选择→应用链和预览后手动补文的保护；尚待新源云端执行，不能由CI60代验。

后续共享核心（本地源码，待云端）：`WorldBookAiCore` 与 `WorldBookResearchEngine` 执行原批次、研究、JSON及修复流程；Android保留task-run、API和operation/stage映射。原9个Prompt literal及7个builder移入唯一shared `WorldBookAiPromptAuthority`，原Android符号继续代理，source fingerprint新增该源。独立机械比较确认原文保持。Desktop transport seam保留isolated参数、120秒默认socket/inactivity，研究才使用thinking=false/effort=low；不套角色512/256、600秒配置。新增候选跨批失败续作、目标ID与修复、终止异常、研究session复用及6阶段真实合成HTTP请求对照，尚未执行。

Desktop源码接线（待云端）：世界书编辑器新增“AI 创建条目”和“AI 填充空白正文”，临时文档、四种资料模式、精确模型、待填目标选择、分批输出／研究与思考预览、请求日志、候选勾选、明确应用草稿。完整book／session／target保护拒绝手动修改后的旧候选；按原字段默认值新增，补空只写仍空且选中目标正文。模型同ID配置变化也使断点失效，已完成批次保留；fresh或失效时同步清除恢复入口。TaskRuntime／数据维护取消及pre-start取消复位独立覆盖；关闭先排水世界书控制器，再关共享来源和transport。没有新增自动保存或改写原件。

新增Desktop controller6回归及420／1100 Scene候选选择→应用草稿→补空→正常保存流程，尚未执行；原文9literal／7builder机械证据可复用，但不等于HTTP或运行通过。研究query上限、session跨批缓存和包含所选模型的恢复签名与角色流程不同，不能套换模型继续语义。未打包、未发布、未真人验收，B3未完成。

CI62精确c3完整结果：Android582／shared1089通过，含原facade9、Core3／Research2／Policy2与6阶段实际HTTP1（12合成请求）。Windows287 suite/2347项=2322PASS+19SKIP+6FAIL，只有新WorldBook controller五项与Panel一项停在完整对象比较；pre-start一项通过。逐个实际XML比对确认六处预期／实际的唯一差别为 `WorldBookRepository.save` 原本就会写入的 `updatedAt`（seed=0，保存后为实际时间），不是本轮生成写入。后续测试改为读取首次保存后的完整对象作基准，保留所有字段严格比较，不改仓库时间语义、不删断言；各失败点之后的步骤仍须重跑，不能用此静态定位声称通过。原失败artifact／XML保留于I盘。

CI63精确ac的Linux job因shared Core取消用例FAIL：实际是同类型同信息的 `CancellationException`，但 `assertSame` 引用不同；该用例在CI62通过不代表身份断言是稳定业务契约。改验取消类型／信息，保留同一用例的调用一次、不进入修复、候选为空及批号不前进检查；拒绝异常原透传检查保留。生产Core和取消实现不改。新增预算shared4实际通过；Android tests此轮因shared先失败而未运行，不能冒认582已由ac重验。Windows仍独立等待实际结果。原FAIL ZIP／XML保留。
