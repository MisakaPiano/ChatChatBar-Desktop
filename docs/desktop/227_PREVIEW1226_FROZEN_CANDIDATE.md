# 1226 冻结候选与导出修复交付

2026-11-01。应用冻结于 `d94e2df0803095d17ebf453529facee438fc42ad`，不包含后续 B2 共享策略源码。现用 H 程序仍为1225，真实资料、凭据及启动目录未改。

## 用户问题与结果边界

用户在67项选择中遇到 PNG／PDF／Word 的 PREVIEW_RENDER。此前“单条可以”的答复已被用户撤回，单条仍为 NOT_TESTED。1225 的 Studio／模型配置、草稿正常退出重开两项已通过；导出未通过。360退出后的现用548个程序文件长度及SHA全匹配，没有缺失；这不能证明历史隔离原因或全部用户图片完整。

修复把长截图按完整布局有界分块渲染，PNG仍保存为一张完整长图，PDF连续分页；Word直接由所选逻辑内容生成可编辑文字和内嵌图片，不再依赖PNG预览。保留资源、容量、取消、源快照与新文件发布检查；失败投影显示具体类别和计数。格式卡AI空白新建能力也在本包内，原卡不自动覆盖。

## 必要检查与最终字节

- 精确d94完整CI53／41268127116 SUCCESS：Windows2312项中2293通过、19跳过、无失败；原逐像素对照、分页六项、直接Word UI与格式卡AI七项通过，Android/shared及后续步骤通过。CI52原失败及空白末块保留，未删除断言；d94修正默认垂直居中导致的分块坐标偏移。云端为合成67项及像素证据，真实聊天尚待用户实测。
- 云端R04／41270742645生成包，控制源c181e536；该run原生步骤失败，整体仍为FAIL。R05／41272231079，控制请求3862768，复用R04精确原始artifact和最终包字节、不重编译，整体SUCCESS。
- EXE：173873723B，SHA256 `fda0ac71c46bc43c440e8485d48ce0f4b578c76dd3665546fb27c9f8a4088291`。
- 独立ZIP：187079949B，SHA256 `9fee34720ec7d6aa1f1c351153e9cca0713f38e216f8ab26af999ff1c35def36`；547个Program文件逐项核对，空UserData。
- R05实际完成1225普通基线、1226升级、稳定入口重开，三次正常窗口关闭且Job全部结束；运行中安装器exit2拒绝且pointer／manifest保持。实际PID镜像与manifest散列确认旧1225临时updater Python的runtime cwd，另行记录例外，未把baseline的allVersionCwdsClear=false改成true；候选两次为true。
- 独立中文入口实际启动、创建自有资料及锁、正常退出且Program内进程结束，外部bootstrap保持。外部可执行文件后代、像素、真人验收不在此结果内。普通原生本轮仅新默认资料和canary，没有声称重测三份假DPAPI凭据或自定义资料根忙碌专项。
- 本机只完成轻量文件／签名核对，未恢复Gradle／JVM渲染。现用1225更新器在独立I缓存从旧metadata9加载新签名10、下载最终EXE并二次验包通过；无公开网络、无执行安装器。信任根保持。

## 草稿与下一步

发行草稿430360318已上传四资产，API实际tag为 `desktop-v0.1.0-preview.1226`，source=d94，draft/prerelease=true；GitHub草稿html_url仍含untagged，不冒认公开链接。四资产长度和digest、说明读回一致。公开更新metadata仍为revision9／1225，签名revision10仅本机准备。

已直接询问用户本次1226先公开测试并自行应用内更新，或保留草稿手动安装验收；尚无本版批准。1225先公开许可不延用到本版，沉默不算许可。此前R01工具下载SHA、R02跨API时间语义、R03探针、R04原生失败均保留。独立B2的bf／CI54已SUCCESS，但桌面生成／研究／图像和候选UI仍未完成，不混入本包。

本机材料：`I:/CCB-Desktop-SmallFixes-20261028/candidate1226-freeze-r01.json`、`RELEASE1226_DRAFT_RESULT.private.json`、`signed1226-client-validation-r01/result.json`、`manual1226-checklist.md`。发布后只需重点重测原选择的PNG首尾与分块接缝、PDF顺序、Word可编辑及嵌图，再核对升级后的原资料。
