package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.budget.EditorPromptFieldEstimate
import com.example.chatbar.ui.kit.*

@Composable
internal fun PromptFieldEstimatePanel(worldBook: Boolean = false, snapshot: () -> List<EditorPromptFieldEstimate>) {
    var fields by remember { mutableStateOf<List<EditorPromptFieldEstimate>?>(null) }
    CbButton("字段 Token 估算", { fields = snapshot() }, variant = ButtonVariant.Outline)
    fields?.let { report ->
        CbDialog(title = "字段 Token 估算", onDismissRequest = { fields = null },
            confirm = { CbButton("完成", { fields = null }) }) {
            CbText("按字符粗估，汉字约 0.8、其他约 0.4；不是模型专用分词结果或实际账单。", style = ChatBarTheme.typography.caption)
            CbText(if (worldBook) "启用正文候选合计 ≈ ${report.sumOf { it.tokens }} Token"
                else "对话候选字段合计 ≈ ${report.sumOf { it.tokens }} Token")
            CbText(if (worldBook) "没有本轮上下文，触发、概率、预算及变量展开均未计算，实际注入量不确定。条目名称、触发键和停用正文不计入。"
                else "不含作者备注、开场白、卡名等资料、生图词或停用人物；会话选择、变量展开、模板标题、历史与记忆另计，不是本轮请求总量。",
                style = ChatBarTheme.typography.caption)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report, key = { it.id }) { field ->
                    Column {
                        CbText("${field.label} ≈ ${field.tokens} Token")
                        if (field.note.isNotBlank()) CbText(field.note, style = ChatBarTheme.typography.caption,
                            color = ChatBarTheme.colors.mutedForeground)
                    }
                }
                if (report.isEmpty()) item { CbText("暂无可估算的候选正文") }
            }
        }
    }
}
