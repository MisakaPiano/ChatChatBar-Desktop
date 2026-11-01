package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.budget.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopEditorBudgetState(val loading: Boolean = false, val selectedId: String? = null,
    val options: List<DesktopCardDesignOption> = emptyList(), val defaultModel: String = "尚未读取", val error: String? = null) {
    val target get() = PromptEstimateTarget(if (selectedId == null) defaultModel else options.firstOrNull { it.id == selectedId }?.name ?: "所选模型不可用")
}

private fun ModelConfig.editorBudgetLabel() = if (credentialAccount == null) "$displayName · $modelName" else desktopAccountLabel()

/** Only reads model identities; estimate selection does not change chat/AI model settings or send requests. */
internal class DesktopEditorBudgetController(private val settings: SettingsRepository,
    private val models: EffectiveModelResolver, private val gate: DesktopDataOperationCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val mutableState = MutableStateFlow(DesktopEditorBudgetState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val epoch = AtomicLong()
    fun refresh() {
        job?.cancel()
        val token = epoch.incrementAndGet()
        mutableState.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val result = gate.withNormalOperation {
                    val fresh = settings.getAppSettings()
                    models.availableChatModels(fresh).map { DesktopCardDesignOption(it.id, it.editorBudgetLabel()) } to
                        (models.defaultChatModel(fresh)?.editorBudgetLabel() ?: "未配置对话模型")
                }
                mutableState.update { if (epoch.get() == token) it.copy(options = result.first, defaultModel = result.second) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { if (epoch.get() == token) it.copy(error = "读取估算参考模型失败，请刷新后重试") else it } }
        }
        job?.invokeOnCompletion { mutableState.update { if (epoch.get() == token) it.copy(loading = false) else it } }
    }
    fun select(id: String?) { mutableState.update { it.copy(selectedId = id) } }
    suspend fun closeAndDrain() { job?.cancelAndJoin(); scope.coroutineContext[Job]?.cancelAndJoin() }
}

@Composable internal fun DesktopEditorPromptBudget(controller: DesktopEditorBudgetController, entity: Any) {
    var open by remember(controller, entity::class) { mutableStateOf(false) }
    val model by controller.state.collectAsState()
    BootstrapButton("字段 Token 估算", secondary = true) { open = true }
    if (open) DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().heightIn(max = 820.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopManagementHeading("编辑字段 Token 估算") }
                    DesktopIconAction("关闭 Token 估算", DesktopAppIcons.Close) { open = false }
                }
                LaunchedEffect(controller, entity::class) { controller.refresh() }
                val report by produceState<PromptBudgetEstimate?>(null, entity, model.target) {
                    value = withContext(Dispatchers.Default) {
                        when (entity) {
                            is CharacterCard -> EditorPromptBudgetEstimator.character(entity, model.target)
                            is FormatCard -> EditorPromptBudgetEstimator.format(entity, model.target)
                            is WorldBook -> EditorPromptBudgetEstimator.worldBook(entity, model.target)
                            else -> null
                        }
                    }
                }
                StatusText("参考模型：${model.target.modelLabel}")
                StatusText("未加载该模型专用分词器；按字符估算（汉字约 0.8，其他约 0.4），特殊字符误差可能更大。")
                StatusText("模型上下文上限未提供；输出上限不等于上下文容量。")
                StatusText("这里只统计已列编辑字段；未计算变量展开、条目激活、RAG／记忆、历史和请求封装，不是本轮输入总量。")
                if (entity is FormatCard) StatusText("随机数工具的本次展开值尚未生成，不计入静态合计。")
                if (!model.loading) SearchableChoice("估算参考模型", listOf<String?>(null) + model.options.map { it.id } +
                    listOfNotNull(model.selectedId?.takeUnless { id -> model.options.any { it.id == id } }), model.selectedId,
                    { id -> if (id == null) "跟随默认 · ${model.defaultModel}" else model.options.firstOrNull { it.id == id }?.name ?: "所选模型不可用" }, controller::select)
                BootstrapButton("刷新参考模型", secondary = true, enabled = !model.loading, onClick = controller::refresh)
                model.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                report?.let { estimate ->
                    StatusText("已列字段静态合计 ≈ ${estimate.estimatedTextTokens} Token")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(estimate.sections, key = { it.id }) { section ->
                            Column { StatusText("${section.label} · ≈ ${section.estimate.tokens} Token")
                                if (section.note.isNotBlank()) StatusText(section.note, DesktopBootstrapColors.mutedForeground) }
                        }
                    }
                }
            }
        }
    }
}
