# POC-B：官方回导与再次导出

结论：FEASIBLE_WITH_PROVEN_SCOPE。

链路：官方 source → host 验证封存 → 从封存 payload 创建新的 host-reexport → 官方 extract/validator → 官方 MomentRepository → 官方第二次 create。host 包 SHA 与 source 不同；输入不是简单原样返回。

- [最终官方接收原始输出](raw/20261022-180225-050-consume/instrumentation-output.log)
- [官方回传档案、恢复后的逻辑文件及 JUnit](raw/20261022-180236-184-pull/artifacts/)
- [逐条目、全部逻辑字段、资源 hash 与实际路径对照](raw/roundtrip-comparison.json)
- [最终比较命令](raw/compare-path-green-command.json)、[输出](raw/compare-path-green.log)、[退出码](raw/compare-path-green-result.json)

结果：15 个条目集合相同；host 封存后每个 payload 原始字节相同；官方再次导出的所有实体逻辑字段相同；2 张图片字节和 SHA 相同；10 条完整资源引用相同。任务五种状态及 postId、sender 快照、私密/失败/checkpoint 字段和历史关联未丢失。没有忽略未知字段来得到相等结果。

资源路径的 20 条实际证据（来源与回传各 10）保存字段值、manifest 推导路径、token、entry、长度及 SHA。官方 `BackupDataRegistry.target` 对 target 做 canonicalFile；该模拟器回传实体使用 `/data/data/com.example.chatbar/`，manifest 使用 `/data/user/0/com.example.chatbar/`。只许可这个精确包根别名；官方第二次 export 再次转回同一 token。没有泛化为任意路径替换。

比较工具有两次原始 RED：

1. `compare-red.log`：把 inventory 遍历产生的引用列表顺序误当作引用身份；最终比较完整引用边的有序多重集，保留重复边，不改变实体字段。
2. `compare-final.log`：增加实际物理路径证明时未处理官方 canonical 根别名；最终仅接受上述精确别名并逐项记录。该名字虽然含 final，实际 exitCode=1；最终有效结果是 compare-path-green。

这证明固定夹具的正式 codec、validator 和 Repository 接收能力。未执行 Android 全应用 UI 恢复后冷启动、真实模型续聊、完整 Android 生态备份、物理设备或未来版本。不要把限定仓库读取等同于所有应用功能的验收。
