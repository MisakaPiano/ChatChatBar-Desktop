# P11 Moments 封存与官方回导限定实验

类型：SYNTHETIC-ONLY / ISOLATED POC / NO PRODUCTION IMPLEMENTATION。

授权：用户本轮明确允许固定官方源码、合成档案、独立工具、隔离模拟器、官方 codec/validator/必要运行时读取、负例及中断实验；允许提交推送现有 feature 分支。没有物理设备、真实档案/凭据、Windows 正式 GUI 或生产持久化实施权限。

控制起点 `62b93e824333540657474d43dc63723d6e90ab24`；分支 `feature/phase8-fish-audio`。正式 baseline 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；实验官方来源 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`。前一轮设计目录不修改。

## 四项独立证明

| 项目 | 方法 | 不足以证明的替代结果 |
|---|---|---|
| A 封存 | 官方 Android 创建真实档案；PC 完整校验 inventory、封存原始记录和资源、建立字段引用表 | 手工拼一个源 ZIP、自解析器一句 PASS |
| B 回导 | PC 从封存副本生成新包；官方 Android extract + validator + MomentRepository 读取 + 官方再次导出；独立全字段/媒体对照 | 取回原包、只比较重新编码后的 JSON bytes |
| C 调度 | 官方恢复事务/导入确认标志重置、真正 runOnce 的关闭与开启但无 Provider 准入路径；静态追踪 startup/settings | 仅关闭时不执行就声称未来开启也不会执行 |
| D 保全 | 明确负例拒绝类别、输入/旧 generation/旧输出不变；子进程硬退出与另进程读取旧证据重新导出 | 实验 pointer 发布成功就声称生产 restore journal 支持封存 |

## 夹具和安全条件

官方序列化生成 5 Post（文字、图片、私密、失败 checkpoint、历史孤儿）、5 Task（五种状态各一）、1 卡/1 sender/1 session、2 张程序生成 PNG。共享 sender/post/card 图片与不同 metadata 图片均纳入引用闭包。历史孤儿只批准合成 `historical / deleted-card / deleted-session / deleted-sender` 组合作为实验准入，不推广任意孤儿。

Moments 默认关闭，images 关闭，模型凭据为空，HTTP 模型许可关闭。调度负向启用实验只允许“无可用鉴权模型”的正式拒绝路径，调用前断言该条件。本机 loopback 哨兵不提供外部代理；它的零连接不等于全网络抓包。模拟器在安装测试包前加入 IPv4/IPv6 OUTPUT 非 loopback 拒绝规则；测试期间重新核实规则。

scope：`app/desktopApp/build/p11-moments-poc-20261022/`；独立 AVD `ccb-p11-moments-poc-20261022`，serial `emulator-5584`，ADB server `5045`。只有这个新建测试 AVD 接受安装/设置/退出操作。宿主真实数据根和 SecretStore 不在工具参数中。

## 工具与执行顺序

工具：[OfficialMomentsPocTest.kt](../../../../tools/desktop/experiments/p11-moments-preservation/OfficialMomentsPocTest.kt)、[Run-Official.ps1](../../../../tools/desktop/experiments/p11-moments-preservation/Run-Official.ps1)、[poc.py](../../../../tools/desktop/experiments/p11-moments-preservation/poc.py)、[compare.py](../../../../tools/desktop/experiments/p11-moments-preservation/compare.py)。

1. 解出精确官方 Git tree 至全新 scope/official；验证 378 个主源码/构建文件身份，CRLF 仅按既有验证器规则规范化。仅复制新 androidTest harness，官方生产文件不改。
2. 在官方 app 下离线构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，build 属性的 SiliconFlow/Supabase 三值为空；单 worker、无 daemon/cache/configuration-cache、Kotlin in-process。失败日志原样保留。
3. 使用已安装 API 34 google_apis x86_64 镜像创建新 AVD；无窗口/无声音/不加载快照。专属 ADB 核实名字、boot、root；配置非 loopback 出口拒绝；仅安装本次两个 APK。
4. `Run-Official.ps1 -Phase produce`：实际官方生成、extract 和 validator；拉取专用 externalFiles/p11-moments-poc 输出，不读取其他应用文件。
5. `python poc.py run <official-source.cbbackup> <new-host-run>`：每次 run 必须新目录；保存原包、条目、seal、独立引用图、20 类负例输入、JUnit XML 和 7 次 child exit/recovery。字段许可来自已验证官方合成参考，不是通用 schema engine。
6. 将 host-reexport.cbbackup 定向传回同一新模拟器。`-Phase consume` 实际官方接收、仓库读取和再次导出。`compare.py` 比较源/host/官方第二次包所有条目、全部逻辑字段及资源 hash；不删除未知字段后再比较。
7. `-Phase scheduler` 对另一个临时 root 执行官方 reset、seal、transaction、Repository 和 Scheduler；不启用正式应用目录的 Moments。独立 JSON/XML/原始 runner 输出同时留存。
8. 保存输出/源码/APK 身份和命令退出码，再正常 `emu kill` 关闭已核验 AVD，停止本次独立 ADB server。保留旧/新合成档案、未发布 generation 与中断恢复证据。

## 证据规则与限制

每个 instrumentation method 用独立命令执行，runner 必须有 `OK (1 test)`，另由真实 JUnit RunListener 写 XML；ADB 退出 0 单独不算测试通过。Python XML 同样来自真实 unittest 回调。输入/输出及原始日志统一登记 EVIDENCE_SHA256.json，不只提交结论文本。

Python 封存器只支持本次已验证的明文官方 v1 合成包；不实现加密密钥迁移。大小上限有意限制在小型 POC。原始字段/资源及输入字节保留，所有拟议字段转换只发生在独立实验工作区。

最终结论仅使用 FEASIBLE_WITH_PROVEN_SCOPE / INFEASIBLE_WITH_EVIDENCE / INCONCLUSIVE / BLOCKED。运行链路、未测试分支、普通模拟器环境失败与真实数据拒绝分别报告。实验结果不改变 P11 / D-038 / Post-P8 HOLD / Preview Gate。
