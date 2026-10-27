# Desktop 关闭准入修复

参考 Studio 固定实现 `248485824e1d6d2201ceedf7d14ff66e251d344d` 的先关准入经验，独立定位 Desktop 实际调用顺序。未复制 Studio 应用/标识/服务/保护配置。详见 `docs/desktop/STUDIO_UPDATE_REFERENCE_ADAPTATION_20261025.md`。

## 真实运行

|轮次|结果|含义|
|---|---|---|
|studio-close-admission-red|exit1，0 tests|新增 JUnit 方法意外推断非 Unit 返回，过滤器找不到；夹具失败保留，不称为产品 RED|
|studio-close-admission-red-executed|1 test /1 FAIL，exit1|真实 AppContainer.close 已进入前序 FullBackup controller 的 cancelAndJoin，仍允许新的文档准备任务；断言准确失败|
|studio-close-admission-green|4 suites /34 PASS，0 failures/errors/skips，exit0|包含12任务、7聊天取消、5关闭观察、10更新检查测试；不是 Studio 的18/56测试，也不是 Windows 安装升级证据|

修复只在容器关闭第一步关闭全局任务准入；操作在原有 admission 锁内线性化且可重复调用。停止准入本身不取消已接受响应，原有 closeAndDrain 仍执行原先取消、join、超时保留存储的语义。没有把 Studio 收完整响应策略强行替换 ChatBar 的用户停止/取消合同。

阻塞的合成文件选择器和 UNDISPATCHED 关闭调用保证测试确实停在前序 drain；没有靠随机 sleep 猜测时序。另测已接受图像任务在 stopAccepting 后仍 RUNNING、完成，后续图像与文档任务拒绝。没有真实 Provider、GUI 或凭据访问。

## 身份与限制

运行起点 `01d9d4bd2b427289a869e26e2afc861721759f5d`；命令、退出码、原始日志、真实 XML 及此改动三份源码在各轮目录。已修改文件来自测试前工作区快照；RED 中尚未修改的两个生产文件引用该 HEAD 的 Git 原字节（不冒称其 CRLF 工作区字节快照）。RAW_INDEX 绑定公开证据长度与 SHA。

当时工作区还包含独立的、未提交的 Moments 保全实现；本记录仅验证关闭与更新回归，不把它认证为封存工程完成。未提交附件和交接文件保留，未上传私有 bootstrap 冻结、真实数据或档案。

Windows native 安装版本碰撞仍是后续打包适配项；真实安装/占用/升级/回滚尚未由本轮测试证明。继续已有工程，不等待新的 Project 开工许可。

SOURCE_IDENTITY 分别记录已测试工作区字节与 Git blob；两份文件只有 CRLF/LF 差异，一份完全相同。归一化只用于对应比较，原始日志/快照和索引字节没有改写。
