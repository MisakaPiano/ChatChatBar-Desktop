# 向量请求数值统计 / 2026-11-02

原216 P15用量缺口的独立增量，不混入已冻结0640752b的1230候选。

复用现有DesktopEmbeddingGateway的最终配置核对、one-shot请求、8MiB响应边界和索引对应／维度校验。配置或准入拒绝时不记物理尝试；实际dispatch前建立一逻辑请求／一物理attempt，不增加重试或真实API请求。

响应仍只解析一次。共享OpenAiSseParser新增已解码JsonObject重载，与原SSE字符串入口使用同一计数权威，避免为可选usage重序列化整个向量。服务端报告零则保留零，缺失／无效可选数字保持未知；有效向量不因可选usage形状异常而失效。先读到的计数在后续向量对应／维度失败时仍保留，并以FAILED结尾；取消保持CANCELLED／未知受理，不冒认零费用。

数值记录与聊天等使用相同本地owner、原子保全、失败待存和关闭排水，协议schema不增加字段。模型ID／modelName来自实际EmbeddingConfig并用现有凭据scrubber限界；配置没有provider／命名账户归属，因此保持null，不从URL猜。正文、向量、响应原文、URL和密钥不入统计。

父归属复用实际运行层：文档准备child记录其已登记task UUID及card/document；历史维护显式携带真实session，不把`chat-memory:*`准入键当card；主聊天child携带本身task/session，检索入口补当前card/session/user。没有父证据的直接调用仍OTHER且ID为空。未额外创建任务中心owner、索引或后台工作线程。关闭顺序继续是embedding transport排水后才关闭数值owner。

CI87／41332222249精确9ae6117c完整SUCCESS。实际Windows308suite／2415项＝2396PASS＋19SKIP，以上五项合成回归全部PASS；独立production JAR5、NTFS7、Linux共享1135／Android582通过。现有embedding wire／索引事务／维护／关闭和数值UI回归保留。该证据不覆盖后续记忆／检索文本适配，也不改变1230冻结源或完整账单覆盖声明。
