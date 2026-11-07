# 已成功存档操作的当次准备资料生命周期

2026-11-07。用户授权只清理能确认不再使用的内容、保留仍需使用的资料。本组是源码确认的生命周期缺口，不是已操作真实用户盘或已测得释放空间。

## 缺口与原意

DesktopSaveSlotArchive每次创建backups/.save-slot-work/slot-UUID，Prepared原来不携带释放能力。Service在成功create/import/export/restore/duplicate/rebind后仍留完整准备包、离线plan和中间媒体。Container的asset:分支另将内置图写到backups/.save-slot-assets/UUID.ext，原来没有当次释放者。失败现场保留有实际诊断/恢复价值；把所有旧目录按前缀扫掉会破坏这一原意。

SessionCommitJournal.finish只是将active改名done，回滚成功也会finish，因此done前缀不代表成功，也不是本组删除对象。journal自己复制了声明的old/next证据；当次主操作确实返回且无pending journal后，原准备目录才不再是其输入依赖。

## 后继实现

Prepared携带新创建目录的DesktopSaveSlotOwnedWork能力。Service在发布/导出前封存该目录的精确文件集合、物理身份与hash，主操作抛错/取消时不进入释放。主操作实际返回后，以非等待的normal准入检查无pending journal，再确认完整tree仍与封存一致，逐项按原身份删除；遇到多余文件、改变bytes、目录替代或无法确认路径则保留并提示。只处理这次生成的目录，不按旧目录名推断，不扫journal。

临时asset副本携带独立parent/file身份与创建字节hash。capture只记录实际物化的内置asset副本；确认成功后独立释放该文件，外部图片、普通用户图片、内置原始资源和其他旧临时文件均不是成员。失败或取消时保留副本。hash/目录遍历与删除在IO上下文中进行；清理失败显示成功侧notice，不将已提交的主操作伪装成失败，也不放宽原source CAS/plan验证/原子journal发布。

本组待云端实际Service、Archive和新的所有权拒绝检查：成功全流程、复制后真实图引用可读、旧证据保留、foreign文件造成清理拒绝但复制仍成功、内置asset成功释放与失败保留、目录/字节替换拒绝。当前尚未计PASS，没有本机JVM、真实用户根操作、安装包或公开更新。

324中d175的会话复制原准入复用保留为该时点的事实；本组在不改变原离线准备/独占发布边界的前提下增加成功后生命周期，修改后的Service/Archive需要本组后继证明，不能继续称当前整个Service blob仍等于原162。
