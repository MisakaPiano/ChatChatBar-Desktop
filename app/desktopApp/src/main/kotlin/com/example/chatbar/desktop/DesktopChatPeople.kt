@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DesktopChatPeopleActions(
    val people: List<DesktopChatPerson>,
    val inspect: (DesktopChatPerson) -> Unit,
    val edit: (String, String) -> Unit,
    val previewEnabled: Boolean = true,
    val toggleEnabled: Boolean = true,
    val setEnabled: ((DesktopChatPerson, Boolean) -> Unit)? = null,
)
internal val LocalDesktopChatPeople = staticCompositionLocalOf<DesktopChatPeopleActions?> { null }

@Composable
internal fun DesktopChatPersonTarget(person: DesktopChatPerson?, onOwnContext: () -> Unit = {},
    content: @Composable () -> Unit) {
    val actions = LocalDesktopChatPeople.current
    if (person == null || actions == null) { content(); return }
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var menu by remember(person.cardId, person.character.id) { mutableStateOf(false) }
    if (menu) DesktopChatContextPopup(bounds, listOf(t(DesktopUiText.PERSON) to listOf(
        DesktopChatMenuEntry(t(DesktopUiText.PERSON_STATUS), DesktopAppIcons.DetailsOpen, true) { actions.inspect(person) },
        DesktopChatMenuEntry(t(DesktopUiText.EDIT_PERSON), DesktopAppIcons.Edit, person.identityUnique) {
            actions.edit(person.cardId, person.character.id)
        },
        DesktopChatMenuEntry(t(if (person.enabled) DesktopUiText.DISABLE_PERSON else DesktopUiText.ENABLE_PERSON),
            if (person.enabled) DesktopAppIcons.Stop else DesktopAppIcons.Add,
            person.identityUnique && actions.toggleEnabled && actions.setEnabled != null) { actions.setEnabled?.invoke(person, !person.enabled) },
    ))) { menu = false }
    TooltipArea(tooltip = {
        if (actions.previewEnabled && !menu) {
        Column(Modifier.widthIn(max = 360.dp).heightIn(max = 300.dp).background(colors.card, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusText(person.character.name)
            if (!person.enabled) StatusText(t(DesktopUiText.PERSON_DISABLED_HISTORY))
            StatusText(person.status?.text ?: t(DesktopUiText.PERSON_STATUS_UNKNOWN))
            StatusText(t(DesktopUiText.PERSON_STATUS_OPEN))
        }
        }
    }) {
        Box(Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
            .semantics { contentDescription = "${t(DesktopUiText.PERSON_STATUS)} · ${person.character.name}" }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (event.buttons.isSecondaryPressed && person.identityUnique) {
                    onOwnContext(); menu = true; event.changes.forEach { it.consume() }
                }
            }.clickable(enabled = person.identityUnique, role = Role.Button) { actions.inspect(person) }) { content() }
    }
}

@Composable
internal fun DesktopChatPeopleRail(people: List<DesktopChatPerson>, controller: DesktopPrimaryChatController) {
    val t = LocalDesktopUiStrings.current
    Column(Modifier.width(96.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusText(t(DesktopUiText.CARD_PEOPLE))
        people.forEachIndexed { index, person -> key(index, person.cardId, person.character.id) {
            DesktopChatPersonTarget(person) {
                Column(Modifier.width(80.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryAvatar(person.character.appearanceImage, person.character.name, controller, 40.dp)
                    BasicText(person.character.name, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
                    if (!person.enabled) StatusText(t(DesktopUiText.PERSON_DISABLED))
                }
            }
        } }
    }
}

@Composable
internal fun DesktopChatPeopleDialog(people: List<DesktopChatPerson>, selectedId: String?,
    onSelect: (String) -> Unit, onEdit: (String, String) -> Unit,
    onSetEnabled: ((DesktopChatPerson, Boolean) -> Unit)? = null, toggleEnabled: Boolean = true,
    participationAvailable: Boolean = true, onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    val selected = people.firstOrNull { it.character.id == selectedId }
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 700.dp)
            .background(colors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText(selected?.character?.name ?: t(DesktopUiText.CARD_PEOPLE)) }
                BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = onClose)
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusText(t(if (participationAvailable) DesktopUiText.PERSON_PARTICIPATION_SCOPE else DesktopUiText.PERSON_TOGGLE_FREEFORM))
                if (selected == null) {
                    people.forEach { person -> BootstrapButton(person.character.name + if (!person.enabled) " · ${t(DesktopUiText.PERSON_DISABLED)}" else "", secondary = true,
                        enabled = person.identityUnique) { onSelect(person.character.id) } }
                } else {
                    if (!selected.enabled) StatusText(t(DesktopUiText.PERSON_DISABLED_HISTORY))
                    selected.status?.let { status ->
                        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(status.source.createdAt))
                        StatusText("${t(DesktopUiText.PERSON_STATUS_SOURCE)} $time · ${status.source.currentAlternativeIndex + 1}")
                    }
                    SelectionContainer { BasicText(selected.status?.text ?: t(DesktopUiText.PERSON_STATUS_UNKNOWN),
                        style = TextStyle(color = colors.foreground, fontSize = 14.sp, lineHeight = 20.sp)) }
                    BootstrapButton(t(DesktopUiText.EDIT_PERSON), secondary = true, enabled = selected.identityUnique) {
                        onEdit(selected.cardId, selected.character.id)
                    }
                    if (onSetEnabled != null) BootstrapButton(t(if (selected.enabled) DesktopUiText.DISABLE_PERSON else DesktopUiText.ENABLE_PERSON),
                        secondary = true, enabled = toggleEnabled && selected.identityUnique) { onSetEnabled(selected, !selected.enabled) }
                }
            }
        }
    } }
}
