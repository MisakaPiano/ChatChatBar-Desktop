package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.imageprompt.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun desktopHistoryDateFilter(text: String): NovelAiHistoryDateFilter? {
    val value = text.trim()
    if (value.isEmpty()) return null
    return when {
        Regex("\\d{4}").matches(value) -> NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.YEAR, java.time.Year.parse(value).value)
        Regex("\\d{4}-\\d{2}").matches(value) -> java.time.YearMonth.parse(value).let {
            NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.MONTH, it.year, it.monthValue)
        }
        else -> java.time.LocalDate.parse(value).let { NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.DAY, it.year, it.monthValue, it.dayOfMonth) }
    }
}

internal fun desktopHistoryRecipeDetails(entry: NovelAiGenerationHistoryEntry, image: NovelAiGenerationHistoryImage): List<Pair<String, String>> {
    val r = entry.recipe; val s = r.settings; val g = r.imageGuidance
    return buildList {
        add("生成时间" to java.util.Date(entry.createdAt).toString())
        add("模型" to s.model.displayName); add("实际 Seed" to image.seed.toString())
        add("尺寸" to s.imageSize().let { "${it.width} × ${it.height}" })
        add("数量 / Steps" to "${s.count} / ${s.steps}")
        add("CFG / Rescale" to "${s.guidance} / ${s.cfgRescale}")
        add("Sampler / Seed 模式" to "${s.sampler.displayName} / ${s.seedMode}")
        add("画风" to r.stylePrompt); add("基础 Prompt" to r.basePrompt); add("补充 Prompt" to r.extraPrompt)
        add("负面 Prompt" to r.negativePrompt)
        r.characters.forEachIndexed { index, c -> add("角色 ${index + 1}" to c.prompt); add("角色 ${index + 1} 负面" to c.negativePrompt) }
        add("图像引导" to "${g.action} / ${g.referenceMode}")
        add("img2img Strength / Noise" to "${g.imageToImageStrength} / ${g.imageToImageNoise}")
        add("Inpaint Strength / 最小上下文" to "${g.inpaintStrength} / ${g.focusedInpaintMinimumContext}")
        g.focusedInpaintRegion?.let { add("Focus 区域" to it.toString()) }
        add("精确参考" to "${g.preciseReference.type.displayName} · ${g.preciseReference.strength} / ${g.preciseReference.fidelity}")
        add("Vibe 强度归一化" to if (g.normalizeVibeStrengths) "开启" else "关闭")
        g.vibes.forEachIndexed { index, v -> add("Vibe ${index + 1}" to "提取 ${v.informationExtracted} · 强度 ${v.strength} · ${if (v.encodedVibe.isNullOrBlank()) "无编码" else "编码已记录"}") }
        if (g.hasMissingHistorySource()) add("复现限制" to "原始引导图片未保留，应用前必须确认；不是完整复现")
    }
}

@Composable
internal fun DesktopStudioHistory(controller: DesktopNovelAiStudioController,
    onApply: (NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage, NovelAiHistoryApplyMode) -> Unit,
    onPreview: (List<String>, Int) -> Unit, onUse: (String, NovelAiImageUseTarget) -> Unit) {
    val entries by controller.history.collectAsState(emptyList())
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf(emptyMap<Int, NovelAiHistoryFoldPreference>()) }
    var preferencesLoaded by remember { mutableStateOf(false) }
    var savingPreference by remember { mutableStateOf(false) }
    var levels by remember { mutableStateOf(listOf(NovelAiHistoryLevel())) }
    var detail by remember { mutableStateOf<NovelAiHistoryImageItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    LaunchedEffect(controller) {
        try {
            preferences = controller.repository.loadHistoryFoldPreferences()
            preferences[0]?.let { levels = listOf(levels.first().copy(foldEnabled = it.enabled, foldType = it.type)) }
            preferencesLoaded = true
        } catch (_: Exception) { problem = "历史折叠偏好读取失败" }
    }
    val depth = levels.lastIndex
    val level = levels.last()
    var dateInput by remember(depth, level.scope) { mutableStateOf(level.dateFilter?.let {
        when (it.granularity) {
            NovelAiHistoryDateGranularity.YEAR -> it.year.toString()
            NovelAiHistoryDateGranularity.MONTH -> "%04d-%02d".format(it.year, it.month)
            NovelAiHistoryDateGranularity.DAY -> "%04d-%02d-%02d".format(it.year, it.month, it.day)
        }
    }.orEmpty()) }
    fun change(next: NovelAiHistoryLevel) { levels = levels.dropLast(1) + next }
    fun fold(enabled: Boolean, type: NovelAiHistoryFoldType) {
        if (!preferencesLoaded || savingPreference) return
        savingPreference = true
        val preference = NovelAiHistoryFoldPreference(depth, enabled, type)
        scope.launch {
            try {
                // Publish only after the repository's atomic write; navigation cannot cancel it.
                withContext(NonCancellable) { controller.repository.saveHistoryFoldPreference(preference) }
                preferences = preferences + (depth to preference)
                levels = levels.mapIndexed { index, existing ->
                    if (index == depth) existing.copy(foldEnabled = enabled, foldType = type) else existing
                }
                problem = ""
            } catch (_: Exception) { problem = "折叠偏好保存失败" }
            finally { savingPreference = false }
        }
    }
    val filtered = remember(entries, level) {
        NovelAiHistoryFilterPolicy.filter(entries, level.searchQuery, level.dateFilter).filter { item -> level.scope?.contains(item.key) != false }
    }
    val albums = remember(filtered, level.foldEnabled, level.foldType) { foldHistoryImages(filtered, level.foldType.takeIf { level.foldEnabled }) }
    val visibleSelections = remember(filtered) { filtered.map { NovelAiHistoryImageSelection(it.entry.id, it.image.path) }.toSet() }
    LaunchedEffect(visibleSelections) {
        controller.retainHistorySelection(visibleSelections)
        confirmDelete = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioActions {
            if (depth > 0) BootstrapButton("返回上层", enabled = !savingPreference) { levels = levels.dropLast(1) }
            StatusText("${level.label} · ${filtered.size} 张")
        }
        StudioField("搜索正面 Prompt", level.searchQuery) { change(level.copy(searchQuery = it)) }
        StudioField("日期（YYYY / YYYY-MM / YYYY-MM-DD，留空为全部）", dateInput) { dateInput = it }
        StudioActions {
            BootstrapButton("应用日期") { try { change(level.copy(dateFilter = desktopHistoryDateFilter(dateInput))); problem = "" } catch (_: Exception) { problem = "日期无效，请使用年、年月或年月日" } }
            BootstrapButton("清除日期") { dateInput = ""; change(level.copy(dateFilter = null)) }
            if (preferencesLoaded && !savingPreference) {
                StudioToggle("折叠为相册", level.foldEnabled) { fold(!level.foldEnabled, level.foldType) }
                StudioChoice("折叠方式", NovelAiHistoryFoldType.entries, level.foldType, { it.label }) { fold(level.foldEnabled, it) }
            } else StatusText(if (savingPreference) "正在保存折叠偏好" else "折叠偏好尚未载入")
        }
        if (problem.isNotBlank()) StatusText(problem, DesktopBootstrapColors.warning)
        StudioActions {
            BootstrapButton("删除选中 ${state.selected.size}", enabled = state.selected.isNotEmpty()) { confirmDelete = true }
            if (confirmDelete) {
                    BootstrapButton("确认删除所选图片", variant = DesktopActionVariant.DESTRUCTIVE) { scope.launch {
                        controller.retainHistorySelection(visibleSelections)
                        controller.deleteSelected(); confirmDelete = false
                    } }
                BootstrapButton("取消") { confirmDelete = false }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.key }) { album ->
                val item = album.cover; val selection = NovelAiHistoryImageSelection(item.entry.id, item.image.path)
                DesktopOwnedImage(item.image.path, controller.resources::readBytes, Modifier.fillMaxWidth().height(180.dp).clickable(enabled = preferencesLoaded && !savingPreference) {
                    if (album.images.size > 1) {
                        val pref = preferences[depth + 1]
                        levels = levels + NovelAiHistoryLevel(scope = album.images.map { it.key }.toSet(), label = album.label,
                            foldEnabled = pref?.enabled ?: false, foldType = pref?.type ?: NovelAiHistoryFoldType.FULL)
                    } else detail = item
                })
                StatusText(if (album.images.size > 1) "${album.label} · ${album.images.size} 张，点击打开相册" else "${item.entry.recipe.settings.model.displayName} · Seed ${item.image.seed}")
                if (album.images.size == 1) StudioActions {
                    BootstrapButton("详情 / 复用") { detail = item }
                    BootstrapButton(if (selection in state.selected) "取消选择" else "选择图片") { controller.toggleSelection(selection) }
                }
            }
        }
    }
    detail?.let { item -> DialogWindow(onCloseRequest = { detail = null }, title = "历史图片与完整配方") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BootstrapButton("返回历史") { detail = null }
            DesktopOwnedImage(item.image.path, controller.resources::readBytes, Modifier.fillMaxWidth().height(240.dp).clickable {
                onPreview(item.entry.images.map { it.path }, item.batchImageIndex)
            })
            StudioActions { NovelAiHistoryApplyMode.entries.forEach { mode ->
                BootstrapButton(when (mode) { NovelAiHistoryApplyMode.FULL -> "完整复现"; NovelAiHistoryApplyMode.NEW_SEED -> "新种子复用"; NovelAiHistoryApplyMode.SEED_ONLY -> "仅复用种子" }) {
                    detail = null; onApply(item.entry, item.image, mode)
                }
            } }
            StudioActions { NovelAiImageUseTarget.entries.forEach { target -> BootstrapButton("用作${target.displayName}") { detail = null; onUse(item.image.path, target) } } }
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                desktopHistoryRecipeDetails(item.entry, item.image).forEach { (label, value) -> StatusText(label); StatusText(value.ifBlank { "（空）" }) }
            } }
        }
    } }
}
