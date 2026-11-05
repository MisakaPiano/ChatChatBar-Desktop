# Studio 双层撤销与输入定位修复

用户在 1236 实测报告：撤销/重做只有一步；Ctrl+Z/Y 无效。最新说明要求栏内历史彼此独立，底部箭头则针对整个 Studio 草稿。另有真实截图确认翻译模式选区覆盖两行、输入法候选框不随当前角色输入栏移动。

## 当前改动（精确云端验证通过，真实系统IME待验）

- Desktop 专属多步持久时间线，最多保留 100 个操作；按实际文字变化范围合并连续输入，明确载入/清空与跨栏操作不合并。新编辑截断重做分支，空操作不消耗历史。写前意图保留旧/新游标，重开按真实草稿选择已提交一侧；损坏或无法匹配时显式拒绝，不抹掉原件。
- 输入栏独立 Ctrl+Z/Y，人物字段以稳定 ID 隔离，参数按模型隔离；切换焦点和人物排序不串栏。展开编辑拥有临时历史，确认再发布，取消保留原栏。全局恢复后重定位受影响栏的历史，避免旧局部 redo 覆盖已恢复内容。没有页面级全局键盘拦截。
- 时间线不嵌入参考图字节，大型 Vibe 使用已有内容地址引用；旧历史仍持有的资源不会被清理。容量裁剪在提交后才交出精确清理候选。备份选择与冲突预览保持草稿/时间线一致。
- 当前 Compose 1.10.3 官方源码的旧输入适配器缓存 focusedRectInRoot；Desktop 在当前字段的请求查询时使用实时布局坐标和原文字形高度。编辑命令、composition、selection 继续交给原输入链。译文只绘制，不进入原文或请求；自绘选区裁剪到原文字形行。

共享仓库只开放正常写入扩展点和已有 Vibe 引用保留方法，不改变 Android 的单份 undo 行为。不改模型 Prompt，不动已公开 1236 包、真实数据或本机程序。

## 验证边界

首轮 history1 生产编译两错、history2 测试编译 Int/Long 两错均保留。history3/41661505579 精确0466原XML：83 方法中62通过、21失败；其中两项为新仓库重开初始化缺少先loadDraft，场景类因JUnit方法返回非Unit而三场景未执行，其余18备份项包含默认TEMP的短文件名与最终句柄长路径不一致拒绝。后继补初始化、显式Unit与沿用已有fresh canonical NTFS TEMP，不放宽路径检查。实际功能测试失败未冒认环境全部免责，后继结果仍待。

history4/41663000551精确a65b02原件88=87PASS+1FAIL/0SKIP：原重开/备份失败已闭合；真实字段CtrlZ/Y、源字带选区像素与原Studio14表现测试通过。唯一失败在平台请求切换第二输入栏的几何断言，后继补精确字段焦点/请求源身份和坐标诊断，保留原5秒期限与第二栏实际根位置断言。另按只读复核恢复数字栏失焦归一、补独立历史/异步echo验证，展开临时history不污染全局动作边界。

云原生native3/41659383846在显示前置拒绝、0方法执行；native4/41660260445未启动即取消、原因未知；native5/41661514818明确返回DEVMODEW公有size188，原错误固定220的假设不成立。后继仅支持已知188/220两种公有前缀，按实际dmSize定位私有尾并完整保存/提交/恢复；其它尺寸继续拒绝。没有可用受支持大模式时留确切屏宽条件，不循环输入或伪造通过。均非本机显示操作。

native6/41663011157原件实际4PASS+1SKIP：scrollbar、Stop下载/生成、Studio edge、chrome通过；long Windows playback因wave设备0未测。实际云屏1024×768临时切到枚举支持的1920×1080，test/apply/restore均0，public188/private0，未写registry且已恢复1024×768。原19SKIP的映射是2+10+3=15原方法完整闭合，原混合Stop仅部分闭合，另3专门音频仍待；不能把拆分的4PASS误算为16原方法全过。

新增独立 Windows 云端工作流 `desktop-studio-history-probe.yml`，使用合成资料测试混合多步、分支、重开、写前恢复、资源、真实字段键盘与平台输入请求。复用相关原有 Studio/备份测试。实际系统中文输入法候选显示仍须用户在后继候选验收；离屏请求/截图通过不能替代该结果。原 native2 的 10 PASS/4 FAIL 保留，另行处理，不被本修复改写。


history5/41667076334在新备份测试可空值跨模块smart cast处编译拒绝，0方法；后继使用assertNotNull返回的稳定局部值，原件保留。history6/41668712390原件92=90PASS+2FAIL/0SKIP：第二栏请求身份与焦点正确，rootTop429.6211相对实际栏顶430.0超出0.3789px；后继把原文字形带约束到真实行边界，保持原根位置断言与5秒期限。另一失败为selected映射校验Vibe引用后未登记selectedMedia，导致payload文件遗漏；补登记且端到端覆盖当前draft与history-only两种来源的selected export/read/map/reopen/原编码resolve。后继只定向重测两个FAIL及两个直接受影响glyph场景，90已绿与native6原件复用，绿后补必要整体验证。真实IME人验和后继公开许可仍未取得。


history7/41672408257精确1076原件4=3PASS+1FAIL/0SKIP：实时第二栏请求、源字选区像素、inline/fullscreen通过；备份越过payload存在检查后，在重开时发现current与timeline不一致。源码追到所选导出按原策略把current.followDefaultNovelAiImageModel投影false，却未对Desktop timeline同样处理。后继对每个committed/pending snapshot执行同样卡/默认模型选择投影，并把历史引用的模型纳入依赖；不清journal、不改恢复一致性断言。备份端到端保留双来源、增加重开前合成字段一致性诊断，下一次只重测该方法与直接受影响selection/merge两suite。


完整155原件保留：main369suite2641=2618PASS+3FAIL+20SKIP，proxy5/shared1233/Android HTML586通过。两反推并发例仍反射新子类getDeclaredField(undoMutex)，但原loadUndoDraft/saveUndoDraft锁实际保留在共享父类；改为反射原父类，不改第二次lock暂停、5秒限时、Stop/过期候选拒绝断言。另一项旧字体高度/基线断言揭示helper契约混淆：保留完整source-font rect及原严格断言，另visible source band负责0.3789px行边界裁剪，平台与实际绘制继续用相同行带。后继仅三FAIL+直接glyph两例+当前Studio原生OS关闭复核；native1确已有同名关闭PASS，20而非19来自Stop拆分，不把它误记新增方法。生产helper分工发生真实源码改动，4493/R29/sign19作为已验证但被后继取代的原包保持，不冒用为新源候选。

## 最终304 / 私有1237收尾

- history8/41674383551原17PASS/0SKIP：actual selected export/read/map/reopen同时覆盖当前draft与history-only图像/Vibe；投影一致性检查保持，不清时间线掩盖问题。
- native7/41681529979原6PASS/0SKIP：两原反推并发、原严格font高度/基线、实时第二栏source/request、实际源字选区像素、Studio原生OS关闭。实际云屏1024×768→受支持1920×1080→恢复1024×768，public188/private0，test/apply/restore均0，不写registry。关闭是native1已有原方法，不冒称新闭合。
- 完整156/41681820343实际checkout c841147f0ccd33ee423af8123b0b2a5c2345da41与应用304bd42a250b062ddaafc4c7dcc55710e94c4e8b完整tree相同（5c495f712085a082b312dadfc5c6b76ebc00e1ea）。Windows原369suite2641=2621PASS/0FAIL/0ERROR/20SKIP；proxy5、NTFS7、native37/TUF14/exit4；Linux成功，shared1233及Android HTML586复用155未变输入的原件。155三FAIL原件保留，后继未降低断言或重写旧结果。
- R31普通候选和R32旧client验包均已实际通过。Release433024800四资产草稿/tag→304保持未公开；EXE178544270B/SHA7878dc17270056db5b44db1783d939ac91efa96d2328a4f6b585bd231f109e4a，独立ZIP191748951B/SHA8a4f9dc73201660e5418c9780ef7f89ffe217b0b04f578dfecc4baed9245735c；manifest549/Program548逐文件核对。R31仅fresh默认合成profile，不证明真实凭据、自选根或所有外部后代。R32受控fetcher发现15→19、完整下载二验，不执行安装器、不冒称用户公网更新。
- notes原CRLF文件1167B/SHA d37979324a780610e9b64f38b360138d0194e5c90d620dc177999fe1fe45995b；规范LF正文1157B/SHA 803c8b65bd032a317206ff4834f87cba2153e904701aaf4ee8e55870f747e9e3。签名19与远端正文绑定后者，原文件摘要单列；无正文修改或重签。root1及公开1236/sign18不变。
- 新1237派生清理实际10文件/1128090667逻辑字节，旧4493/R29二进制与重复外壳删除但原报告/manifest/签名/失败与当前304最终包保留。先前1236外壳删除被自动审核以“blocked by policy”拒绝，0B删除且未重试；T11六I ZIP、作者2857B和未知handoff未触碰。

本版公开许可与真实验收仍待协调端唯一入口；1236原N01a/N01b真实缺陷不改为PASS。原15方法完整闭合，混合Stop物理播放及3专门音频仍未测，云端请求/像素结果不等于真实系统中文IME。原件、OWNER与协调端独立回执在Continuity对应156/native7/R31/R32目录。

## 1237公开回执

2026-11-04 公开1237：用户明确答复“允许先公开1237和匹配签名更新19”。已复用冻结304/R31原包公开Release433024800（desktop-v0.1.0-preview.1237→304bd42a），四资产/正文读回通过；签名19已发布到原codex/update-metadata，commit85a80124bc8e920c897e9cdf1cebffcb725d5be2，root1不变。五role在固定commit与公开branch的十次匿名HTTP逐字节一致，完整178544270B安装包匿名流SHA7878dc17270056db5b44db1783d939ac91efa96d2328a4f6b585bd231f109e4a通过，规范LF notes SHA803c8b65bd032a317206ff4834f87cba2153e904701aaf4ee8e55870f747e9e3与签名一致。实际旧client发现/下载/二验复用R32受控云结果，匿名HTTP不是一次新的公网client执行。没有重建/代装或操作真实资料；1237人验待协调端引导用户自行更新，1236资料/重开PASS和N01原FAIL保留。正式1.0、系统中文IME、物理音频4原scope、真实Provider/长聊天/生产合并等仍按具体场景待验。
