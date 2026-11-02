# 269 — 完整 Prompt 的 Android 与 Suite 配对互换

当前实际验证分层：121/1c的共享5例和Android repository两新例已PASS（Linux总shared1149/Android584）；Desktop新配对例因测试错误state.draft未编译运行，d953仅改真实draft Flow，122运行中，不能提前称该例通过。Suite既有执行者交full-prompt-6133672e6a0c，协调端只读核21冻结源码SHA、同484B原件、checks-r2实际OK(10 tests)，范围为5固定codec+5 Suite真实controller的预览不写/确认/复制/撤销重做重开及两模型位置开关实际请求等价。系统剪贴板、普通Main、原生窗口、外部安装版双端仍未验；当前官方147仅有原正向入口，本Fork同applicationId的源码不是现装官方APK兼容证明，实际Android配套交付对象由协调端询问用户，不擅自覆盖或卸载手机应用。下方实现说明及历史待验状态按其源时间读取。

用户已明确“两者都需要”：Desktop↔CCB Studio Suite及Desktop↔Android CCB。原Desktop三共享加两controller/Scene用例通过，仅证明Desktop内部协议，不代表其他已装应用已支持。本片按原P17-07补当前Fork的Android接线；官方上游只读，不改其发布或宣称当前官方APK已有新入口。Suite由原执行者负责，本源码执行者不写Suite仓库。

共享契约仍为266的`CCB-FULL-PROMPT/1`。Android入口在生图工作室→更多生图选项→完整Prompt文本互换；原“复制正向”和“粘贴覆盖正向提示词”保持原功能。复制采用实际默认/卡/显式选择解析后的模型；复制编码和读取解析均在Default dispatcher执行，保留目的draft完整基线，关闭/取消/重新操作使旧任务和复制回调失效；复制回到Main且基线与所解析默认模型仍相同时才写剪贴板，预览不写draft。只有显式确认且无生成/历史应用、当前draft仍逐值等于基线时才走原updateDraft、撤销、stage与延迟flush，不调用AI、不自动生成。继续使用原退出flush与历史恢复协议。

新预览逐段展开画风、全局正负词、全部角色正负词与开关/坐标。单段视觉预览前8192字符有明确提示，解析与确认采用完整原文，不把裁剪预览写回生成字段。无负词用空值而非沿用目标旧负词；其他参数、连续生成及参考资源保留。新增字段/标题均为传输结构或产品说明，不改AI Prompt模板。

合成配对原件为三测试资源目录下`interop/ccb-full-prompt-v1.txt`，484字节、SHA256 `fb6664013b586923dd1f1d71fb97527dc5e890541f2b9ab74241f986024b66f7`，LF及末尾LF由gitattributes固定。包含V5、自然语言、启用位置、启停两角色、明确坐标、全局/人物负词、被转义标题及空补充段。三处原件逐字相同，允许Suite使用同一合成原件进行其真实读写和组装验证；不含用户卡/图片/密钥。

新增必要证据：共享canonical往返与基线拒绝2例；原Desktop真实controller读取无写→确认→原复制逐字相同→撤销1例；Android真实NovelAiStudioRepository预览无写、stage/flush/新实例重开导出逐字相同及过期候选不覆盖2例。AndroidViewModel/UI由原Android编译链验证，仍需对应设备/系统剪贴板人工与Suite配对实际证据；不能把repository还原例冒称真实设备点击撤销通过。本片待其精确新源云验，不由120或公开1233代验，不发布/安装APK。
