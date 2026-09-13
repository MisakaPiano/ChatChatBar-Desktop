package com.example.chatbar.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class SettingsCategory(val id: String, val title: String, val summary: String)

class SettingsEntry(
    val id: String,
    val category: String,
    val title: String,
    val keywords: String = "",
    val searchable: Boolean = true,
    val searchItems: List<String> = emptyList(),
    val content: @Composable () -> Unit
)

@Stable
class SettingsBrowserState {
    var category by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var target by mutableStateOf<String?>(null)
    var detailTarget by mutableStateOf<String?>(null)
    internal val scrollStates = mutableMapOf<String, ScrollState>()
    fun open(categoryId: String, entryId: String? = null, detail: String? = null) {
        category = categoryId
        target = entryId
        detailTarget = detail
    }
    fun back() { category = null; target = null; detailTarget = null }
}

@Composable
fun SettingsBrowser(
    categories: List<SettingsCategory>,
    entries: List<SettingsEntry>,
    state: SettingsBrowserState,
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val selected = categories.firstOrNull { it.id == state.category }
    BackHandler(active && selected != null) { state.back() }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CbIconButton(AppIcons.ArrowBack, "返回设置分类", state::back)
                CbText(selected.title, style = ChatBarTheme.typography.heading)
            }
        }
        key(selected?.id ?: "home") {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(state.scrollStates.getOrPut(selected?.id ?: "home") { ScrollState(0) })
                    .padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(if (selected == null) 0.dp else 14.dp)
            ) {
                if (selected == null) {
                    CbInput(state.query, { state.query = it }, placeholder = "搜索设置")
                    Spacer(Modifier.height(8.dp))
                    if (state.query.isBlank()) {
                        categories.forEach { category ->
                            SettingsLink(category.title, category.summary) { state.open(category.id) }
                        }
                    } else {
                        val terms = state.query.trim().split(Regex("\\s+"))
                        val results = entries.flatMap { entry ->
                            val categoryTitle = categories.firstOrNull { it.id == entry.category }?.title.orEmpty()
                            if (!entry.searchable) emptyList() else (listOf(entry.title) + entry.searchItems).distinct()
                                .filter { title -> terms.all { term -> "$title ${entry.keywords} $categoryTitle".contains(term, ignoreCase = true) } }
                                .map { title -> entry to title }
                        }
                        if (results.isEmpty()) CbText("没有匹配的设置", color = ChatBarTheme.colors.mutedForeground)
                        results.forEach { (entry, title) ->
                            SettingsLink(title, categories.firstOrNull { it.id == entry.category }?.title.orEmpty()) {
                                state.open(entry.category, entry.id, title.takeIf { it != entry.title })
                            }
                        }
                    }
                } else {
                    entries.filter { it.category == selected.id }.forEach { entry ->
                        key(entry.id) {
                            val requester = remember { BringIntoViewRequester() }
                            LaunchedEffect(state.target) {
                                if (state.target == entry.id) {
                                    withFrameNanos { }
                                    requester.bringIntoView()
                                    state.target = null
                                }
                            }
                            CompositionLocalProvider(LocalSettingsTarget provides (state.target == entry.id),
                                LocalSettingsDetailTarget provides state.detailTarget) {
                                Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) { entry.content() }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun SettingsLink(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            CbText(title, style = ChatBarTheme.typography.label)
            if (summary.isNotBlank()) CbText(summary, style = ChatBarTheme.typography.caption,
                color = ChatBarTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CbIcon(AppIcons.KeyboardArrowRight, null, Modifier.size(18.dp), ChatBarTheme.colors.mutedForeground)
    }
    CbDivider()
}

/** Refresh untouched values only. Source emissions must never erase an in-progress edit. */
@Composable
fun <T> rememberSettingDraft(source: T): MutableState<T> {
    val draft = remember { mutableStateOf(source) }
    var baseline by remember { mutableStateOf(source) }
    if (baseline != source) {
        if (draft.value == baseline) draft.value = source
        baseline = source
    }
    return draft
}

@Composable
fun SettingsDetails(title: String, summary: String, forceOpen: Boolean = LocalSettingsTarget.current || LocalSettingsDetailTarget.current == title, fullscreen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(forceOpen) { if (forceOpen) open = true }
    SettingsLink(title, summary) { open = true }
    if (open && fullscreen) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CbSurface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
                CbTopBar(title, navigation = { CbIconButton(AppIcons.ArrowBack, "返回设置", { open = false }) })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompositionLocalProvider(LocalSettingsTarget provides false) { content() }
                }
            }
        }
    }
    if (open && !fullscreen) CbDialog(onDismissRequest = { open = false }, title = title,
        confirm = { CbButton("完成", { open = false }) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompositionLocalProvider(LocalSettingsTarget provides false) { content() }
        }
    }
}

private val LocalSettingsTarget = compositionLocalOf { false }
private val LocalSettingsDetailTarget = compositionLocalOf<String?> { null }

@Composable
fun SettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, label: String) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = label }
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        // The parent owns input and semantics; the visual must not intercept taps.
        CbSwitch(checked, null, Modifier.clearAndSetSemantics {}, enabled = enabled)
    }
}

@Composable
fun SettingsSavingDialog() {
    CbDialog(onDismissRequest = {}, title = "正在保存", dismissOnClickOutside = false, dismissOnBackPress = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CbSpinner(Modifier.size(24.dp))
            CbText("保存完成后可继续操作。")
        }
    }
}

@Composable
fun UnsavedSettingsDialog(onSave: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit, busy: Boolean = false) {
    CbDialog(onDismissRequest = { if (!busy) onContinue() }, title = "有未保存的设置",
        confirm = { CbButton("保存并继续", onSave, enabled = !busy) },
        dismiss = { CbButton("继续编辑", onContinue, variant = ButtonVariant.Ghost, enabled = !busy) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbText("保存普通设置后继续，或放弃本次未保存修改。已立即生效的外观及已保存密钥不会撤销。")
            CbButton("放弃修改并继续", onDiscard, variant = ButtonVariant.Outline, enabled = !busy)
        }
    }
}
