# NAI代理：选中路由与实际套接字分层复现

2026-11-01。最新本地原报告SHA256 `cadf248e3ff298e366964e336c4ccfb3d41c95e3b18e84b3ac4e5cc2dfa1e542`，原文／截图／真实资料根不上传。报告及当前运行路由截图显示应用已选择手动HTTP端口，但该端口没有连接，TCP只到系统代理端口；账户请求约20秒停在代理连接／协商阶段，未进入TLS。curl快速返回401只证明其传输路径可达，不证明应用同路由。

当前源码已经有应用HTTP／SOCKS优先、DIRECT→NO_PROXY、Main启动时loadAtStartup和networkProxy持久化。账户入口每次刷新新建secureNovelAiClient，不先假定旧账户连接池复用，不重做已有DIRECT分支或盲加重试。用户本次说的失败属于NAI，不能继续归因于已处理的资料目录重叠。

待证假设：OkHttp对HTTP／DIRECT使用默认SocketFactory；JDK默认原始套接字可能为socket://目标再询问默认ProxySelector并套上系统SOCKS，形成第二次选路。这可解释应用选择与TCP端口分歧，但在实际合成RED／现场复测前不能写成已确认根因。候选修正方向是只让底层原始连接遵循OkHttp已选路由，不能把整个客户端强制直连。

对应版本主源已读：[OkHttp4.12 RealConnection](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnection.kt#L268)、[JDK17 SocksSocketImpl](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/net/SocksSocketImpl.java#L283)与[Socket(Proxy) API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html#%3Cinit%3E(java.net.Proxy))。源码支持该机制可能存在，不代替本应用实际连接记录。

新增DesktopProxyPhysicalRoutingTest：每种情况使用fresh JVM、两个合成记录代理端口、JVM http／https／socks参数和socket:// SOCKS trap；先写同一临时资料根设置，再走真实runDesktopMain／StartupGate、真实startup load、secureNovelAiClient／NovelAiAccountService，按实际首字节区分CONNECT与SOCKS。另覆盖SYSTEM、DIRECT和本地地址绕过。代理故意拒绝，无真实Token或外网请求，不按异常文案判断成功。

正常test与独立proxyPackagedRoutingTest保留各自XML；后者创建实际production app-image，子JVM以其全部JAR优先，仅附测试probe类目录，并核对ProxyAwareClient和Main的实际class来源。子进程用测试JDK17，不宣称随包JRE的lib/modules已验。DIRECT使用保留非本地域名并仅在fixture DNS映射到本机，LOCAL单独用loopback字面量；避免两者都先命中local bypass。替换业务UI以避免打开窗口，不冒认原生GUI已复测。当前仅补复现，生产选路尚未修改，等待云端RED或具体编译诊断；不在冻结1228内。

原CI76／aaff完整FAIL：正常与packaged步骤均停在fixture的Dns类型错误，Desktop／物理代理测试未运行，不能当成错路由已复现。后续改为该版本要求的显式Dns对象，并在所有路由断言前输出合成端口／首字节类别，避免临时目录清理后丢失system实际观测；等待新源，不回填原失败。
