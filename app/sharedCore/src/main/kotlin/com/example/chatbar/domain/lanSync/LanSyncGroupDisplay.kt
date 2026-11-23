package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncVersionRef
import kotlinx.serialization.json.JsonPrimitive

data class LanSyncGroupDisplayLabel(val title: String, val kind: String, val identifier: String)

/** Presentation from frozen records only. Names never replace protocol identities or review bindings. */
object LanSyncGroupDisplay {
    fun localPresetReviewNotice(code: String?): String? =
        if (code == LanSyncGroupNativePlanner.LOCAL_PRESET_REVIEW) "本机预设以独立副本保留。只有核对并确认发布后，其他设备才会收到这个副本。" else null
    fun missingLocalImageNotice(code: String?): String? =
        if (code == LanSyncMissingImage.CODE) "本机会话的图片文件缺失。会话记录和附件引用已保留，其他会话可继续同步。" else null

    const val blockedResolutionImageNotice = "这组内容有本机图片缺失或无法处理。可以先暂缓。请补回可读的原图。如果需要替换，请先另存仍有的原件，再从原会话的编辑消息入口移除旧图片并添加兼容图片。完成后在设备组点「立即检查」并重新打开详情。"

    fun nativeStageLabel(row: LanSyncGroupNativeUnit): String = when (row.stage) {
        LanSyncGroupNativeStage.UNCHANGED -> "无变化"
        LanSyncGroupNativeStage.READY_CONTENT -> "传入内容已就绪"
        LanSyncGroupNativeStage.READY_DELETION -> "传入删除"
        LanSyncGroupNativeStage.READY_OBSERVATION -> "内容一致"
        LanSyncGroupNativeStage.LOCAL_PROPOSAL -> "本机修改"
        LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW -> "本机删除或覆盖待核对"
        LanSyncGroupNativeStage.CONFLICT -> "多版本待选择"
        LanSyncGroupNativeStage.AUTHOR_FORK -> "同一设备版本分叉"
        LanSyncGroupNativeStage.WAITING_DEPENDENCY -> dependencyLabel(row.dependencyStatuses.values.singleOrNull())
        LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED ->
            if (missingLocalImageNotice(row.code) != null) "本机图片文件缺失" else "本会话图片暂缓处理"
        LanSyncGroupNativeStage.RECORD_REVIEW -> "记录归属待核对"
        LanSyncGroupNativeStage.DEPENDENCY_HELD -> "关联内容暂缓"
        LanSyncGroupNativeStage.INVALID_MIXED_GRAPH -> "关联资料尚未通过检查"
    }

    fun componentHasRejectedVersion(plan: LanSyncGroupNativePlan, unit: String): Boolean =
        plan.components[unit].orEmpty().any { related ->
            val row = plan.units.getValue(related)
            row.stage != LanSyncGroupNativeStage.UNCHANGED &&
                row.dependencyStatuses.values.any { it.stage == LanSyncGroupDependencyStage.INVALID_CONTENT }
        }

    /** The details follow the exact signed frontier selected in this inspection. */
    fun versionDependencyNotice(row: LanSyncGroupNativeUnit, version: SyncVersionRef): String? {
        if (version in row.incoming || row.frontiers.none { it.version == version && it.change.content != null }) return null
        val status = row.dependencyStatuses[version]
        val label = dependencyLabel(status)
        val next = when (status?.stage) {
            LanSyncGroupDependencyStage.INVALID_CONTENT -> if (status.rejectedPart != null) {
                "已取得的资料无效、超出处理上限或暂不支持。请在来源设备核对并保留原件，修正后同步本机可读的新版本。"
            } else {
                "请核对同步来源和本机存储，保留原件后再点「立即检查」。"
            }
            LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE -> "请连接持有该版本的设备，或检查已配置的网盘，然后点「立即检查」。"
            LanSyncGroupDependencyStage.UNSUPPORTED_SCOPE -> "请核对两台设备的应用版本，原版本仍可留待处理。"
            LanSyncGroupDependencyStage.WAITING_FOR_KEY -> "请连接能提供该版本的组内设备，然后点「立即检查」。"
            LanSyncGroupDependencyStage.AUTHOR_FORK -> "请在来源设备核对版本分叉。"
            else -> "请查看设备组状态，再重新打开详情。"
        }
        return "$label。$next" + status?.code?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) }?.let { "（$it）" }.orEmpty()
    }

    private fun dependencyLabel(status: LanSyncGroupDependencyStatus?): String = when (status?.stage) {
        LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE -> "等待正文或附件"
        LanSyncGroupDependencyStage.WAITING_FOR_KEY -> "等待此版本的密钥"
        LanSyncGroupDependencyStage.UNSUPPORTED_SCOPE -> "暂不支持此版本的资料范围"
        LanSyncGroupDependencyStage.INVALID_CONTENT -> when (status.rejectedPart) {
            LanSyncGroupDependencyPart.BODY -> "已取得的正文未通过验证"
            LanSyncGroupDependencyPart.MEMBERSHIP -> "已取得的记录清单未通过验证"
            LanSyncGroupDependencyPart.ATTACHMENT -> "已取得的附件未通过验证"
            null -> "此版本未能通过资料检查"
        }
        LanSyncGroupDependencyStage.LOCAL_CUSTODY_BLOCKED -> "本机同步凭据无法读取"
        LanSyncGroupDependencyStage.AUTHOR_FORK -> "此设备的版本已分叉"
        else -> "正文或附件尚未核实"
    }

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
