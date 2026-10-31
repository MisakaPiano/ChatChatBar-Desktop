# 本地模型与外部代理并用／接入指引

2026-11-01；对应 FB20261031-02／03。源码修正检查点，不是新版本发行或真人验收。日常／公开仍为 1224；不修改用户系统代理、全局 Java 环境、原数据或凭据。

## 原意与首因

`sharedCore/domain/ProxyAwareClient.kt` 为模型、NovelAI、Fish 等请求提供共用代理选择；原有手动 HTTP → JVM HTTP → JVM SOCKS → 系统默认 selector 顺序需要保留。原问题是前面三个分支直接返回代理，尚未检查本机与免代理地址，因而本地模型请求也进入代理。

现用 1224 实际 JAR 的独立检查证实：127.0.0.1／localhost／IPv6 loopback 三项错误走 HTTP 代理；实际 modelApiBuilder 的本机 HTTP 请求命中模拟代理而非 origin。I 盘 `20261101-proxy1224-probe/RESULT.json` 与 `HTTP_RESULT.json` 保留原失败。

## 本次修正

- 在既有代理优先级之前判断 localhost、合法 127/8 IPv4 字面量、数字 IPv6 loopback 与 `http.nonProxyHosts`。显式空免代理列表也不让本机地址绕进代理。
- 免代理列表按 `|` 分项、`*` 通配、完整主机名匹配，大小写与主机尾点归一化，其他字符按字面匹配。不把所有私网默认直连；不为免代理判断查询任意域名 DNS。
- 外部地址继续使用原代理优先级；保留 IPv4 优先 DNS、显式 HTTP 许可、空密钥不发 Authorization 以及原取消／重试行为。
- Desktop 自身 JVM 启动参数加入 `-Djava.net.useSystemProxies=true`。该选项在 VM 启动时读取，不在建 client 后临时设置，也不永久改 `JAVA_TOOL_OPTIONS`。依据 [JDK17 networking properties](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/doc-files/net-properties.html)：默认 false，Windows 系统代理需显式 opt-in；HTTPS 共用 `http.nonProxyHosts`。
- 模型编辑页增加中英文接入说明：使用服务提供的 OpenAI 兼容 Base URL 与准确 model ID，是否含 `/v1` 由服务决定，列表请求为该 Base URL 下 `/models` 的 `data[].id`，缺少列表接口可手填；HTTP 需显式允许，无鉴权服务可空 key，保存后测试。不假定 Ollama／11434，不把连接功能称为模型下载部署。

## 已执行证据

本地目录：`I:/CCB-Desktop-SmallFixes-20261028/`。

| 轮次 | 真实结果 |
| --- | --- |
| proxy-routing r01 | 新测试误用非 SAM 的 OkHttp Dns 构造导致测试编译失败；未执行断言。改为 object 实现，未改变产品源。 |
| r02／原产品源 | 两项测试实际执行均 FAIL：selector 本机被代理；真实 HTTP origin 收到错误路由。保留 XML／日志，不改写为通过。 |
| r03／修复源 | 5 suites／33 项全部通过、0 error／skip：新路由 6、既有 client 3、模型列表 5、连接探测 11、主流式传输 8。 |
| r03 新路由细项 | 手动／JVM HTTP 各自并行请求 127.0.0.1、localhost 与模拟 external；两类 origin/proxy 正确分别命中；手动及 JVM HTTP 的真实 IPv6 loopback 请求通过；空 key 无 Authorization；custom bypass／外部 lookalike／原代理优先级保留。 |
| 独立编译库复核 | 协调端 `CANDIDATE_RESULT.json` 四项与 JDK 对照一致；`CANDIDATE_HTTP_RESULT.json` 本机实际请求 direct=1／proxy=0，两子进程 exit0。明确 codeSource 为新 sharedCore.jar，旧日常指针和安装 JAR 保持。不是新包或 GUI 验收。 |
| proxy-default-selector-r01 | 独立 JDK17 子进程给默认 selector 固定 HTTP 代理：三个 loopback 不委托，external 恰委托一次并返回该代理，exit0，无 sockets／真实 provider／数据访问。不是 Windows 原生配置验收。 |
| r04 Desktop | 主编译、3 suites／21 项模型管理／编辑生命周期／诊断检查全部通过，0 error／skip；createDistributable 成功。实际开发 app-image cfg 含系统代理启动参数，SHA256 `07e134a3b9190d3527393c55e5db059803b98e82a406f0287795b473933d48ae`，I盘 proxy-app-image-config-r04.json 保留核对。不是冻结普通安装包或新版本交付。 |

两轮共54个定向用例通过，不是完整CI或新版真人验收。普通候选／最终安装器生命周期与该包的发行检查仍按后续冻结身份执行。

## FB02 两个子问题分开登记

本机地址被手动／JVM 代理错误转发的源码修复与隔离真实 HTTP 验证已完成，尚未随新版本交付。只开启 Windows 系统代理的原反馈另含启动配置缺口：1224 实际 cfg 未 opt-in，现已补参数并核新开发 app-image cfg；当前机器只读观察为 ProxyEnable=false、ProxyServer/PAC 均空，未改开关。因此尚不能声称已经验证开启 Windows 代理或 v2ray 后的真实 NovelAI 连接，也不把模拟 selector 通过冒认该真人场景通过。

本次没有扩大为全套代理 UI、provider 分流或环境变量体系；不关闭 FB04／FB05、SF12／SF14 和原有验收余项。
