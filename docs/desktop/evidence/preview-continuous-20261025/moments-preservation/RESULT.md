# 独立 Moments 原字节与媒体保全

本轮继续 D-046 / 203，不创建新的开工门槛。`DesktopPreservationStore` 仅由显式调用写入持锁准备根；尚未被 FullBackup 导入 UI 调用，没有 live Moments Entity、Repository、Scheduler 或 Task 注册。

## 行为与真实测试

- 原始 post/task JSON 逐字节复制，不重序列化。Task.postId 必须闭合。索引保留来源归档摘要、code、Envelope 版本及完整 scope；记录保存为摘要命名，原始路径/ID 在索引中明确关联。
- senderAvatar、imagePath、generatedImageMetadata.imagePath 独立解析；图片为独立物理副本，放在 `desktop_preservation`，不引用 live GC 负责的原文件。相同字节可以去重，不合并三条引用边。
- code84 缺图拒绝；code85/86 已知历史头像缺失单独记录；v2 明确省略 Moments 图片单独记录，不把缺失媒体写成存在。未实现未来 schema 的宽松解释。
- 实际 live GC 删除原文件，副本保持；快照与 Desktop 全备导出→认证检查→映射→图验证往返保持索引/记录/媒体字节。封存域标为 Desktop-only，不声称官方 Android 可接受。
- 文件改动、额外文件、未知索引字段、坏 Task.postId、缺资源、无所有权根拒绝。取消和注入的发布前 IOException 保留 index.part 与原记录/媒体，阻断重试复用和导出；不清理失败现场。IOException 是故障注入，不称为真实磁盘耗尽。

|实际轮次|结果|
|---|---|
|preservation-integration-first|5 suites /46 PASS，exit0|
|preservation-scope-final|8 suites /89 PASS，exit0|
|preservation-reference-family-red|1 test /1 FAIL，exit1：省略图片时错误地接受 documents/audio 引用|
|preservation-reference-family-green|8 suites /90 PASS，0 failures/errors/skips，exit0|

最终修复把图片类型检查放在缺失/未选择分类之前，重新读取 seal 时也强制检查。90 是本轮最终实际运行数量，不与历史46/89或其他轮次相加。八套件是保全、Staging、Source、Census、Mapping、Archive、Journal 和 Startup。

## 边界

未使用真实手机数据、API、DPAPI 或业务根。没有运行真实 pending-deletion 队列（Desktop 当前没有该运行消费者）；直接资源删除原语拒绝封存路径的测试与 live GC 测试不能冒充 Android 队列实测。Root switch/Portable/旧程序/重启/断电/P13 接管均未由本轮证明。

索引不是来源档案的替代品，不丢弃来源档案，不承诺可修改索引抵御本机恶意写入者。复制文件 force 与索引 atomic move 也不称为断电持久性证明。正式导入还需要完整资源/依赖及合并结果验证、受保护凭据提交和新根激活。

测试前各轮工作区源码快照、真实命令、日志、XML、退出码及 SHA/长度在本目录；第一轮尚未修改的 CensusWireFixtures 引用起点 Git 原字节，其余改动文件来自各轮测试前快照。已有关闭准入改动在上述后续测试时相同；安装版本配置在最后两轮测试前已更改，不影响备份生产逻辑且另有独立 Gradle 集成验证。
