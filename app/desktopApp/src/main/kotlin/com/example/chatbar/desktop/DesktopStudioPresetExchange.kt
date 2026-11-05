package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*

/** A preview/confirm file workflow; none of these actions generate images or contact a provider. */
@Composable
internal fun DesktopStudioPresetExchange(controller: DesktopNovelAiStudioController, businessBusy: Boolean, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    fun run(action: suspend () -> Unit) {
        if (working || closing) return
        working = true; notice = ""
        operation = scope.launch {
            try { action() }
            catch (_: CancellationException) { notice = "已请求停止；请核对下方保存或应用结果。" }
            catch (_: Exception) { notice = "文件操作未完成；当前草稿与原文件保留。" }
            finally { working = false }
        }
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                operation?.cancelAndJoin()
                controller.discardGenerationPreset()
                onClose()
            } catch (_: Exception) { closing = false; notice = "临时预设清理未完成，请重试关闭；原文件保留。" }
        }
    }
    DesktopImageToolWindow("生成预设", ::close, width = 680.dp) {
        DesktopStudioPresetExchangeContent(state.presetPreview, businessBusy, working, closing, notice, state.status,
            onExport = { run {
                controller.picker.pickSaveFile(PRESET_TYPE, "ChatBar-generation.ccbpreset")?.let { path ->
                    if (controller.exportGenerationPreset(path)) notice = "已导出：$path"
                }
            } }, onInspect = { run {
                controller.picker.pickOpenFile(PRESET_TYPE)?.let { controller.inspectGenerationPreset(it) }
            } }, onApply = { run { controller.applyGenerationPreset() } },
            onDiscard = { run { controller.discardGenerationPreset() } },
            onStop = { operation?.cancel() }, onClose = ::close)
    }
}

@Composable
internal fun ColumnScope.DesktopStudioPresetExchangeContent(preview: DesktopGenerationPresetPreview?,
    businessBusy: Boolean, working: Boolean, closing: Boolean, notice: String, status: String,
    onExport: () -> Unit, onInspect: () -> Unit, onApply: () -> Unit, onDiscard: () -> Unit,
    onStop: () -> Unit, onClose: () -> Unit) {
    ManageHeading("完整生成预设")
    StatusText("Desktop 预设文件（.ccbpreset），用于本桌面版导入；跨端支持尚未验证。")
    StatusText("包括正负词、角色位置与开关、两种模型参数、连续生成设置及参考图／编码资源。账户、AI 设计对话和本机卡片绑定不会导出。")
    StudioActions {
        StudioAction("导出当前生成预设", enabled = !working && !closing && !businessBusy, onClick = onExport)
        StudioAction("选择预设并预览", enabled = !working && !closing && !businessBusy, onClick = onInspect)
        if (working) StudioAction("停止文件操作", onClick = onStop)
        StudioAction(if (closing) "正在关闭…" else "关闭", enabled = !closing, onClick = onClose)
    }
    if (working) StatusText("正在校验或保存预设资源…")
    if (notice.isNotBlank()) StatusText(notice)
    if (status.isNotBlank()) StatusText(status)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preview == null) StatusText("导入先预览，确认后才替换当前生成草稿；可使用原撤销操作恢复。不会自动开始生成。")
        else {
            val preset = preview.preset
            ManageHeading(preview.fileName)
            StatusText("${preset.selectedModel.displayName} · 角色 ${preset.characters.count { it.enabled }}/${preset.characters.size} · ${if (preset.naturalLanguageMode) "自然语言" else "标签"}模式")
            StatusText(if (preset.continuousModeEnabled) "连续生成已开启：目标 ${preset.continuousTargetCount} 张；应用后仍需手动开始。"
                else "普通批次；不会沿用当前草稿原有的连续生成模式。")
            for (settings in listOf(preset.v45Settings, preset.v5Settings)) {
                val size = settings.imageSize()
                StatusText("${settings.model.displayName}${if (settings.isMedium) " Medium" else ""} · ${size.width}×${size.height} · 数量 ${settings.count} · Steps ${settings.effectiveSteps} · Guidance ${settings.guidance} · ${settings.effectiveSampler.displayName}${if (settings.effectiveTransparentBackground) " · 透明背景" else ""}")
                StatusText("Seed ${if (settings.seedMode == NovelAiSeedMode.RANDOM) "随机" else settings.seed.toString()} · CFG Rescale ${settings.effectiveCfgRescale} · Variety+ ${settings.varietyPlus}")
            }
            val guidance = preset.imageGuidance
            StatusText("${guidance.action.displayName} · 参考模式 ${guidance.referenceMode} · 图片资源 ${guidance.presetAssets().map { it.path }.distinct().size} · 编码 ${guidance.vibes.count { it.encodedVibe != null }}（已校验）")
            DesignTextDisclosure("画风", preset.stylePrompt)
            DesignTextDisclosure("基础 Prompt", preset.basePrompt)
            DesignTextDisclosure("补充 Prompt", preset.extraPrompt)
            DesignTextDisclosure("基础负面 Prompt", preset.negativePrompt)
            preset.characters.forEachIndexed { index, person ->
                StatusText("角色 ${index + 1} · ${if (person.enabled) "启用" else "关闭"} · ${person.center?.let { "位置 ${it.x}, ${it.y}" } ?: "自动位置"}")
                DesignTextDisclosure("角色 ${index + 1} 正向", person.prompt)
                DesignTextDisclosure("角色 ${index + 1} 负向", person.negativePrompt)
            }
        }
    }
    if (preview != null) StudioActions {
        StudioAction("确认替换生成草稿", enabled = !working && !closing && !businessBusy,
            style = StudioActionStyle.PRIMARY, onClick = onApply)
        StudioAction("取消导入", enabled = !working && !closing, onClick = onDiscard)
    }
}

private val PRESET_TYPE = DesktopFileType("CCB 完整生成预设", listOf("ccbpreset"))
