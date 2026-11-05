package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*

@Composable internal fun DesktopStylePresetGallery(controller: DesktopCharacterEditorController, currentPrompt: String,
    enabled: Boolean, close: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val catalog by produceState(NovelAiStyleCatalogLoadResult(isLoading = true), controller, reload) {
        value = controller.loadStyleCatalog()
    }
    DesktopImageToolWindow("内置画风图集", close, width = 920.dp, height = 760.dp) {
        DesktopStyleGalleryContent(catalog, currentPrompt, enabled,
            { reference -> controller.imageBytes(reference) ?: error("画风例图不可读取") },
            { preset -> if (controller.applyStylePreset(preset)) close() }, { reload++ }, close)
    }
}

@Composable internal fun DesktopStyleGalleryContent(catalog: NovelAiStyleCatalogLoadResult, currentPrompt: String,
    enabled: Boolean, read: (String) -> ByteArray, apply: (NovelAiStylePreset) -> Unit,
    reload: () -> Unit, close: () -> Unit) {
    var filter by remember { mutableStateOf(NovelAiStyleModelFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val shown = catalog.styles.filter { it.modelSupport.supports(filter) &&
        (query.isBlank() || listOf(it.displayName, it.description).any { text -> text.contains(query, true) }) }
    val candidate = shown.firstOrNull { it.styleKey == selected }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ManageHeading("内置画风图集")
        StatusText("选择后确认填入当前角色卡的画风和配套负面词；角色卡仍需正常保存。")
        StudioField("搜索画风", query) { query = it }
        StudioChips("模型", NovelAiStyleModelFilter.entries, filter, {
            when (it) { NovelAiStyleModelFilter.ALL -> "全部"; NovelAiStyleModelFilter.V4_5 -> "V4.5"; NovelAiStyleModelFilter.V5 -> "V5" }
        }) { filter = it }
        if (catalog.isLoading) StatusText("正在加载内置画风…")
        catalog.fatalError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (catalog.errors.isNotEmpty()) Column(Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState())) {
            catalog.errors.forEach { StatusText(it, DesktopBootstrapColors.destructive) }
        }
        StatusText("显示 ${shown.size} / ${catalog.styles.size} 项")
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.styleKey }) { style ->
                Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card)
                    .border(1.dp, if (style == candidate) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                    .semantics { contentDescription = "选择画风 ${style.styleKey}" }.clickable { selected = style.styleKey }.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (style.previewAvailable) DesktopOwnedImage("asset:${style.previewAssetPath}", read,
                        Modifier.fillMaxWidth().aspectRatio(1f), crop = true)
                    else Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) { StatusText("例图缺失，画风仍可选用") }
                    StatusText(style.displayName, DesktopBootstrapColors.primary)
                    StatusText(style.modelSupport.badgeLabel)
                    if (style.prompt == currentPrompt) StatusText("当前画风")
                }
            }
        }
        candidate?.let { style ->
            Column(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusText(style.displayName)
                if (style.description.isNotBlank()) StatusText(style.description)
                StatusText(style.prompt)
                StatusText(if (style.hasCustomNegativePrompt) "同时填入画风专用负面词" else "同时填入原版默认负面词")
            }
        }
        StudioActions {
            StudioAction("确认填入角色卡", enabled = enabled && candidate != null) { candidate?.let(apply) }
            StudioAction("重新加载画风", enabled = !catalog.isLoading, onClick = reload)
            StudioAction("关闭图集", onClick = close)
        }
    }
}
