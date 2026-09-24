# CCB Desktop Architecture

状态：CURRENT architecture baseline（Phase 2 COMPLETE；Phase 3A contract audit complete）  
原则：低侵入上游、共享语义、平台边界清晰。

---

# 1. 推荐 Gradle 形态

Gradle root 仍位于仓库 `app/`。当前已实现的 module graph：

```kotlin
include(":app")
include(":sharedCore")
include(":desktopApp")
```

当前目录责任：

```text
app/
├─ app/                 # upstream Android application
├─ sharedCore/          # JVM-neutral shared CCB/storage/domain contracts
└─ desktopApp/          # Windows-first Compose Desktop executable/adapters
```

Phase 1/2 已实现原 Phase 0 的 conservative JVM-sharing 方向；后续继续按审计结果逐项抽取，不做一次性全项目重构。

不要移动官方 `app/app` 目录，不要重命名大量 package，不要把仓库根重新做成 Gradle root。

这样能显著降低 upstream merge 冲突。

---

# 2. 分层

```text
                    sharedCore
        ┌──────────────┼──────────────┐
        │              │              │
      data          domain         protocol
        │              │              │
        └──────────────┼──────────────┘
                       │
          ┌────────────┴────────────┐
          │                         │
     Android adapters          Desktop adapters
          │                         │
     Android Compose            Compose Desktop
```

## Shared Core

目标只放 JVM-neutral CCB 语义：
- entities
- package models/codecs
- repositories interfaces/core
- Prompt
- WorldBook
- Context
- model policies
- RAG algorithms
- Memory policies/state machines
- SaveSlot protocol
- image/voice network-domain
- moments/community domain where platform-neutral

## Platform

只处理：
- filesystem root
- auxiliary catalog/dictionary/index storage lifecycle
- secret storage
- image decode/encode
- audio playback
- file dialog
- URI/path/open-with
- notification/task runtime
- OS integration
- updater/install
- OAuth callback

---

# 3. 第一阶段不要强制 KMP

选择：
**Kotlin/JVM shared library + Kotlin/JVM Compose Desktop app**

理由：
- 官方已经是 Kotlin/JVM 语义
- Windows-first
- Android 可以依赖 JVM library JAR
- 不需要立刻把每个 source set 改成 commonMain/androidMain/desktopMain
- upstream merge 代价更低

未来如果 Linux/macOS 成为正式目标，再根据真实 shared 比例评估迁到 KMP。

---

# 4. Desktop App Composition Root

Android 当前由 `ChatBarApp : Application` 手动 wiring 大量 repository/service。

Desktop 当前使用 `DesktopAppContainer` 作为 composition root，并在外层持有 selected-root ownership 与 data-operation lifecycle。

当前原则：

```text
root authority resolution
→ per-root ownership acquisition
→ DesktopAppContainer construction
→ DesktopAutomaticBackupRuntime initialize
→ Compose application/window
```

`DesktopAppContainer` construction 保持 zero-write。业务 service/repository 继续由 composition root 提供，不允许 Desktop UI 自行构造第二套 domain graph。

长期目标仍是让 Android `ChatBarApp` 与 Desktop composition root 尽可能依赖同一 JVM-neutral domain/storage core，但只在真实 parity 需要时抽取，不做一次性 wiring 重构。

---

# 5. Storage Architecture

默认数据 root：

```text
%LOCALAPPDATA%\ChatChatBarDesktop\
```

建议：

```text
ChatChatBarDesktop/
├─ entities/
├─ images/
├─ audio/
├─ documents/
├─ save_slot_packages/
├─ shared-import/
├─ updates/
├─ cache/
├─ logs/
├─ backups/
└─ desktop-settings.json
```

与 Android 语义对齐的目录尽量保留相同相对结构。

Phase 3 起，Desktop-owned image/document 等 Entity resource reference 遵循 D-027：持久化为相对 authoritative `appDataRoot` 的 owned reference（例如 `images/...`、`documents/...`），由 Desktop resolver 解析。Android baseline 可以继续保留现有 absolute-path persistence；Transfer Package resource ID contract 不受影响。

Portable Mode：

```text
ChatChatBarDesktop/
├─ ChatChatBar.exe
├─ portable.flag
└─ UserData/
```

规则：
- `portable.flag` 存在 → 使用 `UserData/`
- secrets 不随 portable 数据明文迁移
- 数据 root 可在 UI 显示
- 更换 root 走迁移流程，不直接切换导致“数据消失”

---

# 6. Storage Core

业务 Entity persistence 与辅助 catalog/index storage 必须分开建模。核心业务 Entity 继续由 `JsonFileStorage` 管理；NovelAI/Danbooru 的 catalog、dictionary 与 completion index 不属于 Entity persistence。

当前实现：

- authoritative `JsonFileStorage` 已位于 `:sharedCore`；
- constructor 由 platform composition root 传入 app-data `Path` 与 `AppDataOperationGate`；
- Android 传入 `filesDir.toPath()` + no-op gate；
- Desktop 传入 selected `appDataRoot` + `DesktopDataOperationCoordinator` gate；
- Desktop per-root `FileLock` ownership 与 process-local coordinator 是独立安全层。

共享 storage 继续保留：
- mutex
- Flow cache
- atomic replace
- uncached stream APIs
- transactional prefix replace

Android adapter：
```text
context.filesDir.toPath()
```

Desktop：
```text
DesktopDataDirectory.resolve()
```

辅助数据由独立的小型接口或 adapter 管理，覆盖：
- dataset 安装与版本
- 完整性验证
- catalog/dictionary 查询
- completion index 构建与读取生命周期
- 与上游一致的排序和结果语义

Desktop 不需要复制 Android `SQLiteDatabase` API，也不在 Phase 0 指定 JDBC、SQLite library 或其他具体实现。该边界不改变 Phase 2 的 `JsonFileStorage(root)` 抽离路线。

---

# 7. Platform Services

建议接口：

```text
PlatformFilePicker
PlatformOpenExternal
PlatformClipboard
PlatformSecretStore
PlatformNotificationService
PlatformTaskRuntime
PlatformAudioPlayer
PlatformImageCodec
PlatformCatalogStore
PlatformOAuthCallback
PlatformUpdater
PlatformCrashInfo
```

注意：
不要把所有东西塞进一个巨型 `Platform` interface。

每个 interface 只服务一个稳定职责。

---

# 8. Desktop UI

主窗口：

```text
┌──────────────┬─────────────────────────────┬─────────────────┐
│ Sessions /   │                             │ Context / Tools │
│ Cards        │          Chat               │                 │
│              │                             │ WorldBook       │
│              │                             │ Memory          │
│              │                             │ Images          │
│              │                             │ Prompt Inspector│
└──────────────┴─────────────────────────────┴─────────────────┘
```

右栏可折叠。

窄窗口：
- 2 栏
- 1 栏 route

不要把 Android portrait 页面机械放大。

---

# 9. Prompt Inspector

Desktop 特有便利功能，默认只读。

显示：
- logical messages
- role
- source section
- WorldBook triggered entries/reasons
- RAG cards
- Archive
- HEAD
- previous-turn hot zone
- current user
- post-history
- format suffix
- final transport request（脱敏）

这是 Debug/Editor convenience，不得冒充上游官方功能。

---

# 10. SecretStore

要求：
- Windows OS-protected backend
- API token 不进普通 JSON
- 不写 Git
- 不写日志
- Prompt Inspector / request logs 脱敏
- Portable Mode 不把可解密 secret 默认一起搬走

NovelAI/Fish 当前 Android Keystore 行为由 Desktop 等位实现。

ModelConfig 的 `apiKey` 是上游现有 Entity 字段；是否统一迁入 SecretStore 是后续独立迁移议题，不在移植时静默改变。

---

# 11. Image Backend

需要替代 Android：
- BitmapFactory
- Bitmap
- Canvas
- Android font/resource APIs
- `DanbooruTagCatalog`、`NovelAiBundledDictionary`、`RankedTagIndex` / `RankedTagIndexStore` 的 Android SQLite 与文件安装生命周期

推荐方向：
- Compose/Skia image API
- `BufferedImage` 仅在更适合 Java2D 的地方使用

要求：
- PNG metadata codec 精确
- SaveSlot 图片压缩结果不要求字节级一致，但尺寸/策略/格式语义一致
- CCB card PNG payload 精确兼容
- 视觉封面达到 Desktop 等位
- NovelAI/Danbooru dataset version、完整性验证、查询、排序及 completion-index 行为等价

---

# 12. Background Work

Android：
- Foreground Service
- notifications
- wake/Wi-Fi locks
- network guard

Desktop 等位：
- application-owned SupervisorJob
- TaskRegistry
- tray/task center
- explicit cancel
- network failure propagation
- app exit policy

Desktop 不需要伪造 Android FGS，但必须保持：
- 任务不会因 UI 页面销毁而误取消
- stop/cancel 语义可见
- 重叠任务独立计数
- failure 不被假装成功

---

# 13. Updater

Desktop Release：
- Windows EXE/MSI
- GitHub release metadata
- download
- size/hash/signature/version validation
- 用户确认
- 安装器
- restart

不要复用 APK validation/PackageManager。

---

# 14. OAuth

Community Discord/Supabase：

优先：
1. 自定义 Desktop URI scheme（注册到 Windows）
2. localhost callback

选择必须满足：
- state/nonce
- 不把 token 暴露到日志
- 可以从浏览器回到运行中的 Desktop

---

# 15. QQ Voice

定义为特殊平台 parity：

Android 功能本质是：
“让生成的音频被 QQ 的按住说话流程录入并发送。”

Desktop 必须先调查 Windows QQ 是否存在稳定等位入口。

若无公开可靠接口：
- 不以硬编码 UI 坐标自动化声明 EXACT
- 提供音频文件 reveal/copy/drag workflows
- 保持 `BLOCKED`，直到找到可靠实现或用户接受产品定义调整

---

# 16. Shared Import

共享 classifier/staging/FIFO 保持。

Desktop ingress：
- command-line file argument
- file association
- Open With
- drag/drop

再统一进入：
```text
DesktopImportIngress
→ staged file
→ SharedImportClassifier
→ FIFO
→ destination
```

---

# 17. Upstream-Friendliness Rule

修改官方文件前问：

1. 能不能通过新模块/adapter 完成？
2. 能不能只把一小段纯逻辑抽到 shared？
3. 会不会造成未来每次 upstream 都冲突？

禁止：
- 全仓格式化
- 大规模 package rename
- 无关文件移动
- 为 Desktop 改 Android UI 风格
- 复制 domain 到 desktop 再独立修改
