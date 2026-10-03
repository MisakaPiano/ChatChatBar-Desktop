package com.example.chatbar.desktop

import androidx.compose.runtime.compositionLocalOf

/** User-facing Desktop text lives here; persisted data and protocol identifiers are not translated. */
internal enum class DesktopUiText(val zhCn: String, val en: String) {
    CHAT("对话", "Chat"), MANAGE("管理", "Manage"), TOOLS("工具", "Tools"), DATA("数据", "Data"),
    SETTINGS("设置", "Settings"), MODELS("模型", "Models"), TRANSFER("导入导出", "Transfer"),
    WORKSPACE("工作区", "Workspace"), DATA_DIRECTORY("数据目录", "Data directory"),
    DATA_OPERATION("数据操作", "Data operation"), CHAT_WORKSPACE_HINT("会话、消息与任务", "Sessions, messages and tasks"),
    MANAGE_WORKSPACE_HINT("导入、导出与配置", "Import, export and configuration"),
    TOOLS_WORKSPACE_HINT("逻辑 Prompt 检查器", "Logical Prompt Inspector"),
    DATA_WORKSPACE_HINT("数据根目录与迁移", "Data root and migration"),
    WORKSPACE_HINT("当前工作位于主区域。", "Current work stays in the main surface."),
    LANGUAGE("界面语言", "Interface language"), CHINESE("简体中文", "简体中文"), ENGLISH("English", "English"),
    APPEARANCE_DISPLAY("外观与显示", "Appearance and display"), THEME_MODE("主题模式", "Theme mode"),
    FOLLOW_SYSTEM("跟随系统", "System"), LIGHT("浅色", "Light"), DARK("深色", "Dark"),
    THEME_COLOR("主题色", "Theme color"), RESTORE_DEFAULT_COLOR("恢复默认主题色", "Restore default theme color"),
    COLOR_STYLE("配色", "Color style"), COLOR_NEUTRAL("中性", "Neutral"),
    COLOR_CCB_NATIVE("CCB 原生", "CCB native"), COLOR_CUSTOM_ACCENT("自定义强调色", "Custom accent"),
    SAVED("已保存", "Saved"), UNSAVED_CHANGES("有未保存的修改", "Unsaved changes"),
    SAVE_AND_LEAVE("保存并离开", "Save and leave"), DISCARD_CHANGES("放弃修改", "Discard changes"),
    CONTINUE_EDITING("继续编辑", "Continue editing"), SAVE_CHANGES("保存修改", "Save changes"),
    REFRESH("刷新", "Refresh"), WORKING("正在处理…", "Working…"), CLOSE("关闭", "Close"),
    CANCEL("取消", "Cancel"), SAVE("保存", "Save"), EDIT("编辑", "Edit"), DELETE("删除", "Delete"),
    COPY_MESSAGE("复制整条", "Copy whole message"), MESSAGE("消息", "Message"), EDIT_MESSAGE("编辑整条消息", "Edit whole message"),
    MORE_MESSAGE_ACTIONS("更多消息操作", "More message actions"),
    MANAGE_MORE_ACTIONS("更多管理操作", "More management actions"),
    MANAGE_HAS_DRAFT("有草稿", "Draft"),
    MANAGE_RECOVERABLE_DRAFT("可恢复的未完成草稿", "Recoverable unfinished draft"),
    MANAGE_OPEN_DRAFT("打开草稿", "Open draft"),
    MANAGE_DELETE_CONFIRM("确认删除？此操作不可撤销。", "Confirm deletion? This cannot be undone."),
    MANAGE_WORLD_REFERENCED("世界书仍被引用，请先解绑。", "WorldBook is still referenced. Unbind it first."),
    MANAGE_DELETE_COMMITTED("角色删除已提交，但后续清理可能未完成；列表已重新读取。", "Character deletion committed, but follow-up cleanup may be incomplete. The list was refreshed."),
    MANAGE_DUPLICATE_COMMITTED("角色副本已创建，但后续处理可能未完成；列表已重新读取。", "Character copy committed, but follow-up work may be incomplete. The list was refreshed."),
    MANAGE_DELETE_REFRESH_FAILED("角色删除已提交，但列表刷新失败；请稍后重新打开管理页面核对。", "Character deletion committed, but list refresh failed. Reopen Manage to verify."),
    MANAGE_DUPLICATE_REFRESH_FAILED("角色副本已创建，但列表刷新失败；请稍后重新打开管理页面核对。", "Character copy committed, but list refresh failed. Reopen Manage to verify."),
    TRANSFER_IMPORT_COMMITTED("角色导入已提交，但后续处理可能未完成；列表已重新读取。", "Character import committed, but follow-up work may be incomplete. The list was refreshed."),
    TRANSFER_OVERWRITE_COMMITTED("角色覆盖已提交，但后续清理可能未完成；列表已重新读取。", "Character overwrite committed, but follow-up cleanup may be incomplete. The list was refreshed."),
    TRANSFER_IMPORT_REFRESH_FAILED("角色导入已提交，但列表刷新失败；请稍后重新打开管理页面核对。", "Character import committed, but list refresh failed. Reopen Manage to verify."),
    TRANSFER_OVERWRITE_REFRESH_FAILED("角色覆盖已提交，但列表刷新失败；请稍后重新打开管理页面核对。", "Character overwrite committed, but list refresh failed. Reopen Manage to verify."),
    MANAGE_DRAFT_CLEANUP_WARNING("草稿已删除，但后续清理未完成。", "Draft deleted, but subsequent cleanup did not complete."),
    CLIPBOARD_UNAVAILABLE("剪贴板暂时不可用，请重试", "Clipboard temporarily unavailable. Please try again."),
    PREVIOUS_ALTERNATIVE("上一版本", "Previous alternative"), NEXT_ALTERNATIVE("下一版本", "Next alternative"),
    EDIT_WHOLE_MESSAGE("编辑整条", "Edit whole message"), DELETE_WHOLE_MESSAGE("删除整条", "Delete whole message"),
    SEGMENT("本段原文", "Raw segment"), COPY_SEGMENT("复制本段", "Copy segment"),
    EDIT_SEGMENT("编辑本段", "Edit segment"), DELETE_SEGMENT("删除本段", "Delete segment"),
    DELETE_SEGMENT_WARNING("将删除本段内容；如果这是最后一段且没有图片，将删除整条消息。", "Delete this segment. If it is the final text and there are no images, the whole message will be deleted."),
    MODEL_DETAILS("模型详情", "Model details"), HIDE_SESSIONS("收起会话列表", "Hide sessions"),
    SHOW_SESSIONS("展开会话列表", "Show sessions"),
    ASSISTANT_ROLE("助手", "Assistant"), YOU_ROLE("你", "You"), SYSTEM_ROLE("系统", "System"),
    UNLABELED_SPEAKER("未标注", "Unlabeled"),
    DELETE_MESSAGE_CONFIRM("删除消息？", "Delete message?"),
    DELETE_MESSAGE_WARNING("将删除整条消息，且无法撤销。", "This deletes the whole message and cannot be undone."),
    REASONING("推理过程", "Reasoning"), STATUS_OPTIONS("状态 / 选项", "Status / options"),
    RELINK_CHARACTER("重新关联角色", "Relink Character"),
    RELINK_EXPLANATION("为此归档会话选择角色；历史消息和设置保持不变。", "Choose a Character for this archived session. History and settings remain intact."),
    RELINK_NO_CHARACTERS("没有可用角色，请先在管理中导入。", "No Characters available. Import one in Manage first."),
    CONFIRM_RELINK("确认重新关联", "Confirm relink"),
    SESSION_WORLD_BOOKS("会话世界书", "Session WorldBooks"),
    WORLD_BOOK_INHERITED_NOTE("角色继承的世界书只读；额外选择将在保存时生效。", "Character-inherited WorldBooks are read-only; extra selections apply on Save."),
    SEARCH_WORLD_BOOKS("搜索世界书", "Search WorldBooks"),
    CHARACTER_INHERITED("角色继承", "Character inherited"), SELECTED("已选择", "Selected"),
    ADD("添加", "Add"), REMOVE("移除", "Remove"),
    INHERITED_WORLD_BOOK_UNAVAILABLE("继承的世界书不可用", "Inherited WorldBook unavailable"),
    EXTRA_WORLD_BOOK_UNAVAILABLE("额外世界书不可用", "Extra WorldBook unavailable"),
    SESSIONS("会话", "Sessions"), RECENT_SESSIONS("最近对话", "Recent sessions"), PINNED("置顶", "Pinned"),
    SEARCH_SESSIONS("搜索对话", "Search conversations"), NO_MATCHING_SESSIONS("没有匹配的对话", "No matching conversations"),
    NO_SESSIONS("暂无会话", "No sessions"), NEW_CHAT("+ 新建对话", "+ New chat"),
    SEARCH_CHARACTERS("搜索角色", "Search characters"), NO_CHARACTERS("暂无角色，请先在管理中导入", "No characters yet; import one in Manage"),
    NO_MATCHING_CHARACTERS("没有匹配的角色", "No matching characters"),
    SELECT_SESSION("选择或新建会话", "Select or create a session"),
    ARCHIVED("已归档 · 角色缺失", "Archived · character missing"),
    ARCHIVED_READABLE("角色已缺失，历史消息仍可阅读", "Character missing; history remains readable"),
    EXPAND_SUMMARY("展开摘要", "Expand summary"), COLLAPSE_SUMMARY("收起摘要", "Collapse summary"),
    PIN("置顶", "Pin"), UNPIN("取消置顶", "Unpin"), RENAME("修改显示名称", "Rename display title"),
    DISPLAY_TITLE("显示名称（留空则跟随原名）", "Display title (blank follows original)"),
    SESSION_SETTINGS("会话设置", "Session settings"), CLOSE_SETTINGS("关闭会话设置", "Close session settings"),
    SAVE_SESSION_SETTINGS("保存会话设置", "Save session settings"),
    CHAT_MODEL("对话模型", "Chat model"), FORMAT_CARD("格式卡", "FormatCard"),
    FOLLOW_DEFAULT("跟随默认", "Follow default"), UNAVAILABLE("不可用", "Unavailable"),
    REPLY_LENGTH("回复长度", "Reply length"), REPLY_LENGTH_POSITIVE("回复长度必须为正数", "Reply length must be positive"),
    REPLY_LANGUAGE("回复语言", "Reply language"), SUPPLEMENTARY_SETTING("补充设定", "Supplementary setting"),
    PLAYER_NAME_OVERRIDE("玩家名称", "Player name override"), PLAYER_SETTING_OVERRIDE("玩家设定", "Player setting override"),
    LOAD_OLDER("加载更早消息", "Load older"), TOTAL("总数", "total"), GENERATING("正在生成", "generating"),
    SEND("发送", "Send"), CONTINUE("继续生成", "Continue"), STOP("停止", "Stop"),
    REGENERATE("重新生成", "Regenerate"), RETRY_GENERATION("重试生成", "Retry generation"),
    COMPOSER_HINT("Ctrl+Enter 发送；Enter / Shift+Enter 换行", "Ctrl+Enter sends; Enter / Shift+Enter inserts a newline"),
    TASK("任务", "Task"),
    TEST_CONNECTION("测试连接", "Test connection"), TESTING_CONNECTION("正在测试…", "Testing…"),
    STOP_CONNECTION_TEST("停止测试", "Stop test"), TEST_CANCELLED("连接测试已中断", "Connection test cancelled"),
    TEST_SAVED_CONFIGURATION("测试当前已保存的有效配置", "Tests the current saved effective configuration"),
    SAVE_KEY_FIRST("请先保存密钥", "Save the key first"),
    PROBE_SUCCESS("成功", "Success"), PROBE_FAILED("失败", "Failed"),
    CHAT_MODELS("对话模型", "Chat models"), CREATE_MODEL("新建模型", "Create model"),
    ADD_MODEL("+ 添加模型", "+ Add model"), SET_DEFAULT("设为默认", "Set as default"),
    USE_AUTOMATIC("使用自动选择", "Use automatic selection"), CURRENT_DEFAULT_MODEL("当前默认对话模型", "Current default chat model"),
    CURRENT_EFFECTIVE("当前有效", "Currently effective"), AUTOMATIC_SELECTION("自动选择", "Automatic selection"),
    SESSION_SPECIFIED("会话指定", "Session-specific"), FOLLOWS_GLOBAL_DEFAULT("跟随全局默认", "Follows global default"),
    SPECIFIED_UNAVAILABLE("指定模型不可用", "Specified model unavailable"), CURRENT_FALLBACK("当前回退", "Current fallback"),
    CREDENTIAL_SOURCE("凭据来源", "Credential source"), MODEL_SPECIFIC_KEY("模型专用 API Key", "Model-specific API key"),
    GLOBAL_DEFAULT_KEY("全局默认 API Key", "Global default API key"), NO_AUTH("无鉴权（允许的 HTTP 本地模型）", "No authentication (allowed HTTP local model)"),
    TEMPLATE_OPENAI("OpenAI", "OpenAI"), TEMPLATE_CLAUDE("Claude", "Claude"),
    TEMPLATE_GEMINI("Gemini", "Gemini"), TEMPLATE_CUSTOM("自定义", "Custom"),
    NO_SAVED_MODELS("暂无已保存模型", "No saved models"), PRESET("内置", "preset"), CUSTOM("自定义", "custom"),
    DUPLICATE("复制", "Duplicate"), DELETE_MODEL_CONFIRM("确定删除此模型？安全凭据也将被移除。", "Delete this model? Its secure credential will be removed."),
    CONFIRM_DELETE("确认删除", "Confirm delete"), NEW_MODEL("新建模型", "New model"), EDIT_MODEL("编辑模型", "Edit model"),
    DISPLAY_NAME("显示名称", "Display name"), BASE_URL("基础 URL", "Base URL"), MODEL_ID("Model ID（手动输入）", "Model ID (manual entry)"),
    MODEL_API_KEY("模型 API Key", "Model API key"), DISCOVER_IDS("获取模型 ID", "Discover model IDs"),
    DISCOVERING("正在获取…", "Discovering…"), FILTER_IDS("筛选模型 ID", "Filter model IDs"),
    DISCOVERY_NOTE("选择仅修改 Model ID；不代表对话连接可用", "Selection changes only Model ID; listing does not prove chat connectivity"),
    TEMPLATE("模板", "Template"), SELECTABLE_CHAT("可用于对话", "Selectable for chat"), MULTIMODAL("多模态", "Multimodal"),
    VISION_CLEARED("多模态模型会清除视觉模型绑定", "Vision model binding is cleared for multimodal models"),
    VISION_MODEL_ID("视觉模型 ID", "Vision model ID"), THINKING("思考", "Thinking"), DEFAULT("默认", "Default"),
    REASONING_EFFORT("推理强度（留空为默认）", "Reasoning effort (blank = default)"),
    MAX_OUTPUT_TOKENS("最大输出 Tokens（留空为不指定）", "Max output tokens (blank = unset)"),
    OUTPUT_TOKEN_PARAMETER("输出 Token 参数", "Output token parameter"),
    FORMAT_PROMPT_POSITION("格式提示位置", "Format prompt position"),
    SUPPORTS_JSON("支持 JSON 模式", "Supports JSON mode"),
    SUPPORTS_DISABLE_THINKING("支持关闭思考", "Supports disable-thinking"),
    CUSTOM_PARAMETERS("自定义参数", "Custom parameters"), PARAMETER_NAME("参数名称", "Parameter name"),
    TYPE("类型", "Type"), VALUE("值", "Value"), REMOVE_PARAMETER("移除参数", "Remove parameter"),
    ADD_PARAMETER("添加参数", "Add parameter"), SAVE_MODEL("保存模型", "Save model"),
    CLOSE_EDITOR("关闭编辑器", "Close editor"),
    BUILT_IN_MODELS("内置模型", "Built-in models"), RESTORE_BUILT_IN("恢复内置模型", "Restore built-in models"),
    PRESET_RESTORE_NOTE("恢复内置模型默认值；已有逻辑模型 ID 的安全凭据由模型仓库保留。", "Restores bundled defaults; the model repository preserves secure credentials for existing logical model IDs."),
    CHAT_MODEL_COUNT("个对话模型", "chat models"), EMBEDDING_MODEL("嵌入模型", "Embedding model"),
    SETTINGS_NOT_LOADED("设置尚未加载", "Settings not loaded"), LOAD_SETTINGS("加载设置", "Load settings"),
    CHAT_DEFAULTS("对话默认设置", "Chat defaults"), PLAYER_SETTING("玩家设定", "Player setting"),
    BUILT_IN_FORMATS("内置格式卡", "Built-in FormatCards"), IMPORT_RESTORE("导入/恢复", "Import / restore"),
    PRESET_VERSION("预制版本", "Preset version"), SECURE_KEY_SAVED("已安全保存", "Saved securely"),
    KEY_CLEARED("已清除", "Cleared"), SAVE_KEY("保存密钥", "Save key"),
    CORE_CHAT_SETTINGS("对话设置", "Core chat settings"), DEFAULT_CHAT_MODEL("默认对话模型", "Default chat model"),
    AUTOMATIC_FIRST("自动选择（首个可用模型）", "Automatic (first available)"),
    SELECTED_UNAVAILABLE("已选模型不可用", "Selected model unavailable"),
    ALLOW_CLEARTEXT("允许明确配置的 HTTP 模型端点", "Allow explicit cleartext HTTP model endpoints"),
    CLEARTEXT_NOTE("仅允许明确配置的 http:// 模型；本地模型空密钥不发送 Authorization。", "Permits explicitly configured http:// models; a blank local key sends no Authorization header."),
    CONTEXT_WINDOW("默认上下文窗口大小", "Default context-window size"), DEFAULT_FORMAT_CARD("默认格式卡", "Default FormatCard"),
    NONE("无", "None"), SEGMENTED_BUBBLES("助手消息分段气泡", "Segmented assistant bubbles"),
    MODEL_CONNECTION("模型连接", "Model connection"), SILICONFLOW("硅基流动", "SiliconFlow"),
    GLOBAL_API_KEY("硅基流动 / 全局默认 API Key", "SiliconFlow / Global Default API Key"),
    KEY_USAGE_NOTE("内置硅基流动模型，以及未单独填写密钥的 HTTPS 模型，可使用此全局默认 API Key。", "Built-in SiliconFlow models and HTTPS models without their own key can use this global default API Key."),
    STORED_SECURELY("已安全保存", "stored securely"), NOT_CONFIGURED("未配置", "not configured"),
    KEEP("保持不变", "Keep"), REPLACE("设置/替换密钥", "Set / replace key"), CLEAR("清除", "Clear"),
    NEW_KEY("新密钥", "New key"), ON("开启", "On"), OFF("关闭", "Off"),
    SAVE_CHAT_SETTINGS("保存对话设置", "Save chat settings"), PLAYER("玩家", "Player"),
    PLAYER_NAME("玩家名称", "Player name"), PLAYER_PERSONA("玩家设定", "Player persona"),
    SAVE_PLAYER("保存玩家设定", "Save player setting"),
    CURRENT_DATA_DIRECTORY("当前运行数据目录", "Current running data directory"),
    AUTHORITY("来源", "Authority"), CHANGE_DATA_DIRECTORY("更改数据目录…", "Change data directory…"),
    SELECTED_DESTINATION("选定目标目录", "Selected destination"),
    MIGRATION_EXPLANATION("CCB 会先创建安全快照，再复制当前数据。源目录保留；目标目录须通过安全检查；成功切换后需要重启应用。不会移动或删除源数据。", "CCB copies current data after a safety snapshot. The source is retained, the destination must pass safety checks, and a successful switch requires a restart. The source is not moved or deleted."),
    CONFIRM("确认", "Confirm"), MIGRATING("正在迁移数据…应用会保持打开，直到稳定化完成。", "Migrating data… The application remains open until stabilization finishes."),
    CLOSE_DEFERRED("迁移期间暂缓关闭。", "Close is deferred while migration is in progress."),
    MIGRATION_IN_PROGRESS("迁移进行中", "Migration in progress"),
    RETRY_DESTINATION("重试此目标目录", "Retry this destination"), CHOOSE_ANOTHER("选择其他目录…", "Choose another…"),
    NEXT_START_DIRECTORY("下次启动数据目录", "Next-start data directory"),
    ATTEMPTED_DESTINATION("尝试的目标目录（来源未确认）", "Attempted destination (authority not confirmed)"),
    EXIT_APPLICATION("退出应用", "Exit application"),
    TYPED_TRANSFER("分类导入与导出", "Typed import / export"),
    CHARACTERS("角色", "Characters"), IMPORT_CHARACTER("导入角色", "Import Character"),
    EXPORT_JSON("导出 JSON", "Export JSON"), EXPORT_CCB_PNG("导出 CCB PNG", "Export CCB PNG"),
    FORMATS("格式卡", "Formats"), IMPORT_FORMAT("导入格式卡", "Import Format"),
    WORLD_BOOKS("世界书", "World Books"), IMPORT_WORLD_BOOK("导入世界书", "Import WorldBook"),
    EXPORT_CHATBAR_JSON("导出 ChatBar JSON", "Export ChatBar JSON"),
    EXPORT_SILLYTAVERN_JSON("导出 SillyTavern JSON", "Export SillyTavern JSON"),
    NAME_CONFLICT("名称冲突", "Name conflict"), OVERWRITE("覆盖", "Overwrite"),
    IMPORT_AS_NEW("作为新条目导入", "Import as new"), NO_ITEMS("暂无条目", "No items"),
    PROMPT_INSPECTOR("Prompt 检查器", "Prompt Inspector"),
    LOGICAL_REQUEST("逻辑请求 / 与传输无关", "Logical request / transport-neutral"),
    NOT_PROVIDER_REQUEST("此处不是序列化后的服务商 HTTP 请求。", "This is not a serialized provider HTTP request."),
    INSPECT_USER("检查选中的 USER 消息", "Inspect selected USER"),
    PERSISTED_SESSION("已保存会话", "Persisted session"), NO_PERSISTED_SESSIONS("暂无已保存会话", "No persisted sessions"),
    PERSISTED_USER_MESSAGE("已保存 USER 消息", "Persisted USER message"),
    NO_USER_MESSAGES("当前会话没有已保存的 USER 消息", "The selected session has no persisted USER messages"),
    INSPECTION_INPUTS("检查输入", "Inspection inputs"), EFFECTIVE_CONTEXT_REQUIRED("有效上下文窗口大小（必填）", "Effective context-window size (required)"),
    GLOBAL_PLAYER_NAME("全局玩家名称", "Global player name"), GLOBAL_PLAYER_PERSONA("全局玩家设定", "Global player persona"),
    DEFAULT_FORMAT_ID("默认格式卡 ID", "Default FormatCard ID"), RAG_MODE("RAG 注入模式", "RAG injection mode"),
    EXCLUDE_ASSISTANT_STATUS("排除助手状态", "Exclude assistant status"),
    LOADING("正在加载…", "Loading…"), CACHE("缓存", "Cache"),
    WORLD_BOOK_EVIDENCE("世界书证据", "WorldBook evidence"), ORDERED_MESSAGES("按序排列的逻辑消息", "Ordered logical messages"),
    CACHEABLE_PREFIX("可缓存的稳定前缀", "Cacheable stable prefix"),
    PREFIX_MESSAGE_COUNT("稳定前缀消息数", "Stable-prefix message count"),
    LOGICAL_CACHE_KEY("逻辑 promptCacheKey", "Logical promptCacheKey"),
    NO_WORLD_BOOK_DIAGNOSTICS("暂无世界书诊断", "No WorldBook diagnostics"),
    WORLD_BOOK_PROMPT("世界书 Prompt", "WorldBook prompt"), WORLD_BOOK_OUTLETS("世界书出口", "WorldBook outlets"),
    PERSISTED_TIMED_STATE("已保存定时状态", "Persisted timed state"),
    PROPOSED_TIMED_STATE("拟议定时状态", "Proposed timed state"),
    YES("是", "yes"), NO("否", "no"), EMPTY("空", "empty"),
    CHARACTER_MANAGEMENT("角色管理", "Character management"), NEW_CHARACTER("新建角色", "New Character"),
    EDIT_CHARACTER("编辑角色", "Edit Character"), RECOVERED_DRAFT("已恢复未保存草稿", "Unsaved draft recovered"),
    DRAFT_SAVING("正在保存草稿", "Saving draft"), DISCARD_DRAFT("放弃草稿", "Discard draft"),
    CHARACTER_NAME("角色卡名称", "Character card name"), BOT_NAME("助手名称", "Bot name"),
    GREETING("开场白", "Greeting"), ALTERNATE_GREETINGS("备用开场白", "Alternate greetings"),
    ADD_GREETING("添加备用开场白", "Add alternate greeting"),
    STRUCTURED_MODE("结构化", "Structured"), FREEFORM_MODE("自由文本", "Freeform"),
    MODE_SWITCH_NOTE("切换模式不会删除另一模式的内容", "Switching modes keeps the other mode's content"),
    BASIC_SETTING("基础设定", "Basic setting"), FREEFORM_BODY("自由文本人物设定", "Freeform character text"),
    IMAGE_PROMPT("图片提示词", "Image prompt"), NEGATIVE_IMAGE_PROMPT("反向图片提示词", "Negative image prompt"),
    SYSTEM_PROMPT_FIELD("系统提示词", "System prompt"), POST_HISTORY_FIELD("历史后指令", "Post-history instructions"),
    MESSAGE_EXAMPLE("对话示例", "Message example"), CREATOR_NOTES("创作者备注", "Creator notes"),
    CHARACTER_INFOS("人物信息", "Character entries"), ADD_CHARACTER_INFO("添加人物", "Add character entry"),
    PROFILE("简介", "Profile"), APPEARANCE("外貌", "Appearance"), CLOTHING("服装", "Clothing"),
    ABILITIES("能力", "Abilities"), HABITS("习惯", "Habits"), BACKGROUND_FIELD("背景", "Background"),
    RELATIONSHIPS("关系", "Relationships"), SPEAKING_STYLE("说话方式", "Speaking style"),
    CHARACTER_WORLD_BOOKS("角色世界书", "Character WorldBooks"), CHARACTER_DEFAULT_FORMAT("角色默认格式卡", "Character default FormatCard"),
    UNAVAILABLE_BINDING("不可用的既有绑定", "Unavailable existing binding"),
    REFERENCE_DOCUMENTS("参考文档", "Reference documents"), NEW_TEXT_DOCUMENT("新建文本文档", "New text document"),
    IMPORT_TEXT_DOCUMENT("导入文本文档", "Import text document"), DOCUMENT_NAME("文档名称", "Document name"),
    DOCUMENT_BODY("文档内容", "Document content"), SAVE_DOCUMENT_EDIT("更新文档", "Update document"),
    CLEAR_DOCUMENTS("清空文档", "Clear documents"), CLEAR_DOCUMENTS_CONFIRM("清空当前角色草稿中的全部参考文档？", "Clear all reference documents from this Character draft?"),
    CHARACTER_IMAGES("角色图片", "Character images"), AVATAR_IMAGE("角色头像", "Character avatar"),
    CHAT_BACKGROUND_IMAGE("聊天背景", "Chat background"), APPEARANCE_IMAGE("人物外貌图", "Appearance image"),
    CHOOSE_IMAGE("选择图片", "Choose image"), CLEAR_IMAGE("清除图片", "Clear image"),
    IMAGE_MISSING("图片不可读取", "Image unavailable"),
    COPY_AS_NEW("复制为新角色", "Copy as new Character"), SAVE_AS_NEW("另存为新角色", "Save as new Character"),
    COMMUNITY_READ_ONLY("社区下载角色只读；可复制为新角色编辑", "Downloaded Community Character is read-only; copy it to edit"),
    CHARACTER_SOURCE_CHANGED("原角色已在别处修改；不会覆盖较新版本", "Source Character changed elsewhere; newer data will not be overwritten"),
    CHARACTER_SOURCE_DELETED("原角色已删除；可另存为新角色", "Source Character was deleted; save as new to recover"),
    CHARACTER_NAME_REQUIRED("请输入角色卡名称", "Enter a Character card name"),
    CHARACTER_GREETING_REQUIRED("请输入开场白", "Enter a greeting"),
    CHARACTER_ENTRY_NAME_REQUIRED("非空人物信息需要名称", "A non-empty Character entry needs a name"),
    CHARACTER_DUPLICATE_NAME("角色卡名称已存在", "Character card name already exists"),
    CHARACTER_DUPLICATE_ENTRY("人物名称不能重复", "Character entry names must be unique"),
    CHARACTER_SAVE_FAILED("角色保存失败；草稿仍保留", "Character save failed; draft retained"),
    CHARACTER_DRAFT_FAILED("草稿保存失败", "Draft save failed"),
    CHARACTER_RESOURCE_FAILED("资源处理失败", "Resource operation failed"),
    CHARACTER_NEW_DRAFT_EXISTS("已有未保存的新角色草稿；请先处理该草稿，再另存当前角色", "An unsaved new Character draft already exists. Handle it before saving this Character as new"),
    CHARACTER_DOCUMENT_READ_FAILED("文档无法读取；不会将其作为空文档覆盖", "Document cannot be read; it will not be overwritten as empty"),
    CHARACTER_SAVE_COMMITTED_WARNING("角色已保存，但后续缓存或清理未完成；请检查草稿状态", "Character saved, but cache or cleanup did not complete; check draft state"),
    CHARACTER_CLEAN_DRAFT_WARNING("过期角色草稿清理未完成；请重试", "Obsolete Character draft cleanup did not complete; retry"),
    CHARACTER_RETRY_CLEANUP("重试草稿清理", "Retry draft cleanup"),
    CHARACTER_IMPORT_DATA("导入其他角色卡人物", "Import characters from another card"),
    CHARACTER_IMPORT_SOURCE("来源角色卡", "Source Character card"),
    CHARACTER_IMPORT_PERSON("来源人物", "Source person"),
    CHARACTER_IMPORT_SECTIONS("选择要覆盖的分段", "Select sections to import"),
    CHARACTER_IMPORT_ACTION("导入到当前草稿", "Import into current draft"),
    CHARACTER_IMPORT_RESULT("已新建 {created} 人，更新 {updated} 人", "Created {created}, updated {updated}"),
    CHARACTER_IMPORT_EMPTY("没有可导入的结构化角色卡", "No structured Character cards to import"),
    CHARACTER_CONVERT_FREEFORM("将结构化人物转换为自由文本", "Convert structured characters to freeform"),
    CHARACTER_CONVERT_WARNING("现有自由文本人物设定将被转换结果覆盖；结构化人物仍会保留。", "Existing freeform character text will be replaced. Structured characters will remain."),
    CHARACTER_CONVERT_CONFIRM("确认覆盖并转换", "Replace and convert"),
    FORMAT_MANAGEMENT("格式卡管理", "FormatCard management"),
    SEARCH_FORMATS("搜索格式卡", "Search FormatCards"),
    NEW_FORMAT("新建格式卡", "New FormatCard"),
    EDIT_FORMAT("编辑格式卡", "Edit FormatCard"),
    NO_FORMATS("暂无匹配的格式卡", "No matching FormatCards"),
    FORMAT_NAME("格式卡名称", "FormatCard name"),
    FORMAT_CONTENT("Prompt 格式要求", "Prompt format requirement"),
    FORMAT_DEFAULT_FLAG("默认格式卡标记", "Default FormatCard flag"),
    FORMAT_DEFAULT_EXPLANATION("格式卡默认标记；全局聊天默认由管理/对话默认设置决定", "FormatCard default flag; global chat fallback is selected in management/chat defaults"),
    GLOBAL_DEFAULT("全局默认", "Global default"),
    SESSION_SUMMARY("会话摘要", "Session summary"),
    PERSON_AVATAR_BOOK("人物头像册", "Person avatar book"),
    SELECT_PERSON("选择或添加人物以编辑资料与头像", "Select or add a person to edit their details and avatar"),
    SET_GLOBAL_DEFAULT("设为全局默认", "Set as global default"),
    CHARACTER_LIST_COUNTS("{characters} 个人物 · {documents} 份文档", "{characters} characters · {documents} documents"),
    FORMAT_TOOLS("按顺序排列的用户工具", "Ordered user tools"),
    FORMAT_ADD_RANDOM("添加随机数", "Add random number"),
    FORMAT_ADD_SUFFIX("添加强提示词尾缀", "Add strong prompt suffix"),
    FORMAT_RANDOM("随机数", "Random number"),
    FORMAT_SUFFIX("强提示词尾缀", "Strong prompt suffix"),
    FORMAT_MIN("最小值", "Minimum"), FORMAT_MAX("最大值", "Maximum"),
    FORMAT_SUFFIX_TEXT("尾缀文本", "Suffix text"),
    FORMAT_UP("上移", "Move up"), FORMAT_DOWN("下移", "Move down"),
    FORMAT_NAME_REQUIRED("请输入格式卡名称", "Enter a FormatCard name"),
    FORMAT_CONTENT_REQUIRED("请输入 Prompt 格式要求", "Enter the Prompt format requirement"),
    FORMAT_DUPLICATE_NAME("格式卡名称已存在", "FormatCard name already exists"),
    FORMAT_INVALID_TOOL("用户工具配置无效", "Invalid user tool configuration"),
    FORMAT_MIN_INVALID("最小值必须是 32 位整数", "Minimum must be a 32-bit integer"),
    FORMAT_MAX_INVALID("最大值必须是 32 位整数", "Maximum must be a 32-bit integer"),
    FORMAT_MAX_LESS_THAN_MIN("最大值不能小于最小值", "Maximum cannot be less than minimum"),
    FORMAT_SUFFIX_REQUIRED("请输入强提示词尾缀", "Enter a strong prompt suffix"),
    FORMAT_SOURCE_CHANGED("原格式卡已在别处修改；不会覆盖较新版本", "Source FormatCard changed elsewhere; newer data will not be overwritten"),
    FORMAT_SOURCE_DELETED("原格式卡已删除；可另存为新卡恢复", "Source FormatCard was deleted; save as new to recover"),
    FORMAT_SAVE_AS_NEW("另存为新格式卡", "Save as new FormatCard"),
    FORMAT_NEW_DRAFT_EXISTS("已有未保存的新格式卡草稿；请先处理该草稿", "An unsaved new FormatCard draft already exists; handle it first"),
    FORMAT_DRAFT_FAILED("格式卡草稿保存或清理失败", "FormatCard draft save or cleanup failed"),
    FORMAT_SAVE_FAILED("格式卡保存失败；草稿仍保留", "FormatCard save failed; draft retained"),
    FORMAT_SAVE_WARNING("格式卡已保存，但草稿清理未完成", "FormatCard saved, but draft cleanup did not complete"),
    FORMAT_CLEAN_DRAFT_WARNING("过期草稿清理未完成；请重试", "Obsolete draft cleanup did not complete; retry"),
    FORMAT_KEEP_DRAFT_AND_LEAVE("保留草稿并离开", "Keep draft and leave"),
    WORLD_MANAGEMENT("世界书管理", "WorldBook management"),
    WORLD_SEARCH("搜索世界书", "Search WorldBooks"),
    WORLD_NEW("新建世界书", "New WorldBook"), WORLD_EDIT("编辑世界书", "Edit WorldBook"),
    WORLD_NONE("暂无匹配的世界书", "No matching WorldBooks"),
    WORLD_NAME("名称", "Name"), WORLD_DESCRIPTION("描述", "Description"),
    WORLD_SCAN_DEPTH("扫描深度", "Scan depth"), WORLD_TOKEN_BUDGET("Token 预算（留空为不限制）", "Token budget (blank = unlimited)"),
    WORLD_RECURSIVE("递归扫描", "Recursive scanning"),
    WORLD_CASE("区分大小写", "Case-sensitive"), WORLD_WHOLE("整词匹配", "Whole-word matching"),
    WORLD_ENTRIES("条目", "Entries"), WORLD_ADD_ENTRY("添加条目", "Add entry"),
    WORLD_EDIT_ENTRY("编辑条目", "Edit entry"), WORLD_ENTRY_NAME("条目名称", "Entry name"),
    WORLD_PRIMARY_KEYS("主触发词（英文逗号分隔）", "Primary keys (comma-separated)"),
    WORLD_SECONDARY_KEYS("二级触发词（英文逗号分隔）", "Secondary keys (comma-separated)"),
    WORLD_ENTRY_CONTENT("条目内容", "Entry content"),
    WORLD_ENABLED("启用", "Enabled"), WORLD_CONSTANT("常驻", "Constant"),
    WORLD_INSERTION_ORDER("插入顺序", "Insertion order"),
    WORLD_POSITION("插入位置", "Position"), WORLD_BEFORE("角色设定之前", "Before Character"),
    WORLD_AFTER("角色设定之后", "After Character"), WORLD_OUTLET("Outlet 位置", "Outlet position"),
    WORLD_OUTLET_NAME("Outlet 名称", "Outlet name"),
    WORLD_LOGIC("二级逻辑", "Secondary logic"),
    WORLD_AND_ANY("AND ANY（任一）", "AND ANY"), WORLD_NOT_ALL("NOT ALL（非全部）", "NOT ALL"),
    WORLD_NOT_ANY("NOT ANY（全无）", "NOT ANY"), WORLD_AND_ALL("AND ALL（全部）", "AND ALL"),
    WORLD_REGEX("Regex 触发词", "Regex keys"),
    WORLD_FOLLOW_BOOK("跟随世界书", "Follow WorldBook"),
    WORLD_YES_MATCH("开启", "On"), WORLD_NO_MATCH("关闭", "Off"),
    WORLD_MATCH_DESCRIPTION("额外匹配角色描述", "Match Character description"),
    WORLD_MATCH_PERSONALITY("额外匹配角色性格", "Match Character personality"),
    WORLD_MATCH_SCENARIO("额外匹配背景场景", "Match scenario"),
    WORLD_MATCH_CREATOR("额外匹配作者备注", "Match creator notes"),
    WORLD_MATCH_PERSONA("额外匹配玩家设定", "Match player persona"),
    WORLD_IGNORE_BUDGET("忽略 Token 预算", "Ignore token budget"),
    WORLD_EXCLUDE_RECURSION("递归时排除", "Exclude from recursion"),
    WORLD_PREVENT_RECURSION("阻止由本条目递归", "Prevent recursion from this entry"),
    WORLD_DELAY_RECURSION("仅递归触发", "Only trigger recursively"),
    WORLD_PROBABILITY("触发概率（0–100）", "Probability (0–100)"),
    WORLD_GROUP("分组", "Group"), WORLD_GROUP_WEIGHT("分组权重", "Group weight"),
    WORLD_ENTRY_SCAN_DEPTH("扫描深度覆盖（留空跟随书）", "Entry scan depth (blank = book)"),
    WORLD_STICKY("Sticky（消息数）", "Sticky (messages)"),
    WORLD_COOLDOWN("Cooldown（消息数）", "Cooldown (messages)"),
    WORLD_DELAY("Delay（消息数）", "Delay (messages)"),
    WORLD_SAVE_ENTRY("保存条目", "Save entry"), WORLD_DISMISS_ENTRY("关闭条目编辑", "Dismiss entry editor"),
    WORLD_DELETE_ENTRY_CONFIRM("删除这个世界书条目？", "Delete this WorldBook entry?"),
    WORLD_IMPORT_CHARACTER("导入结构化角色信息", "Import structured Character information"),
    WORLD_IMPORT_SELECT_CARD("选择角色卡", "Select Character card"),
    WORLD_IMPORT_SELECT_CHARACTERS("选择人物信息", "Select Character entries"),
    WORLD_IMPORT_ACTION("加入世界书草稿", "Add to WorldBook draft"),
    WORLD_NO_STRUCTURED_CHARACTERS("暂无可导入的结构化角色卡", "No eligible structured Character cards"),
    WORLD_CLEAR_SOURCE_CONFIRM("是否立即清空原角色卡中对应的导入分段？世界书仍需单独保存。", "Clear the imported sections in the source Character now? The WorldBook still needs a separate Save."),
    WORLD_KEEP_SOURCE("保留原角色信息", "Keep source information"),
    WORLD_CLEAR_SOURCE("确认清空原角色分段", "Confirm clearing source sections"),
    WORLD_HELP("世界书帮助", "WorldBook help"),
    WORLD_HELP_BOOK("扫描深度决定回看消息数；Token 预算限制本书一次注入的内容。递归扫描可由已触发条目继续触发其他条目。", "Scan depth controls how many messages are checked; token budget limits content injected from this book. Recursive scanning can trigger entries from activated entry content."),
    WORLD_HELP_TRIGGERS("主触发词命中后，二级触发词按所选逻辑进一步筛选；常驻条目无需触发词。插入位置可选角色设定前后或同名 Outlet；插入顺序与列表顺序不同。", "Primary keys trigger an entry; secondary keys apply the selected logic. Constant entries need no trigger. Position places content before/after Character or in a named Outlet; insertion order differs from list order."),
    WORLD_HELP_TIMING("概率控制命中后生效机会；同组条目按分组权重选择。Sticky 保持、Cooldown 冷却、Delay 延迟触发，均按消息数计算。", "Probability controls activation chance; group weight selects among entries in a group. Sticky, Cooldown, and Delay are measured in messages."),
    WORLD_HELP_MATCHING("Regex 可使用表达式触发词；整词与大小写可逐条跟随书级设置或覆盖。忽略预算、递归时排除、阻止后续递归、仅递归触发分别控制预算和递归边界。", "Regex enables expression keys; whole-word and case matching can follow or override book settings. Ignore budget, exclude from recursion, prevent further recursion, and recursive-only control those boundaries."),
    WORLD_NAME_REQUIRED("请输入世界书名称", "Enter a WorldBook name"),
    WORLD_DUPLICATE_NAME("世界书名称已存在", "WorldBook name already exists"),
    WORLD_SCAN_DEPTH_INVALID("扫描深度必须是非负整数", "Scan depth must be a nonnegative integer"),
    WORLD_TOKEN_BUDGET_INVALID("Token 预算必须留空或为非负整数", "Token budget must be blank or a nonnegative integer"),
    WORLD_ENTRY_REQUIRED("条目名称、主触发词和内容不能同时为空", "Entry name, primary keys, and content cannot all be blank"),
    WORLD_ENTRY_CHANGED("条目列表已变化；请重新打开条目", "Entry list changed; reopen the entry"),
    WORLD_ENTRY_MODAL_OPEN("请先保存或关闭条目编辑", "Save or dismiss the entry editor first"),
    WORLD_SOURCE_CHANGED("原世界书已在别处修改；不会覆盖较新版本", "Source WorldBook changed elsewhere; newer data will not be overwritten"),
    WORLD_SOURCE_DELETED("原世界书已删除；可另存为新书恢复", "Source WorldBook was deleted; save as new to recover"),
    WORLD_SAVE_AS_NEW("另存为新世界书", "Save as new WorldBook"),
    WORLD_NEW_DRAFT_EXISTS("已有未保存的新世界书草稿；请先处理该草稿", "An unsaved new WorldBook draft already exists; handle it first"),
    WORLD_DRAFT_FAILED("世界书草稿保存或清理失败", "WorldBook draft save or cleanup failed"),
    WORLD_SAVE_FAILED("世界书保存失败；草稿仍保留", "WorldBook save failed; draft retained"),
    WORLD_SAVE_WARNING("世界书已保存，但缓存或草稿清理未完成", "WorldBook saved, but cache or draft cleanup did not complete"),
    WORLD_CLEAN_DRAFT_WARNING("过期世界书草稿清理未完成；请重试", "Obsolete WorldBook draft cleanup did not complete; retry"),
    WORLD_IMPORT_FAILED("角色信息导入失败", "Character import failed"),
    WORLD_CLEAR_FAILED("原角色信息清空失败", "Source Character clear failed"),
    WORLD_RETRY_CLEANUP("重试草稿清理", "Retry draft cleanup"),
    WORLD_KEEP_DRAFT_AND_LEAVE("保留草稿并离开", "Keep draft and leave"),
}

internal class DesktopUiStrings(private val language: DesktopUiLanguage) {
    operator fun invoke(key: DesktopUiText): String = when (language) {
        DesktopUiLanguage.ZH_CN -> key.zhCn
        DesktopUiLanguage.EN -> key.en
    }

    fun status(message: String): String = if (language == DesktopUiLanguage.EN) when (message) {
        "未配置可用默认对话模型" -> "No usable default chat model configured"
        "默认对话模型/API Key 未配置" -> "Default chat model/API Key is not configured"
        else -> message
    } else when (message) {
        "Select or create a session" -> this(DesktopUiText.SELECT_SESSION)
        "Reply is outside the active context; regeneration is unavailable" -> "该回复已不在直接上下文中，无法重新生成"
        "Connection test unavailable" -> "无法执行连接测试，请检查已保存配置"
        "Session settings saved" -> "会话设置已保存"
        "Reply length must be positive" -> this(DesktopUiText.REPLY_LENGTH_POSITIVE)
        "Session pin saved" -> this(DesktopUiText.SAVED)
        "Model saved" -> "模型已保存"
        "Default model saved" -> "默认模型已保存"
        "Global FormatCard default saved" -> "全局默认格式卡已保存"
        "Unable to save global FormatCard default" -> "无法保存全局默认格式卡"
        "Save or discard Chat Defaults before changing the global FormatCard" -> "请先保存或放弃对话默认设置草稿，再更改全局默认格式卡"
        "FormatCard no longer exists" -> "格式卡已不存在"
        "FormatCard imported" -> "格式卡已导入"
        "Credential saved securely" -> this(DesktopUiText.SECURE_KEY_SAVED)
        "Credential cleared" -> this(DesktopUiText.KEY_CLEARED)
        "Model duplicated" -> "模型已复制"
        "Model deleted" -> "模型已删除"
        "Built-in models restored" -> "内置模型已恢复"
        "Settings saved" -> "设置已保存"
        "Player setting saved" -> "玩家设定已保存"
        "Configure a usable chat model" -> "请先配置可用的对话模型"
        "No usable chat model" -> "没有可用的对话模型"
        "Archived session: character is missing" -> this(DesktopUiText.ARCHIVED)
        "Unable to save chat draft" -> "无法保存对话草稿"
        "Chat draft persistence is closing" -> "对话草稿保存正在关闭"
        "Unable to load models" -> "无法加载模型"
        "Unable to restore built-in models" -> "无法恢复内置模型"
        "Unable to load settings" -> "无法加载设置"
        "Unable to save model" -> "无法保存模型"
        "Unable to delete model" -> "无法删除模型"
        "Unable to save settings" -> "无法保存设置"
        "Unable to save default model" -> "无法保存默认模型"
        "Unable to save credential" -> "无法安全保存密钥"
        "Unable to clear credential" -> "无法清除密钥"
        "Unable to import bundled FormatCard" -> "无法导入内置格式卡"
        "Session no longer exists" -> "会话已不存在"
        "Character no longer exists" -> "角色已不存在"
        "Message no longer exists" -> "消息已不存在"
        "Message cannot be empty" -> "消息不能为空"
        "Message editing is unavailable during generation" -> "生成期间无法编辑消息"
        "Message deletion is unavailable during generation" -> "生成期间无法删除消息"
        "Unable to open model" -> "无法打开模型"
        "Model no longer exists" -> "模型已不存在"
        "Saved model is unavailable" -> "已保存模型不可用"
        "Unable to duplicate model" -> "无法复制模型"
        "Enter a key or choose Clear" -> "请输入密钥或选择清除"
        "Open settings before saving" -> "请先打开设置再保存"
        "Context window size must be positive" -> "上下文窗口大小必须为正数"
        "Model discovery failed" -> "获取模型 ID 失败"
        "Display name is required" -> "请输入显示名称"
        "Base URL is required" -> "请输入基础 URL"
        "Model ID is required" -> "请输入 Model ID"
        "Max output tokens must be positive" -> "最大输出 Tokens 必须为正数"
        "Custom parameter name is required" -> "请输入自定义参数名称"
        "Custom parameter names must be unique" -> "自定义参数名称不能重复"
        "Custom number must be finite" -> "自定义数值必须为有限数"
        "Custom boolean must be true or false" -> "自定义布尔值必须为 true 或 false"
        "Unable to save player setting" -> "无法保存玩家设定"
        "Generating…" -> "正在生成…"
        "Completed" -> "已完成"
        "Stopped by user" -> "已由用户停止"
        "Cancelled" -> "已取消"
        "Task failed" -> "任务失败"
        "Task runtime is closing" -> "任务运行时正在关闭"
        else -> message
    }
}

internal val LocalDesktopUiStrings = compositionLocalOf { DesktopUiStrings(DesktopUiLanguage.ZH_CN) }

internal fun DesktopTransferCommittedNotice.uiText(): DesktopUiText = when (operation) {
    com.example.chatbar.domain.card.CharacterTransferPostCommitOperation.IMPORT ->
        if (reconciled) DesktopUiText.TRANSFER_IMPORT_COMMITTED else DesktopUiText.TRANSFER_IMPORT_REFRESH_FAILED
    com.example.chatbar.domain.card.CharacterTransferPostCommitOperation.OVERWRITE ->
        if (reconciled) DesktopUiText.TRANSFER_OVERWRITE_COMMITTED else DesktopUiText.TRANSFER_OVERWRITE_REFRESH_FAILED
    com.example.chatbar.domain.card.CharacterTransferPostCommitOperation.DUPLICATE ->
        if (reconciled) DesktopUiText.MANAGE_DUPLICATE_COMMITTED else DesktopUiText.MANAGE_DUPLICATE_REFRESH_FAILED
    com.example.chatbar.domain.card.CharacterTransferPostCommitOperation.DELETE ->
        if (reconciled) DesktopUiText.MANAGE_DELETE_COMMITTED else DesktopUiText.MANAGE_DELETE_REFRESH_FAILED
}
