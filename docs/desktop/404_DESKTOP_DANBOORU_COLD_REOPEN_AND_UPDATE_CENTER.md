# Danbooru 冷重开索引与统一更新入口

## 缺陷与意图

随包索引由 `DesktopNovelAiCatalogAssets.indexAdmitted` 按 SHA-256 内容名安装；首次词库初始化把此 Path 放入临时 Snapshot，并持久化源 metadata。旧 `snapshot` 的重开分支却只寻找更新/迁移使用的 `index-<sourceSha>.sqlite`，导致完整原库和随包索引仍在时出现 `SQLITE_CANTOPEN`。翻译查询也先过 snapshot 的索引验证，所以会一并报错。

保留源 metadata 为持久权威、原库 Git-blob 验证、不可变索引与原数据准入。现有 source-named 索引优先且必须验证；仅其缺失时，允许解析同一 source 的已验证随包内容索引。不同源、原库缺失、已存在索引损坏不退回旧词库，不删 pointer 或辅助目录。只读 JDBC 在连接前要求普通文件，保留 URI 只读和 query-only。

## 原版行为与入口

固定官方 `87d1ab5e209233b66f0db9129e984cce0d437499` 的启动检查仅查应用。设置中的“应用与词库更新”由用户点击后分别检查两项，词库下载需确认；并非启动静默下载。

Desktop“数据 → 版本与更新”的“检查更新”现同时启动原程序检查 owner 和独立词库 owner，结果/错误/重试分开。程序签名、可信 root 和安装确认链不变。Studio 设置复用同一 `DesktopCatalogUpdatePanel` 与基础设施内的 `DesktopCatalogUpdates`，不再借 `launchNovelAi`，不占生图 lane、不读取 NovelAI 凭据。

词库 owner 单任务互斥，显式下载/停止。检查和安装仍持有原 data gate；原安装器保持 DB/index 准备与原子 pointer 最后发布。该发布尝试被标记后遇中断/异常显示 UNCONFIRMED，不声称撤销或盲重试。任务中心复用原 UUID，分类“词库检查/词库更新”，停止原 Job；基础设施关闭先排空它，再关闭公共 scope。

## 验证边界

`DesktopCatalogMaintenanceAdmissionTest` 的合成数据库冷安装→关闭真实 AppContainer→新 owner 重开，覆盖 metadata、翻译、查询和补全，以及 Windows 中文/空格/转义字符路径和只读文件。`DesktopNovelAiCatalogReopenTest` 覆盖缺失/损坏源和索引、不同源拒绝及只读不创建文件。更新/portable 原完整测试继续保留。

`DesktopCatalogUpdatesTest` 与实际更新页 Compose 检查分别覆盖双向失败独立、不隐式安装、共享单任务、精确停止/退出排空、发布不确定状态、就地错误与重试及不误报图片任务。所有业务数据均为内联合成；用户截图、资料路径与本机读取回执仅留本地。执行结果按连续任务账中的原始回执登记；源码及测试存在不等于已经通过或交付。

公开1247/sign29保持原字节；修复未单独获得公开授权，不自动代装或改写真实词库。1247第1组已收到用户自然应用内更新 PASS，其余第2–5组尚待用户按入口验证，旧15通过范围保留。
