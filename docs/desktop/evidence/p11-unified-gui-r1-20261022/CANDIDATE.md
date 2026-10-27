# R1 独立冻结 Windows 候选

生产源码提交：`d95501f87899be47d07537132793afc90b9f4acc`。

本目录后续提交只补发行校验及证据，不改变该候选源码。版本仍为 `0.1.0-preview.1`；这不是 Public Preview 发布。

## 本地交付

- 冻结目录：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-gui-r1-20261022\windows-frozen-d95501f8\runtime-image`
- 便携 ZIP：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-gui-r1-20261022\windows-frozen-d95501f8\ChatChatBarDesktop-0.1.0-preview.1-d95501f8-windows.zip`
- ZIP 大小：164639761 bytes。
- ZIP SHA-256：`0929232098e41cefc67fb2ebf28c0b42a1bbdb1c45e213e321f7145fb98f825f`。
- 发行 manifest SHA-256：`4732d1dd9f4de95f6af654b120c210b19405a9e83b887ff937e6ff826dfe6b91`。

## 精确校验

`package-manifest.json` 包含全部 **217** 个发行文件的相对路径、字节数和 SHA-256。冻结目录对构建输出逐文件一致，缺失/额外/不符均 0。ZIP CRC、成员列表、数量和解压后每个文件的字节数/SHA-256 也 **217/217** 一致；没有只比较 ZIP 外层 hash。

唯一 `desktop-build.properties` 的版本/提交与上述源码提交一致。详细机器记录：`package-verification.json`；构建日志：`raw/12-windows-package/`。候选构建使用独立 `package-d95501f8-build`，没有覆盖旧冻结目录。

本次提供 runtime-image 和便携 ZIP，没有生成 MSI/EXE 安装器，也没有运行、安装或发布候选。后续 Windows GUI 新启动仍需新的明确授权。

## 证据完整性补充

原始 `.log` 被仓库全局忽略规则排除于首次源码提交，发行核对时发现后在本次证据交付中补入；本目录 `.gitignore` 明确保留这些日志。日志原始字节未改写，最终 Git 对象按 `evidence-manifest.json` 逐项校验。原始 CRLF 不做归一化，所有 RED/失败尝试保留。

评审内容、工程结果和一次集中人工复核范围见 `PROJECT_REVIEW.md`。P11、Preview Gate、Post-P8 HOLD 不自动提升。
