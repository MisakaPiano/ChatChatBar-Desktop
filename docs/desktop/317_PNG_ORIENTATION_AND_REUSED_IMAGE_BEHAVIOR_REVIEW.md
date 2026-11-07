# PNG 方向补修与已有图片链路复核

2026-11-07。固定追齐目标仍为 `12e420a58d1c097cb996d09dbf0c3e659d4377d6`；本页不提升正式 upstream baseline，也不改变公开1244/db7/签26、A1真人保存FAIL或315未采纳Prompt组。

## 本次发现的实际缺口

官方 `ImagePrivacyExporter` 读取 PNG/JPEG/WebP 的渲染方向，只向新副本保留最小方向信息。Desktop 的静态像素解码原先只应用 JPEG 方向；带 `eXIf` 方向6的合成3×2 PNG仍被当作3×2横图，隐私副本随之丢失显示方向。旧的编码器单元测试和完整162通过都没有覆盖这条真实PNG解码链，不能据此宣称已经等价。

测试提交 `5db8722bc935ffa068329529b0dd11884f54717a` 不改生产代码。云端R01实跑三方法，得到两项业务FAIL和一项普通PNG PASS：解码报告应为2×3、实际3×2；隐私副本应宽2、实际宽3。不是编译或测试前置失败。

生产修正 `5f0850d1be506b1b53af969cf06e0ea28b9eda71` 复用共享 `ImageMetadataStripper` 原有有界PNG方向读取器，新增内存字节入口；Desktop在尺寸报告、采样、裁剪之前应用方向一次。原文件读取入口使用同一实现，既有长度、EXIF上限和TIFF校验保持。隐私输出从已正确定向的像素重新编码为方向1，原EXIF和文本块不会复制。

原始隐写读取保持独立：`DesktopImageMetadata.alpha` 直接读取原始完整栅格，不经过显示旋转或缩放。`fa39b24ad72ab44d66e2777f64b7828cd667db14` 只扩展既有集成测试，在相同Comment/alpha优先级场景加入方向6，检查隐私清理、方向归一化和源字节保留。WebP原有读取适配和APNG帧提取没有被改成静态图片流程。

## 前后对照原件

| 检查 | 正常源码 / 工作流控制 | 原始结果 |
| --- | --- | --- |
| R01 / 42259077470 | 5db8722b / f9565b203f395ac08477b7095eb7965103040f3e | Desktop三方法：1PASS、2FAIL、0ERROR/SKIP |
| R02 / 42259492759 | 5f0850d1 / 807e2cda666ff6807f35308e45f1db244efe3fed | shared三套9PASS；Desktop五套36PASS；0FAIL/ERROR/SKIP |
| R03 / 42259960504 | fa39b24a / 19dfff57a99c5f71e88d1d3e96c81263b9734c8c | Desktop两套11PASS；0FAIL/ERROR/SKIP |

R02包含 `PngOrientationReadingTest`、`ImageMetadataStripperTest`、`PrivacyPngEncoderTest`、`DesktopPngOrientationTest`、`DesktopPhase7ImagesTest`、`DesktopPhase7ReauditTest`、`DesktopCharacterCardPngRendererTest`、`DesktopScreenshotDocumentsTest`。R03包含方向三方法及实际图片集成八方法；它与R02重复的方向三方法不重复计算为新增覆盖。

三份原ZIP均已独立核对长度、SHA256、ZIP CRC、`source.json`和实际XML方法；本地原件及逐方法review位于 Continuity 的 `updates/20261106-upstream-catchup-to149/`。

| 原件 | 字节 | SHA256 |
| --- | ---: | --- |
| png-orientation-r01-original.zip / artifact13523858836 | 12982 | cac28482850f2f6fd726f69a0815c627b8202780978914a425a767401e6cfc31 |
| png-orientation-r02-original.zip / artifact13523814671 | 47081 | ee1d948da1f3611f13df499d781e7b3724dd8e19b2899279270bad73da2a5929 |
| png-orientation-r03-original.zip / artifact13525246524 | 13229 | f1302cec8bd4fb12c5c5d69b897b28023e234f49c1debe5971e07d39d13f2391 |

## 已有功能的实际链路与复用边界

`OWNER_IMAGE_MOMENT_REUSED162_SOURCE_REVIEW.json` 记录位置、后处理、alpha读取、价格、Variety+、朋友圈相册/编辑、历史范围选择和隐私编码的共享owner及Desktop实际caller。固定目标对比只对注明的行尾/可见性差异归一化；价格的 `isMedium` 与 `isMediumEffort`、Variety+注释/空白差异另有语义审阅，不能称逐字相同。

`OWNER_STUDIO_EXISTING_BEHAVIOR_REUSED162_REVIEW.json` 补充核对清空、角色暂停/折叠、角色Prompt追加导入：

- 清空按钮经实际Studio controller到共享 `clearPrompts(cardNegativePrompt)`；清除画风、基础、补充、角色、描述、额外要求和转换快照，并从已关联卡恢复有效负面词。导入卡同时带入卡负面词。
- 暂停只改变保存的 `enabled`，不删除Prompt、负面词或位置；HTTP、位置面板、容量验证、设计附加快照均使用 `activeCharacters`。折叠/编辑/固定展开是独立显示状态。
- PNG角色导入保留关/覆盖/新增三种模式；新增保留原角色ID、暂停状态、内容和顺序，缺少角色数据不抹除旧内容。
- 历史Shift范围选择原完整162的 `DesktopCurrentImagePresentationTest` 确实使用实际 `DesktopStudioHistory` 和Shift指针按下/释放，断言可见区间选中三项及刷新清空。该方法、owner及 `DesktopFinalProductFixture` blob保持相同；这是复用原运行证明，不冒称本轮又执行了一次。
- 朋友圈相册按卡/查询/日期粒度筛选并返回精确动态ID；人物编辑通过原store修改文字/作者，保留图片、任务和其他动态字段。

复用原完整162：run42085881455、checkout57011ef9；shared原ZIP SHA256 `94dbb24756e3e03ba2cd96f56f3c4204f13e24a6cf0c109c3941d8e74f4d17a1`，Desktop原ZIP SHA256 `6060fda49658efc08fe19b14d55a83c94ea6731bf430ffc481adac1ccd2af5f3`。本轮已重读对应XML和当前源blob。原图形前置SKIP保持SKIP；新发现的PNG方向缺口以本页R01–R03覆盖，不能用旧162掩盖。

`ac03143beeb79c78a76f78920c66c532271d5391` 另补上游41476f的“新建对话”图标区分：采用同库 `MessageSquarePlus`，角色新增仍为 `CirclePlus`，原点击回调和无障碍描述不变。后继run42261803705实际编译及原输入界面邻接三套32方法PASS、0FAIL/ERROR/SKIP，原件见318；不用图标常量镜像测试充数，不冒称原生真人验收。

本页均为未交付工程记录。没有本机编译、日常App操作、真实资料改写、Provider调用、新安装包或新公开发布；其余141–148混合差异和已有Prompt组仍逐项保留在314，整体 `complete:false`。
# cb9 元数据候选的单项复用 / 2026-11-07

另独读cb9的完整元数据读取增量：普通文本块先作为完整候选，失败后才尝试原始alpha候选；不从两个来源拼参数。当前共享Reader/Stealth解码器、Desktop原始alpha适配和共享测试四blob仍与原162一致；当前Desktop集成fixture与fa39一致。原162的Stealth三方法及方向R03原件中实际PNG优先/失败回退和WebP无ImageIO插件两方法共五项PASS仅分源复用，未新跑。适配读取原尺寸alpha，不先旋转或缩略图重采样；显示方向由独立owner负责。只登记cb9的Reader一条delta，不提升全部图片交互、其他元数据增量或真人验收。逐blob和原ZIP SHA/CRC/XML见本地`OWNER_METADATA_CANDIDATE_CHANNEL_REUSE.json`。

