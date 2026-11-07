# 合法文档后缀备份与注册范围

实际文档持久化及编辑草稿会保留用户文件名。原inventory一概排除.tmp/.part/.backup，漏掉合法文件；a128只给documents/draft_assets保留例外。隐藏目录/隐藏文件继续明确拒绝，images/.publication.part继续按未完成发布排除，链接/越界/未知数据保护不变。

旧R113原13730B/39c0f31e…875038两真实FAIL：首个文档未入inventory、隐藏文档被静默忽略。后续分支未达。修正R114原16081B/a0508968…0ba87b三套12完整PASS（新2、Selection5、Mapping5），SHA/CRC/source/唯一XML/空stderr均核。新实际Service用全量和全选两入口，经认证Source、映射恢复、真实EditorDraftRepository重开读回正文/文件名/资源路径，原目录/备份保持。循环不另计方法，不冒称用户卡已验收。

完整1a Registry delta另核：官方40个serializer在Desktop44中齐全，实际Policy/schema路径工作；安装词库沿R108桥。Desktop对未知store/隐藏内容明确拒绝，区别于Android只枚举注册集合。只映射该delta，不以未用Registry.files证明全备份，314整体false。原件详见 OWNER_DOCUMENT_SUFFIX_R113_R114_AND_REGISTRY_1A.json。
