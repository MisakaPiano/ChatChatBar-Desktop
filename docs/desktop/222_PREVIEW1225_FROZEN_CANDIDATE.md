# 1225 冻结候选与用户交付起点

2026-11-01 JST。包源固定 `54008bc17847a30fc53e403f99e8aaa335521cda`，版本 `0.1.0-preview.1225`／installer `0.1.1225`。**公开／日常仍1224，未代装，用户尚未授权1225提前公开测试，也未验收1225。** 后续源码不得混入本冻结候选。

## 范围与实际结果

215的PNG／PDF／可编辑Word及路径复制、217的代理路由／本地模型指引、218的NAI分类／主聊天结束原因、219–220的Studio账户与原始模型读取恢复、221的备份本机IO分类进入本批。具体源码／定向结果见对应文件，不按一个子项提升整个phase或parity。

| 检查 | 实际结果与限定 |
| --- | --- |
| 完整CI49／41261407630 | exact54008 terminal SUCCESS；Android/Test and package124715325156和Windows124715325342均SUCCESS。没有据run/job回执推测artifact测试计数。旧47／48因后续源取代而取消，不记PASS。 |
| 普通NSIS r02 | 新输出构建成功3m27；image audit、版本／source／文件清单与编译器身份绑定。installer 173,732,979B，SHA256 `04a35d64ba9958e305bb247c1f217bbfcd1507fa1753661ee2520e54032a96c0`。不是普通日常根安装验收。 |
| 独立ZIP | 186,944,605B，SHA256 `6fde6bd9e593cb5f8717370ad838718ddddeff11cb57ca525a2545190e409619`；独立逐文件核547个Program条目／270,442,056B，含6个零字节；其余只有中文入口、说明和空UserData，无现用profile。实际中文入口→自身资料目录→正常关闭所有进程PASS，日常pointer／bootstrap／settings保持；未注入假数据或调用Provider。 |
| Synthetic安装升级／重开 | 同一冻结普通image做独立synthetic安装身份；现有受管假资料1224→1225实际升级、ready、稳定入口、root busy、正常关闭／进程排水、旧草稿／图片及3项假DPAPI凭据回读PASS；运行中重装exit2且pointer／manifest不改。随后稳定入口重开PASS。不是普通H日常根／用户资料验收，也不是SF12故障确认或SF14像素证据。 |
| 可信更新离线 | 本次签名metadata revision9只在本地。实际现用1224的原客户端／bootstrap，使用新I盘缓存、精确URL的离线fetcher，完成签名／期限／版本source／说明／installer校验及二次验包；不执行安装器、不接触现用缓存。公共通道仍revision8。 |

I盘目录 `I:/CCB-Desktop-SmallFixes-20261028/` 的 `candidate1225-freeze-r02.json`、`image1225-r02-audit.json`、`package1225-r02/`、`signed1225-client-validation-r01/`、`INDEPENDENT_1225_NATIVE_R01_RESULT.private.json` 和 `native1224-to1225-r01.log`／`native1225-reopen-r01.log` 保留原始限定证据。隔离安装case仍为任务所有的 `windows-runtime-nsis-diagnostic1215-r03`，程序根在I盘；H日常current保持1224。

## 保留的构建／检查失败

首轮package1225-r01因已有updater运行时目录触发 `NEW_RUNTIME_DESTINATION_REQUIRED`，没有生成新安装器。旧运行时323文件大小／SHA及精确集合均匹配，不以删除或跳过校验处理。54008把Gradle构建配方与updater输入一同纳入输出key；新目录生成后重复prepare明确UP-TO-DATE／SUCCESS21秒，new-only保护与locked artifacts保持。原失败log、旧运行时及独立前后key回执保留。

首个freeze检查器误把PDFBox core与IO两个依赖当作应只有一个，断言失败另存；按两个实际必需模块分别核对后通过，没有改包／代码来掩盖。冻结image确有Word/PDF类、两PDF依赖和一条应用级系统代理opt-in。

## 草稿与尚需用户决定的步骤

GitHub发行草稿ID **430303168**，实际tag_name `desktop-v0.1.0-preview.1225`、真Git tag绑定54008，draft=true／prerelease=true，4资产长度与server digest核对、说明与本地签名notes一致。草稿html_url是GitHub正常的untagged草稿URL，不据其外观误称实际tag不匹配。当前没有公开Release或提交metadata分支。

依据AGENTS“公开pre-release需真实用户验收结果”，已直接问用户：允许先公开1225测试Preview＋匹配签名信息供其自行应用内更新，还是保留草稿手动安装候选验收。未有答复不能发布，不把1224的许可或沉默沿用为1225验收；不代装日常根。

最短验收清单：`I:/CCB-Desktop-SmallFixes-20261028/manual1225-checklist.md`；说明：`release-notes1225.md`。重点是原资料／草稿重开、含图Word/PDF和路径复制、Studio／模型入口与备份到预览，不要求付费API或故意改坏真实配置。SF14单独留完整窗口人工截图及明确落点，普通人工截图也不代表自动捕获恢复；SF12 native故障／OS锁专项仍按其未测范围保留。

当前候选冻结且已交用户决定。等待只挂安装／验收／公开等实际依赖动作；独立P12格式卡AI等接续应另Git检查点，复用原官方语义，不改54008候选。社区最后、T23等待上游、SF10／自由正文绑定等具体暂缓保留。
