package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun StorageReadFailureScreen(
    failures: List<JsonFileStorage.SingletonReadException>,
    retrying: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CbText("本地数据读取失败", style = ChatBarTheme.typography.heading)
        CbText("原文件已保留，应用已暂停使用这些数据，不会自动重置为默认设置。")
        failures.forEach { failure ->
            CbText(failure.message.orEmpty(), color = ChatBarTheme.colors.destructive)
        }
        CbText(
            "存储恢复可用后可重试。若文件已损坏，请保留应用数据，使用已有备份恢复或联系开发者协助处理；不要卸载或清除应用数据。",
            color = ChatBarTheme.colors.mutedForeground
        )
        CbButton(if (retrying) "正在重试…" else "重新读取", onRetry, enabled = !retrying)
    }
}
