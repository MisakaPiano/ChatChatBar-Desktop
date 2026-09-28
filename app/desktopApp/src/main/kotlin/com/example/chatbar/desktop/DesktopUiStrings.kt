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
    COMPOSER_HINT("Ctrl+Enter 发送；Enter / Shift+Enter 换行", "Ctrl+Enter sends; Enter / Shift+Enter inserts a newline"),
    TASK("任务", "Task"),
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
}

internal class DesktopUiStrings(private val language: DesktopUiLanguage) {
    operator fun invoke(key: DesktopUiText): String = when (language) {
        DesktopUiLanguage.ZH_CN -> key.zhCn
        DesktopUiLanguage.EN -> key.en
    }

    fun status(message: String): String = if (language == DesktopUiLanguage.EN) message else when (message) {
        "Select or create a session" -> this(DesktopUiText.SELECT_SESSION)
        "Session settings saved" -> "会话设置已保存"
        "Reply length must be positive" -> this(DesktopUiText.REPLY_LENGTH_POSITIVE)
        "Session pin saved" -> this(DesktopUiText.SAVED)
        "Model saved" -> "模型已保存"
        "Default model saved" -> "默认模型已保存"
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
        else -> message
    }
}

internal val LocalDesktopUiStrings = compositionLocalOf { DesktopUiStrings(DesktopUiLanguage.ZH_CN) }
