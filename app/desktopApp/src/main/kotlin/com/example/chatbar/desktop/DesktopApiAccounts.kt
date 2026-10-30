package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.desktop.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val LocalDesktopApiAccounts = staticCompositionLocalOf<DesktopApiAccountController?> { null }
internal class DesktopApiAccountController(val store: DesktopAccountStore, val models: ModelRepository,
    private val gate: AppDataOperationGate, private val modelPolicy: DesktopModelCredentialPersistencePolicy? = null) {
    val opened = MutableStateFlow<DesktopAccountService?>(null)
    private suspend fun change(service: DesktopAccountService, block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        gate.withNormalOperation {
            if (service == DesktopAccountService.LLM) {
                models.getAllModels() // Initialize before acquiring the shared model-credential mutex.
                if (modelPolicy != null) modelPolicy.withAccountMutation(block) else block()
                models.refreshModelsFromStorage()
            } else block()
        }
    }
    private suspend fun missingReferences(id: String): Set<String> {
        val missing = mutableSetOf<String>()
        store.referencingModels(id).forEach { model ->
            when (models.readDurableModel(model)) {
                com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Missing -> missing += model
                is com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Valid -> Unit
                else -> error("MODEL_REFERENCE_UNAVAILABLE")
            }
        }
        return missing
    }
    suspend fun select(service: DesktopAccountService, id: String?, model: com.example.chatbar.data.local.entity.ModelConfig?) = change(service) {
        val latest = if (service == DesktopAccountService.LLM) {
            val read = models.readDurableModel(requireNotNull(model).id)
            require(read is com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Valid)
            read.value
        } else null
        store.select(service, id, latest)
    }
    suspend fun save(service: DesktopAccountService, id: String?, name: String, provider: String, endpoint: String, credential: String?) = change(service) {
        if (service == DesktopAccountService.LLM && id != null) missingReferences(id).forEach(store::detachMissingModel)
        store.saveAccount(service, id, name, provider, endpoint, credential)
    }
    suspend fun delete(service: DesktopAccountService, id: String) = change(service) {
        store.deleteAccount(service, id, if (service == DesktopAccountService.LLM) missingReferences(id) else emptySet())
    }
}

@Composable internal fun DesktopApiAccountEntry(service: DesktopAccountService = DesktopAccountService.LLM) {
    val controller = LocalDesktopApiAccounts.current ?: return
    DesktopCurrentAccountLabel(service)
    BootstrapButton("API 账户…", secondary = true, icon = DesktopAppIcons.Settings) { controller.opened.value = service }
}

@Composable internal fun DesktopCurrentAccountLabel(service: DesktopAccountService) {
    val controller = LocalDesktopApiAccounts.current ?: return
    val revision by controller.store.revision.collectAsState()
    val label by produceState("正在读取账户…", service, revision) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (service == DesktopAccountService.LLM) "LLM 账户：按模型选择"
            else {
                val id = controller.store.selected(service)
                controller.store.accounts(service).firstOrNull { it.id == id }?.let { "当前账户：${it.provider} · ${it.name}" }
                    ?: "当前账户：原有单密钥配置"
            }
        }.getOrElse { "账户信息不可用，请检查受保护存储" } }
    }
    StatusText(label)
}

@Composable internal fun DesktopApiAccountDialog() {
    val controller = LocalDesktopApiAccounts.current ?: return
    val opened by controller.opened.collectAsState()
    val service = opened ?: return
    val revision by controller.store.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var modelId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val models by produceState<List<com.example.chatbar.data.local.entity.ModelConfig>>(emptyList(), service, revision, reload) {
        try { value = if (service == DesktopAccountService.LLM) withContext(Dispatchers.IO) { controller.models.getAllModels() } else emptyList() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "模型清单无法读取，请检查配置后刷新" }
    }
    val model = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    val accounts = remember(service, revision, reload) { runCatching { controller.store.accounts(service) }
        .getOrElse { error = "账户清单无法读取；原配置保留"; emptyList() } }
    val selected = remember(service, revision, reload, model?.id) { runCatching { controller.store.selected(service, model?.id) }.getOrNull() }
    fun perform(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); onSuccess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "操作未确认。请核对名称、服务地址、密钥和账户引用，再刷新清单确认结果。" }
            finally { busy = false }
        }
    }
    fun leave(action: () -> Unit) { if (editing) discard = action else action() }
    fun clearDraft() { editing = false; id = null; name = ""; provider = ""; endpoint = ""; credential = "" }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxHeight(.9f).background(DesktopBootstrapColors.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ManageHeading("API 命名账户")
                    BootstrapButton("刷新清单", secondary = true, enabled = !busy) { error = null; reload++ }
                    BootstrapButton("关闭", secondary = true, enabled = !busy) { leave { controller.opened.value = null } }
                }
                ActionRow { DesktopAccountService.entries.forEach { item ->
                    BootstrapButton(when (item) { DesktopAccountService.LLM -> "LLM"; DesktopAccountService.NOVELAI -> "NovelAI"; DesktopAccountService.FISH -> "Fish Audio" },
                        secondary = service != item, enabled = !busy) { leave { clearDraft(); controller.opened.value = item } }
                } }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusText("密钥保存在 Windows 受保护存储中；保存和切换不会发送请求。切换仅影响后续任务，不自动轮换账户。")
                    StatusText("命名账户可随整应用备份保存；目前仅验证 Desktop 恢复，跨端迁移需核对目标版本支持。")
                    if (service == DesktopAccountService.LLM) {
                        if (model == null) StatusText("请先在模型页添加模型，再为其选择账户。")
                        else CompactChoice("配置模型", models, model, { "${it.displayName} · ${it.modelName}" }) { modelId = it.id }
                        StatusText("服务平台由你填写；协议模板不代表实际供应商。账户服务地址必须与所选模型一致。")
                    }
                    BootstrapButton(if (selected == null) "已选择：原有单密钥配置" else "使用原有单密钥配置", secondary = true,
                        enabled = !busy && (service != DesktopAccountService.LLM || model != null)) {
                        perform { controller.select(service, null, model) }
                    }
                    accounts.forEach { account ->
                        Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ManageHeading("${if (selected == account.id) "✓ " else ""}${account.name} · ${account.provider}")
                            if (account.endpoint.isNotBlank()) StatusText(account.endpoint)
                            ActionRow {
                                BootstrapButton("选择", secondary = selected != account.id, enabled = !busy && (service != DesktopAccountService.LLM || model != null)) {
                                    perform { controller.select(service, account.id, model) }
                                }
                                BootstrapButton("编辑", secondary = true, enabled = !busy) { leave {
                                    id = account.id; name = account.name; provider = account.provider; endpoint = account.endpoint; credential = ""; editing = true
                                } }
                                BootstrapButton("删除", secondary = true, enabled = !busy) { perform { controller.delete(service, account.id) } }
                            }
                        }
                    }
                    if (!editing) BootstrapButton("添加账户", enabled = !busy) { clearDraft(); editing = true
                        provider = when (service) { DesktopAccountService.LLM -> ""; DesktopAccountService.NOVELAI -> "NovelAI"; DesktopAccountService.FISH -> "Fish Audio" }
                        endpoint = model?.baseUrl.orEmpty()
                    }
                    else {
                        LabeledField("账户名称", name) { name = it }
                        LabeledField("实际服务平台", provider) { provider = it }
                        if (service == DesktopAccountService.LLM) LabeledField("服务地址", endpoint) { endpoint = it }
                        LabeledField(if (id == null) "API Key / Token" else "替换密钥（留空保留）", credential, secret = true) { credential = it }
                        ActionRow {
                            BootstrapButton("保存账户", enabled = !busy && name.isNotBlank() && provider.isNotBlank() && (id != null || credential.isNotBlank())) {
                                val submitted = credential.takeIf { it.isNotBlank() }; val oldId = id; val label = name; val vendor = provider; val url = endpoint
                                perform(onSuccess = { clearDraft() }) { controller.save(service, oldId, label, vendor, url, submitted) }
                                credential = "" // never populate a saved key back into an editor
                            }
                            BootstrapButton("结束编辑", secondary = true, enabled = !busy) { clearDraft() }
                        }
                    }
                    error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                }
            }
            discard?.let { action -> Column(Modifier.widthIn(max = 420.dp).background(DesktopBootstrapColors.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusText("放弃当前账户编辑？")
                ActionRow {
                    BootstrapButton("继续编辑", secondary = true) { discard = null }
                    BootstrapButton("放弃编辑") { discard = null; clearDraft(); action() }
                }
            } }
        }
    }
}
