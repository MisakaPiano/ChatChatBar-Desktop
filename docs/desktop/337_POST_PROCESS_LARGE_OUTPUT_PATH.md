# 337 后处理大结果专用通路

固定148官方 `NovelAiPostProcessFiles.save` 允许100 MiB静态PNG，Enhance最多3,145,728像素，Upscale最多四倍。Desktop先前的输出验证、保存后读取和显示解码沿用普通32 MiB限额，合法3000×3000随机RGBA结果在请求成功后仍会丢失可用性。334原八方法只证明小结果生命周期与任务归属，不代表这一容量范围已完成。

新增 `DesktopPostProcessRaster` 统一结果用途的字节、完整PNG签名、静态和操作像素上限。Processor、结果Store和专用有界资源读取使用该策略；普通输入和截图的32 MiB限额保持。结果Pane、明确用作当前图片后创建的临时副本、内联及独立预览和复制显式传递结果用途，原来源、费用、任务停止和Prompt不变。

整库验证只对typed结果记录指向且摘要匹配的资源采用100 MiB策略；其他图片仍用原限制。不改变备份ZIP单文件／总量预算，也不凭文件名放宽。多记录同引用时摘要须一致，取更严格操作像素限额。

新增合成用例通过实际loopback Upscale响应生成3000×3000确定性噪声PNG（真实图像压缩后大于32 MiB），实际Controller完成、新Controller读回、整页结果预览／另存／明确用作后的当前图片显示，以及真实Service备份／映射恢复／新Store读取。还验证普通decode／截图／大结果再作为API输入仍拒绝，未登记的大图片仍不能通过整库验证。

R64／42356733866原14165B／SHA6df78ed5…4a10a已核CRC/source/XML：请求完成、新Controller字节回读与普通输入／截图拒绝均已执行。整页点击导入图片后会创建真实“当前图片”Window，而用例误用headless环境并只找主Scene；stderr有HeadlessException，后续找不到Disclosure，尚未进入结果预览／保存／备份段。另标题实际含“▸”，测试也漏此前缀。原单方法FAIL保留，不能作为整条通过或生产大图失败。R66仅校正标题仍不足，保留原运行；后继同时改为既有云端native环境和真实子Window语义树，主Scene继续证明明确用作后的内联显示。R67／42357345938原1784935B／SHA1ae80c7b…0360fd，真实native环境下完整单方法PASS、0FAIL/ERROR/SKIP、stderr空。实际请求、磁盘、新Controller、真实子窗结果解码／另存／明确用作、主Scene当前图、Service备份／映射恢复新Store和拒绝边界均执行；CRC/source/全部XML及两张实际PNG已独核。原窗口与主Scene均显示合成噪声图。不是真实服务或整进程重启／真人验收。本机不启动JVM或日常应用。


最终证据 `OWNER_LARGE_POST_PROCESS_RESULT_FINAL_REVIEW.json`。实际图大于32 MiB且3000×3000，不冒充精确100 MiB边界测试；原小结果八方法仍为分源复用，不相加成一次新九方法。原图中再处理入口的泛化错误提示随后仅改成明确输入大小超限提示，拒绝条件与原资料保全不变，未为这一低影响文案重跑整条大图。
