package com.example.chatbar.lanSync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*

/** User selection only. The existing resolution owner prepares and validates the entire graph. */
@Composable
internal fun LanSyncGroupResolutionChoices(view: LanSyncGroupNodeView, selectedUnit: String,
    busy: Boolean, actions: LanSyncGroupPanelActions) {
    val plan = view.plan ?: return
    var expanded by remember(view, selectedUnit) { mutableStateOf(false) }
    CbButton(if (expanded) "收起版本处理选择" else "选择采用、保留两份或暂缓", { expanded = !expanded }, variant = ButtonVariant.Secondary)
    if (!expanded) return
    val units = remember(view, selectedUnit) { plan.components.getValue(selectedUnit).filter {
        plan.units.getValue(it).stage != LanSyncGroupNativeStage.UNCHANGED
    }.sorted() }
    val primary = remember(view, selectedUnit) { mutableStateMapOf<String, LanSyncGroupResolutionSource>() }
    val copies = remember(view, selectedUnit) { mutableStateMapOf<String, LanSyncGroupResolutionSource>() }
    var page by remember(view, selectedUnit) { mutableStateOf(0) }
    val blocked = units.any { plan.units.getValue(it).stage == LanSyncGroupNativeStage.AUTHOR_FORK }
    CbText("关联内容需要一起处理，请分别为每组选定来源。准备目标后仍会显示完整正文和影响，最后由你确认。")
    if (blocked) CbText("存在同一设备的版本分叉，当前不能采用或复制；可以先暂缓处理。", color = ChatBarTheme.colors.warning)
    CbText("保留两份时，每组需另选一份非空正文；全局设定与删除标记不能复制。")
    units.drop(page * 10).take(10).forEach { unit ->
        key(view, unit) {
            val display = LanSyncGroupDisplay.unit(view, unit)
            CbText("${display.kind} · ${display.title}")
            CbText(display.identifier)
            val row = plan.units.getValue(unit)
            val options = listOf<LanSyncGroupResolutionSource>(LanSyncGroupResolutionSource.Local) +
                row.frontiers.sortedWith(compareBy({ it.version.id.value }, { it.version.bodyHash })).map { LanSyncGroupResolutionSource.Version(it.version) }
            options.forEachIndexed { index, source ->
                key(source) {
                    val reference = (source as? LanSyncGroupResolutionSource.Version)?.reference
                    val fact = reference?.let { ref -> row.frontiers.single { it.version == ref } }
                    val records = if (reference == null) plan.before.units()[unit].orEmpty() else row.incoming[reference]?.decoded?.payload?.records.orEmpty()
                    val available = reference == null || fact?.change?.content == null || reference in row.incoming
                    val label = if (reference == null) "本机内容" else if (fact?.change?.content == null) "已签名版本 $index · 删除标记"
                        else "已签名版本 $index · ${LanSyncGroupDisplay.unit(unit, records).title}"
                    reference?.let { CbText("版本身份：${it.id.value} · ${it.bodyHash}") }
                    if (!available) CbText("此版本正文或附件尚未补齐。")
                    CbButton((if (primary[unit] == source) "✓ " else "") + "作为采用目标 · $label",
                        { primary[unit] = source }, enabled = !busy && !blocked && available, variant = ButtonVariant.Secondary)
                    CbButton((if (copies[unit] == source) "✓ " else "") + "作为第二份 · $label",
                        { copies[unit] = source }, enabled = !busy && !blocked && available && LanSyncGroupDisplay.canOfferCopy(records), variant = ButtonVariant.Secondary)
                }
            }
        }
    }
    if (units.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页关联组", { page-- }, enabled = page > 0, variant = ButtonVariant.Secondary)
        CbText("${page + 1}/${(units.size + 9) / 10}")
        CbButton("下一页关联组", { page++ }, enabled = (page + 1) * 10 < units.size, variant = ButtonVariant.Secondary)
    }
    val selected = units.isNotEmpty() && units.all { it in primary }
    CbButton("采用选定版本", { actions.prepareResolution(view, units.associateWith {
        LanSyncGroupResolutionChoice.Keep(primary.getValue(it))
    }) }, enabled = !busy && !blocked && selected, variant = ButtonVariant.Secondary)
    CbButton("两份都保留", { actions.prepareResolution(view, units.associateWith {
        LanSyncGroupResolutionChoice.Keep(primary.getValue(it), mapOf("copy-1" to copies.getValue(it)))
    }) }, enabled = !busy && !blocked && selected && units.all { it in copies && copies[it] != primary[it] }, variant = ButtonVariant.Secondary)
    CbButton("暂缓处理", { actions.prepareResolution(view, mapOf(selectedUnit to LanSyncGroupResolutionChoice.Later)) },
        enabled = !busy, variant = ButtonVariant.Secondary)
}

/** Frozen actual candidate bodies and effects; final acknowledgment remains in the parent panel. */
@Composable
internal fun LanSyncGroupResolutionDetails(review: LanSyncGroupActionReview, actions: LanSyncGroupPanelActions) {
    val preview = review.resolution ?: return
    val context = remember(review) { LanSyncGroupFrozenImageContext.Action(review) }
    val units = remember(preview) { preview.units.keys.sorted() }
    var page by remember(preview.binding) { mutableStateOf(0) }
    CbText("以下是准备后的实际目标，包含采用内容与新增副本。")
    if (preview.resolvedUnits.isEmpty()) CbText("此次仅保存暂缓选择，不发布新的内容版本。")
    units.drop(page * 10).take(10).forEach { unit ->
        key(preview.binding, unit) {
            val row = preview.units.getValue(unit)
            val display = LanSyncGroupDisplay.unit(unit, row.current.ifEmpty { row.proposed })
            CbText("${display.kind} · ${display.title}")
            CbText(display.identifier)
            GroupFrozenRecords("处理前的本机内容", unit, row.current, context, actions)
            GroupFrozenRecords("采用及副本的完整目标", unit, row.proposed, context, actions)
            CbText(groupActionImpactSummary(row.localImpact))
            row.versions.entries.sortedWith(compareBy({ it.key.id.value }, { it.key.bodyHash })).forEach { (version, records) ->
                key(version) {
                    CbText("已签名核对版本：${version.id.value} · ${version.bodyHash}")
                    GroupFrozenRecords("此版本的完整正文", unit, records, context, actions)
                    row.versionImpact[version]?.let { CbText(groupActionImpactSummary(it)) }
                }
            }
        }
    }
    if (units.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页处理目标", { page-- }, enabled = page > 0, variant = ButtonVariant.Secondary)
        CbText("${page + 1}/${(units.size + 9) / 10}")
        CbButton("下一页处理目标", { page++ }, enabled = (page + 1) * 10 < units.size, variant = ButtonVariant.Secondary)
    }
    val mappings = remember(preview) { buildList {
        preview.identities.forEach { add("新增副本 · ${it.branch} · ${it.type}：${it.source} → ${it.target}") }
        preview.media.forEach { add("附件副本 · ${it.branch} · ${it.kind}：${it.source} → ${it.target} · ${it.hash}") }
        preview.requiredRestorations.sortedWith(compareBy({ it.formerUnit }, { it.record })).forEach {
            add("重新引入已移走或删除的记录：${it.record} · 原内容组 ${it.formerUnit} · 删除版本 ${it.removal.id.value} · ${it.removal.bodyHash}")
        }
    } }
    var mappingPage by remember(preview.binding) { mutableStateOf(0) }
    if (mappings.isNotEmpty()) {
        CbText("副本身份、附件与重新引入记录：共 ${mappings.size} 项")
        mappings.drop(mappingPage * 10).take(10).forEach { CbText(it) }
        if (mappings.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("上一页影响明细", { mappingPage-- }, enabled = mappingPage > 0, variant = ButtonVariant.Secondary)
            CbText("${mappingPage + 1}/${(mappings.size + 9) / 10}")
            CbButton("下一页影响明细", { mappingPage++ }, enabled = (mappingPage + 1) * 10 < mappings.size, variant = ButtonVariant.Secondary)
        }
    }
    if (preview.heldUnits.isNotEmpty()) {
        val held = remember(preview) { preview.heldUnits.sorted() }
        var heldPage by remember(preview.binding) { mutableStateOf(0) }
        CbText("以下内容仍将保留待处理：")
        held.drop(heldPage * 10).take(10).forEach { unit ->
            val display = review.resolutionBasis?.let { LanSyncGroupDisplay.unit(it, unit) } ?: LanSyncGroupDisplay.unit(unit, emptyList())
            CbText("${display.kind} · ${display.title} · ${display.identifier}")
        }
        if (held.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("上一页待处理组", { heldPage-- }, enabled = heldPage > 0, variant = ButtonVariant.Secondary)
            CbText("${heldPage + 1}/${(held.size + 9) / 10}")
            CbButton("下一页待处理组", { heldPage++ }, enabled = (heldPage + 1) * 10 < held.size, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
internal fun LanSyncGroupResolutionHistory(view: LanSyncGroupNodeView?, busy: Boolean, actions: LanSyncGroupPanelActions) {
    if (view == null || view.resolutions.isEmpty()) return
    CbText("本机已保存的处理与暂缓", style = ChatBarTheme.typography.title)
    var page by remember(view.resolutions) { mutableStateOf(0) }
    view.resolutions.drop(page * 10).take(10).forEach { row ->
        key(row.operation) {
            CbText(when (row.stage) {
                LanSyncGroupResolutionLocalStage.PREPARED -> "已确认，等待本机处理"
                LanSyncGroupResolutionLocalStage.LOCAL_READY -> if (row.resolvedUnits.isEmpty()) "已保存暂缓选择" else "本机目标已准备"
                LanSyncGroupResolutionLocalStage.NEEDS_REVIEW -> "需要重新核对"
                LanSyncGroupResolutionLocalStage.SUPERSEDED -> "已由后续选择替代"
            })
            if (row.heldUnits.isNotEmpty()) CbText("暂缓内容：" + row.heldUnits.sorted().joinToString("、") { LanSyncGroupDisplay.unit(view, it).title })
            CbText("处理身份：${row.operation}")
            CbButton("接续已确认的处理", { actions.resumeResolution(row.operation) },
                enabled = !busy && row.stage != LanSyncGroupResolutionLocalStage.SUPERSEDED, variant = ButtonVariant.Secondary)
        }
    }
    if (view.resolutions.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页已保存处理", { page-- }, enabled = page > 0, variant = ButtonVariant.Secondary)
        CbText("${page + 1}/${(view.resolutions.size + 9) / 10}")
        CbButton("下一页已保存处理", { page++ }, enabled = (page + 1) * 10 < view.resolutions.size, variant = ButtonVariant.Secondary)
    }
}
