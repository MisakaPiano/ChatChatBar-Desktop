# 345 图像引导数值输入与Vibe历史身份

实际Studio图像引导原七类float使用普通StudioField：模型把输入“0”回声为“0.0”，按字符串比较会替换用户的编辑态。多个Vibe同标签又在同一text-history scope内，字段可能共用局部撤销身份。

原参数body提取为实际复用的 `DesktopGuidanceNumericControls`，并直接接回Studio原入口。薄的 `StudioNumericField` 保留标签、复用原NumericInput的等价回声和失焦settle；float仅发布finite／0–1，Minimum Context仅32–96。每个Vibe按id作Compose key及history scope，变更按当前集合中这一id合并，信息提取数值改变才清自身编码、等值输入和强度编辑保留编码；移除／重排不把旧字段写给其他Vibe。模型、其他guidance、reset／原全局undo及价格／API不改。

聊天图片重新生成对话的Guidance同样改接原数值框，保持finite／1–10。其预提交提示／按钮使用既有requestValidationError作一致性接线。固定官方该Dialog没有这些Steps／Guidance编辑，这是已有Desktop扩展；当前initialSettings新默认再card.applyTo.normalized，且界面不选sampler，未证明用户从这里能进入旧High DDIM状态，不把它记为已复现sampler缺陷，也不伪造非法注入测试。Prompt、元数据、取消及模型菜单不改。

定向一个实际guidance body方法检查两个Vibe相同初值下的小数“0”→“0.35”、真实CtrlZ隔离、等值信息输入保编码、只清目标编码、重排后撤销及移除后的旧回调不改另一项；共享原history组件，非镜像范围测试或全Studio回归。待原件，不提前记通过。

R78（42364536689）原件11867B，SHA256 `1ba50b26215d518465b733dfa09d2803daa41785b3f8165e2cc612be68c8de62`，CRC／source／全部XML已核。唯一XML为JUnit `initializationError`：方法末尾可选图片写入使表达式返回类型推断为Path?，没有显式Unit，测试框架要求void而拒绝启动；目标方法body没有执行，没有PNG，不作数值业务FAIL。后继只给测试方法加显式Unit。

同时把夹具输入校准为逐字“0”→“0.”→“0.3”→“0.35”：原一次SetText从0直接插入3字符会按既有small-edit规则分成两个撤销组，这是静态发现，R78尚未执行到它；不能为了夹具改原history算法。保留原“0”未被回写的断言与其余身份／编码检查，只重跑此一方法，待后继原件。

b1a261bc只修测试显式Unit与逐键序列，R80／42365384145（control d9dfba84ce01c0801a55d53531c5441993745a24）仅同一实际方法在验。产品history算法、所有其他断言不变；R78目标body 0执行与静态撤销组假设分开保留。
