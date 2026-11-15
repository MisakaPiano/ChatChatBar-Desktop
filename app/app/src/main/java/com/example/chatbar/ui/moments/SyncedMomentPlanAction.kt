package com.example.chatbar.ui.moments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.domain.moment.MomentScheduler
import com.example.chatbar.ui.kit.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct scheduler entry: do not move this into the ViewModel's normal storage admission. */
@Composable
internal fun SyncedMomentPlanAction(scheduler: MomentScheduler = ChatBarApp.instance.momentScheduler) {
    val scope = rememberCoroutineScope()
    var reviewed by remember { mutableStateOf<List<MomentTask>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cardNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun inspect() {
        busy = true; error = null; message = null
        scope.launch {
            try {
                val plans = scheduler.syncedPendingPlans()
                cardNames = ChatBarApp.instance.characterRepository.getAll().associate { it.id to it.name }
                reviewed = plans
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "同步计划未完整读取，请稍后重新核对"; reviewed = emptyList() }
            finally { busy = false }
        }
    }
    CbButton("同步计划", ::inspect, enabled = !busy, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
    if (reviewed != null) {
        val plans = requireNotNull(reviewed)
        val dates = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        CbDialog(title = "恢复同步计划", onDismissRequest = { if (!busy) reviewed = null },
            dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
            dismiss = { CbButton("关闭", { reviewed = null }, enabled = !busy, variant = ButtonVariant.Ghost) },
            confirm = if (plans.isEmpty() || error != null) null else ({
                CbButton("仅本机恢复 ${plans.size} 项", {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val count = scheduler.approveSyncedPlans(plans)
                            reviewed = emptyList(); message = "已在本机恢复 $count 项计划"
                            scheduler.kick("explicit-synced-plan-approval")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "计划已变化或保存未完成，请关闭后重新核对本机计划" }
                        finally { busy = false }
                    }
                }, enabled = !busy)
            })) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (plans.isEmpty()) CbText(message ?: "没有等待本机批准的同步计划。")
                else {
                    CbText("共 ${plans.size} 项同步计划，当前保持暂停。恢复后，到期计划可能立即使用本机配置生成文字或图片并产生费用。")
                    CbText("仅恢复下列待执行计划；中断、已完成和失败记录不会重新执行。")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(plans, key = { it.id }) { task ->
                            CbText("${dates.format(Date(task.scheduledAt))} · ${cardNames[task.characterCardId] ?: "原角色已删除"} · 计划 ${task.id.take(12)}")
                        }
                    }
                }
                error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
            }
        }
    }
}
