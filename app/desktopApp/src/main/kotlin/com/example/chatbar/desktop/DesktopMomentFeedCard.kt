@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.chatbar.data.local.entity.MomentPost
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation only: image choices, author routes and mutations stay with their existing owners. */
@Composable
internal fun DesktopMomentFeedCard(post: MomentPost, resources: DesktopCharacterResourceStore?, busy: Boolean,
    onOpenSession: () -> Unit, onOpenCard: (Boolean) -> Unit, onPreviewAvatar: () -> Unit,
    onPreviewImage: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onLike: () -> Unit,
    onRetry: () -> Unit, onGenerateImage: () -> Unit, onRegenerateImage: () -> Unit,
    onVersions: () -> Unit, onSelectVersion: (String) -> Unit, onDelete: () -> Unit) {
    val colors = DesktopBootstrapColors
    var more by remember(post.id) { mutableStateOf(false) }
    val avatar = post.senderAvatar
    @Composable fun LinkedAuthor(content: @Composable () -> Unit) {
        ContextMenuArea(items = { buildList {
            add(ContextMenuItem("打开所属角色卡设置") { onOpenCard(false) })
            add(ContextMenuItem("查看该角色卡朋友圈开关") { onOpenCard(true) })
            if (!avatar.isNullOrBlank()) add(ContextMenuItem("查看头像", onPreviewAvatar))
        } }) { content() }
    }
    Column(Modifier.fillMaxWidth().testTag("moment-post-${post.id}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkedAuthor {
                val avatarModifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                    .testTag("moment-avatar-${post.id}").clickable(onClick = onOpenSession)
                if (!avatar.isNullOrBlank() && resources != null) DesktopOwnedImage(avatar, resources::readBytes, avatarModifier, crop = true)
                else Box(avatarModifier.background(colors.secondary), contentAlignment = Alignment.Center) {
                    StatusText(post.senderName.take(1))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LinkedAuthor {
                            BasicText(post.senderName, Modifier.testTag("moment-author-${post.id}").clickable(onClick = onOpenSession),
                                style = TextStyle(color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                        }
                    }
                    if (!post.isPlaceholder) DesktopChatIconAction("编辑动态文字", DesktopAppIcons.Edit, onClick = onEdit)
                }
                if (post.isPlaceholder) {
                    StatusText(post.failureReason ?: "生成尚未完成", colors.destructive)
                    BootstrapButton("从已完成阶段重试", enabled = !busy, secondary = true, onClick = onRetry)
                } else {
                    SelectionContainer {
                        BasicText(post.text, Modifier.testTag("moment-text-${post.id}"),
                            style = TextStyle(color = colors.foreground, fontSize = 16.sp, lineHeight = 24.sp))
                    }
                    val image = post.imagePath
                    if (!image.isNullOrBlank()) {
                        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (resources != null) DesktopOwnedImage(image, resources::readBytes,
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                    .testTag("moment-image-${post.id}").clickable(onClick = onPreviewImage), crop = true)
                            val versions = DesktopMomentImageVersions.retained(post)
                            val current = versions.indexOfFirst { it.imagePath == image }
                            DesktopVersionControls(current + 1, versions.size, "上一张动态图片", "下一张动态图片",
                                modifier = Modifier.testTag("moment-versions-${post.id}")) { delta ->
                                versions.getOrNull(current + delta)?.let { onSelectVersion(it.id) }
                            }
                        }
                    } else BootstrapButton("未生成图片 · 点击生成私密配图", enabled = !busy,
                        variant = DesktopActionVariant.SECONDARY, onClick = onGenerateImage)
                }
                FlowRow(Modifier.fillMaxWidth().testTag("moment-footer-${post.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusText(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(post.generatedAt)))
                        if (post.isPrivate) StatusText("仅你可见")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!post.isPlaceholder && !post.imagePath.isNullOrBlank())
                            DesktopChatIconAction("编辑Prompt重新生成", DesktopAppIcons.Refresh, enabled = !busy, onClick = onRegenerateImage)
                        DesktopChatIconAction("删除动态", DesktopAppIcons.Delete, destructive = true, onClick = onDelete)
                        if (!post.isPlaceholder) {
                            DesktopChatIconAction(if (post.userLiked) "取消点赞" else "点赞", DesktopAppIcons.Like,
                                selected = post.userLiked, onClick = onLike)
                            StatusText(post.displayLikeCount.toString())
                            Box {
                                DesktopChatIconAction("动态更多操作", DesktopAppIcons.More) { more = !more }
                                if (more) Popup(popupPositionProvider = StudioPopupPosition, onDismissRequest = { more = false },
                                    properties = PopupProperties(focusable = true)) {
                                    Column(Modifier.width(180.dp).background(colors.card, RoundedCornerShape(8.dp))
                                        .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(6.dp)) {
                                        StudioAction("复制动态文字", icon = DesktopAppIcons.Copy) { more = false; onCopy() }
                                        if (!post.imagePath.isNullOrBlank()) StudioAction("管理图片版本", icon = DesktopAppIcons.History) {
                                            more = false; onVersions()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.65f)))
    }
}
