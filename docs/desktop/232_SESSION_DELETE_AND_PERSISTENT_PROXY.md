# 会话删除与更新后网络代理反馈

2026-11-01，B0新增用户转述：会话菜单没有删除；系统VPN开启时NovelAI仍超时，临时改JVM后可用但更新后失效。随后本地报告明确为1226/d94、Win10 build19045、v2rayN/Xray HTTP10809／SOCKS10808，修改的是用户环境变量而非版本内cfg；已有到代理的Established连接，但两种代理均hang。同环境curl无凭据请求可得到401，1218曾显示账户正常。实际1226包已包含启动system-proxy参数。因此不能用缺启动参数、升级覆盖cfg或认证失败解释该回归，持久配置本身不等于修复隧道／TLS问题。原报告仅本地读取，不执行其中环境变量命令，不上传原件；未改用户VPN、安全软件或日常程序。下一步检查安全的分阶段连接诊断，并对比原包请求链，不能无依据强制HTTP/1或改TLS／重试。

源码增加独立应用代理设置：跟随系统、HTTP、SOCKS、直连，保存在选中资料根desktop-settings.json；正式启动前读取，保存后正常重启生效。既有localhost／127/8／IPv6本机绕过保留，HTTPS专属JVM配置优先于旧HTTP通用配置；原SYSTEM其余优先级保留。配置读取失败和selector失败可见，不静默改直连。当前路由查看在IO线程，区分账户／生图／本地地址，不发送账户或生成请求；系统PAC／WPAD可能读取配置服务。该功能尚不证明测试者VPN场景已修复。

会话右键增加删除与二次确认；先排水草稿／阅读位置，拒绝冲突任务和未保存设置，再经原语音／任务／记忆预留、strict inventory、完整存档目录与session journal提交删除。删除该会话图、消息、存档与独占包；卡片、世界书与其他会话保留。已提交的媒体清理由backups/.session-deletion-cleanup私有记录追踪，先检查全体剩余实体引用，再调用现有exact image／absolute audio ownership接口；失败保留记录、重启重试，不递归删目录。扫描和事务不在UI线程。

新增代理持久化／坏配置／HTTPS优先级／selector失败、窄宽Scene、整会话删除／回滚／清理重试／共享及外部录音／晚到草稿与视口等合成回归，待新源云端执行。没有真实删除、实际测试者网络调用、普通候选打包或真人验收。P13生成核心38f3f51b保留未交付，与本次B0一起送新源，不改变公开1226身份与原Prompt。

CI64／863df8ae已完整SUCCESS；Windows288suite2350项=2331PASS+19SKIP，Android582、shared1096全通过。WorldBook／预算10图复用已实际查看的CI63同散列图；是代理验证，不算真实用户验收。CI62、CI63原失败保留。

CI65／ed988d20整轮FAIL：shared179suite1102项中1101通过，唯一MomentCore失败为IllegalStateException同类同信息但引用不同；测试改核类型／信息，保留两次调用、仅decision检查点／不重试断言，不改生产传播。Windows主步骤在编译停于新面板调用文件private SelectChip，未执行Desktop测试；现将原组件共享为internal，不改视觉行为。原失败保留，后续NSIS／更新器结果不能代替Desktop主步骤通过。

后续源码补NovelAI账户固定连接阶段诊断：每次请求独立tag，仅保存阶段／耗时／固定代理类型及协商协议，不存URL、header、正文、密钥或原始异常。请求失败时随原安全分类展示；HTTP代理连接与隧道协商合称一阶段，进入TLS由secureConnectStart确认，复用连接不伪造握手。“读取响应头开始”不代表首字节已收到。新增纯本机合成代理分别阻塞CONNECT和TLS的回归，待新源云端；没有取消TLS验证、强制协议、拉长超时或添加重试。实际1226网络回归仍待对应环境阶段证据。

CI66／d84336b2：Android582／shared180suite1103全通过，包含MomentCore4和真实loopback CONNECT／TLS两场景1测试。Windows291suite2357项=2336PASS+19SKIP+2FAIL：一处其他会话完整比较用了添加消息之前的seed（真差异为原消息添加更新预览、时间与两种轮计数）；一处controller夹具CLI_OVERRIDE未创建目录，运行锁按原保护拒绝，后续删除步骤未执行。仅改夹具：在删除前读取真实完整baseline，显式创建测试目录后申请原锁并显示拒绝详情。不删字段断言、不放宽资料根保护，不改生产源码；controller后续步骤待新源验证。原失败、XML及字段差异保留。
