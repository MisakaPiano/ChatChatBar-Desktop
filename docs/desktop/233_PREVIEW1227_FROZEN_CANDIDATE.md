# 1227 冻结候选：未公开

2026-11-01。应用源码 `003c782fa34de6f2e07882bd9f4c9dd1a6599193`，版本 `0.1.0-preview.1227`；单独打包控制源 `073a20607ef3f245f0ed1d976ee681e4b2028560`。后续仅文档提交不改变冻结身份。

范围是确认式会话删除、资料目录持久代理和安全连接阶段诊断，以及此前已验证的角色卡／世界书AI候选与Token参考估算。原Prompt文字未改写。P13只有共享基础与生成核心提取，尚未接通Desktop朋友圈生成或启动自动任务，不能当作P13交付。

## 工程证据

- CI67／41289365666 两job完整SUCCESS；Windows实际XML为291套、2357项，2338通过、19跳过、0失败／错误。shared1103、Android582通过；003c与d843三个生产树完全相同，既有实际报告可复用。CI65及CI66原失败保留。
- 云端候选R06／41289702505完整SUCCESS，先做删除／网络定向验证再打包。普通1225→1227升级、稳定入口重开、三次正常关闭与Job排空、运行中安装器拒绝及合成标记保留通过。
- 独立版中文入口实际启动、创建自身资料／锁、正常关闭和全部自身Program路径进程退出，外部bootstrap保持。未验证外部可执行文件后代、像素或真人操作；普通合成生命周期未覆盖三份假凭据保留或自选资料根busy专测。
- 原始artifact ZIP大小和SHA核对后，仅提取已知包文件。独立版547个Program文件逐一匹配audit和安装清单共用项；548项安装清单额外的`install-manager.exe`为安装管理器，非独立版缺损。UserData为空。
- 读取日常1225已有更新器代码，在I盘新缓存离线验证revision10→11、精确包下载及安装前二次验包。信任根不变，未运行安装器或改写日常程序。首轮验证脚本沿用旧相对目录而停止，修正本地路径后r02通过；不是产品认证失败。
- 云端Scene核对SYSTEM和HTTP保存待重启在420／1100宽度下的字段、按钮和提示；不代替真实原生窗口或用户验收。

## 最终文件

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| 安装EXE | 175142053 | `9c6f60123f7a7c4ac1d56d19edeae2806e50ec562d7141f994a26a3870095c12` |
| 独立ZIP | 188357862 | `5751a26be2a6fa30fbee19e037bcc4b871936118d5e209ed8f4135c49826bea7` |

发行草稿430467486已上传两文件及各自`.sha256`，实际tag、target commit、说明、四资产大小／散列已读回，仍`draft:true`。[草稿入口](https://github.com/MisakaPiano/ChatChatBar-Desktop/releases/tag/untagged-2cc835cde78dc62ad0f4)不是公开下载入口。

本地回执目录：`I:/CCB-Desktop-Continuity/updates/20261101-candidate1227-preparation/`。包括`FROZEN_REVIEW.json`、`CI_RUN_READBACK.json`、`RELEASE_DRAFT_RESULT.private.json`、`signed-client-validation-r02/result.json`、原始native记录、文件散列和最短手动清单。真实报告和私钥不在Git或上传产物。

## 仍待与权限

1226先公开测试许可仅适用于1226。1227尚无公开许可，签名revision11仅本地暂存，未推公共metadata。公开仍1226／d94及metadata10／6af10f14；没有代装日常H。

真实NAI超时根因和原环境复测待：先保留原VPN节点／启动参数与SYSTEM模式记录基准，再另测手动代理保存、重开及固定阶段耗时；不擅改用户环境或强制HTTP／TLS策略。TCP到代理及无凭据curl401均不能证明完整账户请求成功。

原67项PNG／PDF／Word真人导出仍待复测，单条仍NOT_TESTED；云端合成结果不能替代。用户尚未明确回来时不催验收，独立已授权工作继续。
