# 更新请求的代理协议与应用路由

测试者反馈旧1236的手动检查更新向HTTP入口发送SOCKS握手，同机NAI正常。冻结1243中手动检查仍由裸OkHttp的`DesktopUpdateChecker`执行；安装版启动发现、下载及安装前复核则是`DesktopUpdateRuntime`调用独立TUF helper，原helper的urllib3默认直连。这两条链路分开定位，不能用其中一条的成功代替另一条。

修正方向：

- 新检查捕获当前已保存的应用代理设置，沿原共享selector解析SYSTEM／HTTP／SOCKS／DIRECT与绕过规则。底层HTTP／DIRECT socket使用原`DesktopSelectedRouteSocketFactory`，避免JDK再次将其按系统SOCKS选路。
- helper每次启动接收四个既有允许HTTPS域的无凭据typed route。各域保留真实协议；HTTP用CONNECT，SOCKS用独立SOCKS5远端解析，DIRECT保持直连。新增PySocks wheel按公开元数据锁定大小与SHA，原运行时组装流程负责核验。
- 当前操作保持自己的路由快照；下一次检查／下载读取新保存选择。没有修改其他网络owner的重启提示语义。
- 原签名root、URL与重定向允许域、目标哈希、取消、单次wire限制保持。不加静默直连回退、强制重试，不改用户系统代理或VPN。

定向证据由实际云端结果登记：新JVM带系统SOCKS污染，NAI与检查更新分别记录HTTP CONNECT／SOCKS／DIRECT／SYSTEM／loopback及更新下一请求热改；打包路径检查生产JAR类来源。准备后的独立Python运行时分别测试真实CONNECT、SOCKS、DIRECT TLS首包、每域路由和错误输入，原TUF策略测试另列。均为本地回环合成服务；不调用真实账户或远端更新服务，不代表测试者机器已验收。
