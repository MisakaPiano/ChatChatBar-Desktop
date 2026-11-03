# 朋友圈图片版本与资源保全

2026-11-03，当前选定 M04 源码片；必要云端检查尚待精确原报告，未交付或真人验收。

`MomentPost.desktopImageVersions` 是可省略的 Desktop 扩展，每个版本保留稳定 ID、图片引用、Prompt、metadata、brief 和时间。旧动态不因查看而写入；第一次重新生成时把原图纳入保留集。有当前图时仅追加新候选，保持当前 imagePath/Prompt/metadata/brief；由用户明确选择才切换。原来无图时可选首张。修改在原 `DesktopMomentStore` 原子写入及协调器内完成，来源比对包含完整版本集，过期生成不能覆盖人工选择/删除/正文变化。

动态“图片版本”入口预览、使用、确认移除候选。移除当前图的确认明确说明改用最新保留候选；至少保留一张。生成失败/取消仍沿原 worker cleanup；新引用落盘后，原全引用清理器保护全部保留图。删除动态的 write-before-delete 回执覆盖全部候选及头像；保留旧 v1 两引用读取，新 v2 有显式数量/文件预算，不取消清理或放宽未知引用保护。单动态最大4096候选，达到时在 Provider 请求之前拒绝并让用户手动移除，不自动裁剪旧图。

现代整应用备份实际导出 Moments 范围，映射所有版本图片及metadata路径；不选配图时同时清除当前图/metadata与版本列表，省略和大小估算一致，头像遵循原保留规则。`DesktopMomentBackupExtension` 独立校验 Desktop 字段，固定官方 `MomentPostSnapshot` DTO 不改。桌面导出显式写该字段（即使空列表），用于新根合并的 live 动态选择；普通官方手机动态和所有 incoming 任务继续原字节 dormant custody，不自动启用调度。电脑自身原动态/任务保留为合并单位；新根仍不继承本地任务许可。旧 v1 Moments 整包恢复拒绝保持，不能拿现代桌面扩展宣称 Android 完整往返。

保全副本的资源边和校验也覆盖每个版本；恢复映射后检查 live 图片文件存在。新版本 field 不冒充代码84官方字段。全局引用清理仍识别其他动态/角色卡共享图；新根/源变化、取消和草稿边界保持。

必要验证源码：`DesktopMomentImageVersionsTest`（四候选、重开、当前图保持、显式选择、过期完成清理、共享引用及整动态删除）；`DesktopMomentVersionBackupTest`（现代导出/认证、三候选与当前第三图的参数/字节回读、新根 store 切旧图并重读、关闭图片范围全部省略、原件不变、incoming 任务排除）；`DesktopMomentImageVersionsPanelTest`（420/1100实际内容中的版本选择与确认删除）。同时运行受影响的原 Moments/backup/merge/scope/preservation 必要回归。没有用纯列表或原件封存成功冒称 live 图片版本恢复成功。

公开1235/签名17、日常 H 安装及真实用户根保持；不自动合并真实数据，不调用真实 Provider，不在本机启动 JVM/GUI。完成检查后再准备匹配的普通私有候选与必要生命周期验证。
