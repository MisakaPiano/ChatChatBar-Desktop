package com.example.chatbar.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.domain.chat.*
import com.example.chatbar.ui.kit.*
import java.io.File

@Composable
internal fun ChatImageGalleryDialog(snapshot: ChatImageGallerySnapshot?, loading: Boolean, error: String?,
    onRefresh: () -> Unit, onView: (ChatGalleryImage) -> Unit, onLocate: (String) -> Unit, onDismiss: () -> Unit) {
    CbDialog(title="会话图片",onDismissRequest=onDismiss,confirm={ CbButton("完成",onDismiss) }) {
        CbText("当前会话全部已保存消息的图片；存档、背景库和朋友圈在各自入口查看。",style=ChatBarTheme.typography.caption)
        CbButton(if(loading) "读取中…" else "刷新",onRefresh,enabled=!loading,variant=ButtonVariant.Ghost)
        error?.let { CbText(it,color=ChatBarTheme.colors.destructive) }
        snapshot?.let { current ->
            CbText("${current.images.size} 张 · 已省略 ${current.omittedImages} 张 · 不可读取 ${current.images.count { !it.readable }} 张",style=ChatBarTheme.typography.caption)
            if(current.images.isEmpty()) CbText("当前会话暂无图片")
            LazyVerticalGrid(columns=GridCells.Adaptive(128.dp),modifier=Modifier.fillMaxWidth().weight(1f,fill=false),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(current.images,key={it.key}) { item ->
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(item.readable) AsyncImage(File(item.path),"会话图片",Modifier.fillMaxWidth().height(120.dp),contentScale=ContentScale.Crop)
                        else CbText("图片文件不可读取",color=ChatBarTheme.colors.warning)
                        CbButton("查看图片",{onView(item)},enabled=item.readable,variant=ButtonVariant.Outline)
                        CbButton("定位消息",{onLocate(item.messageId)},variant=ButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}
