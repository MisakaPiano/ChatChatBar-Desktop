@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal data class DesktopGuideCopy(val zhCn: String, val en: String)
internal data class DesktopGuideSection(val title: DesktopGuideCopy, val body: DesktopGuideCopy)
internal data class DesktopGuideTopic(val id: String, val advanced: Boolean,
    val title: DesktopGuideCopy, val sections: List<DesktopGuideSection>)

/** Static reader inside the existing help modal. No controller, preferences or navigation writes. */
@Composable
internal fun DesktopTutorialGuide(onBack: () -> Unit, onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = LocalDesktopPalette.current
    var selected by remember { mutableStateOf(0) }
    var showTopics by remember { mutableStateOf(false) }
    val topic = desktopTutorialTopics[selected]
    Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().heightIn(max = 680.dp).fillMaxHeight()
        .background(colors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DesktopDataSectionHeading(t(DesktopUiText.GUIDE)) }
            DesktopIconAction(t(DesktopUiText.GUIDE_CLOSE), DesktopAppIcons.Close, onClick = onClose)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            @Composable fun Topics(modifier: Modifier) {
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                Column(modifier.onSizeChanged { viewport = it }.semantics { contentDescription = t(DesktopUiText.GUIDE_TOPIC_LIST) }
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    desktopTutorialTopics.forEachIndexed { index, item ->
                        if (index == 0 || item.advanced != desktopTutorialTopics[index - 1].advanced)
                            DesktopDataSectionHeading(t(if (item.advanced) DesktopUiText.GUIDE_ADVANCED else DesktopUiText.GUIDE_BASIC))
                        val bring = remember(item.id) { BringIntoViewRequester() }
                        LaunchedEffect(selected, viewport) {
                            if (selected == index && viewport.height > 0 && viewport.width > 0) bring.bringIntoView()
                        }
                        DesktopWorkspaceNavigationItem(DesktopWorkspaceDestination(item.id, t(item.title)), selected == index,
                            true, Modifier.fillMaxWidth().bringIntoViewRequester(bring)) { selected = index; showTopics = false }
                    }
                }
            }
            @Composable fun Reader(modifier: Modifier) {
                key(topic.id) {
                    SelectionContainer(modifier.semantics { contentDescription = t(DesktopUiText.GUIDE_BODY) }) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DesktopDataSectionHeading(t(topic.title))
                            topic.sections.forEach { section ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DesktopDataSectionHeading(t(section.title))
                                    StatusText(t(section.body))
                                }
                            }
                        }
                    }
                }
            }
            if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Topics(Modifier.width(210.dp).fillMaxHeight())
                Reader(Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(t(DesktopUiText.GUIDE_TOPICS), secondary = true) { showTopics = !showTopics }
                if (showTopics) Topics(Modifier.fillMaxWidth().heightIn(max = (maxHeight - 128.dp).coerceIn(40.dp, 200.dp)))
                Reader(Modifier.weight(1f).fillMaxWidth())
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BootstrapButton(t(DesktopUiText.GUIDE_BACK), secondary = true, onClick = onBack)
            BootstrapButton(t(DesktopUiText.GUIDE_PREVIOUS), secondary = true, enabled = selected > 0) { if (selected > 0) selected-- }
            BootstrapButton(t(DesktopUiText.GUIDE_NEXT), secondary = true, enabled = selected + 1 < desktopTutorialTopics.size) {
                if (selected + 1 < desktopTutorialTopics.size) selected++
            }
            StatusText("${selected + 1} / ${desktopTutorialTopics.size}")
        }
    }
}
