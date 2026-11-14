package com.example.chatbar.domain.lanSync

import kotlinx.serialization.json.JsonPrimitive

data class LanSyncGroupDisplayLabel(val title: String, val kind: String, val identifier: String)

/** Presentation from frozen records only. Names never replace protocol identities or review bindings. */
object LanSyncGroupDisplay {
    /** Presentation eligibility only; the resolution owner still validates the complete copied graph. */
    fun canOfferCopy(records: List<LanSyncRecord>): Boolean = records.isNotEmpty() && records.none { it.singleton }
    fun unit(unit: String, records: List<LanSyncRecord>): LanSyncGroupDisplayLabel {
        val kind = when (unit.substringBefore('/')) {
            "conversation" -> "会话"
            "moment-task" -> if (records.any { it.type == "moment_posts" }) "朋友圈计划与内容" else "朋友圈计划"
            "moment-post" -> "朋友圈内容"
            "studio-current" -> "工作室当前资料"
            else -> recordKind(unit.substringBefore('/')).takeUnless { it == "资料记录" } ?: "资料组"
        }
        val owner = when (unit.substringBefore('/')) {
            "conversation" -> records.firstOrNull { it.type == "chat_sessions" && it.id == unit.substringAfter('/') }
            "moment-task", "moment-post" -> records.filter { it.type == "moment_posts" }.minByOrNull { it.key }
                ?: records.filter { it.type == "moment_tasks" }.minByOrNull { it.key }
            "studio-current" -> records.firstOrNull { it.type == LanSyncStudioCurrent.DRAFT }
            else -> records.firstOrNull { it.key == unit } ?: records.minByOrNull { it.key }
        }
        return LanSyncGroupDisplayLabel(owner?.let(::recordTitle) ?: kind, kind, unit)
    }

    fun unit(view: LanSyncGroupNodeView, unit: String): LanSyncGroupDisplayLabel {
        val plan = view.plan ?: return this.unit(unit, emptyList())
        val local = plan.before.units()[unit].orEmpty()
        if (local.isNotEmpty()) return this.unit(unit, local)
        val row = plan.units[unit]
        val received = row?.available?.decoded?.payload?.records
            ?: row?.incoming?.entries?.sortedWith(compareBy({ it.key.id.value }, { it.key.bodyHash }))?.firstOrNull()?.value?.decoded?.payload?.records
        return this.unit(unit, received.orEmpty())
    }

    fun record(record: LanSyncRecord): LanSyncGroupDisplayLabel {
        val kind = recordKind(record.type)
        return LanSyncGroupDisplayLabel(recordTitle(record) ?: kind, kind, record.key)
    }

    private fun recordTitle(record: LanSyncRecord): String? =
        listOf("displayTitleOverride", "title", "name", "playerName", "senderName").firstNotNullOfOrNull { field ->
            (record.body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let(::lanSyncGroupDisplayText)?.takeIf { it.isNotBlank() }
        }

    private fun recordKind(type: String): String = when (type) {
        "character_cards" -> "角色卡"
        "format_cards" -> "格式卡"
        "world_books" -> "世界书"
        "chat_sessions" -> "会话"
        "chat_messages" -> "消息"
        "save_slots" -> "会话存档"
        "generated_voice_messages" -> "已生成语音"
        "voice_anchor_states" -> "语音定位资料"
        "vector_chunks" -> "检索资料"
        "memory_nodes" -> "长期记忆条目"
        "memory_states" -> "长期记忆状态"
        "memory_tier_revisions" -> "长期记忆修订"
        "memory_compression_transactions" -> "记忆压缩记录"
        "memory_commits" -> "记忆提交记录"
        "moment_posts" -> "朋友圈内容"
        "moment_tasks" -> "朋友圈计划"
        "player_setting" -> "玩家设定"
        "novelai_generation_history" -> "生图历史"
        LanSyncPostProcess.TYPE -> "电脑后处理历史"
        LanSyncStudioCurrent.DRAFT -> "工作室当前草稿"
        LanSyncStudioCurrent.UNDO -> "工作室撤销历史"
        LanSyncStudioCurrent.CHECKPOINT -> "工作室参考图检查点"
        LanSyncStudioCurrent.HISTORY -> "工作室编辑历史"
        else -> "资料记录"
    }
}

/** Bounded single-line rendering, preserving whole Unicode characters and the original stored text. */
internal fun lanSyncGroupDisplayText(value: String): String {
    val result = StringBuilder()
    var position = 0
    var characters = 0
    var space = false
    var truncated = false
    while (position < value.length && characters < 160) {
        val point = value.codePointAt(position); position += Character.charCount(point)
        if (Character.isISOControl(point) || Character.isWhitespace(point) || point in 0x202a..0x202e ||
            point in 0x2066..0x2069 || point == 0x200e || point == 0x200f || point == 0x061c) {
            if (result.isNotEmpty()) space = true
            continue
        }
        if (space) {
            result.append(' '); space = false; characters++
            if (characters == 160) { truncated = true; break }
        }
        result.appendCodePoint(point); characters++
    }
    if (position < value.length || truncated) result.append('…')
    return result.toString()
}
