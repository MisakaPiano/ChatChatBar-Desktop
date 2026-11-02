@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val CURRENT_DESKTOP_GUIDE_VERSION = 1
internal enum class DesktopGuideReminderProblem { READ, SAVE }
internal data class DesktopGuideReminderState(
    val ready: Boolean = false, val acknowledgedVersion: Int = 0,
    val deferredThisRun: Boolean = false, val busy: Boolean = false,
    val problem: DesktopGuideReminderProblem? = null,
) {
    val pending get() = ready && !deferredThisRun && acknowledgedVersion < CURRENT_DESKTOP_GUIDE_VERSION
}

/** Desktop preference only. Reading/defer never writes; explicit suppression uses latest-read custody. */
internal class DesktopGuideReminderController(private val store: DesktopSettingsStore) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(DesktopGuideReminderState())
    val state = mutable.asStateFlow()

    suspend fun load() = lock.withLock {
        try {
            val document = when (val result = store.load()) {
                is DesktopSettingsLoadResult.Missing -> result.document
                is DesktopSettingsLoadResult.Loaded -> result.document
                is DesktopSettingsLoadResult.Failure -> {
                    mutable.value = mutable.value.copy(ready = true, problem = DesktopGuideReminderProblem.READ)
                    return@withLock
                }
            }
            mutable.value = mutable.value.copy(ready = true,
                acknowledgedVersion = document.settings.guideAcknowledgedVersion, problem = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(ready = true, problem = DesktopGuideReminderProblem.READ) }
    }

    fun deferForRun() { if (!mutable.value.busy) mutable.value = mutable.value.copy(deferredThisRun = true) }

    suspend fun suppressFutureReminder() = lock.withLock {
        if (mutable.value.acknowledgedVersion >= CURRENT_DESKTOP_GUIDE_VERSION) return@withLock
        mutable.value = mutable.value.copy(busy = true)
        try {
            val saved = store.updateLatest { it.copy(guideAcknowledgedVersion =
                maxOf(it.guideAcknowledgedVersion, CURRENT_DESKTOP_GUIDE_VERSION)) }
            mutable.value = mutable.value.copy(ready = true,
                acknowledgedVersion = saved.settings.guideAcknowledgedVersion, problem = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(problem = DesktopGuideReminderProblem.SAVE) }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
}

internal fun desktopShowGuideReminder(state: DesktopGuideReminderState, blocked: Boolean) = state.pending && !blocked

@Composable
internal fun DesktopGuideReminder(state: DesktopGuideReminderState, blocked: Boolean,
    onView: () -> Unit, onLater: () -> Unit, onNever: () -> Unit, onRetry: () -> Unit) {
    if (!desktopShowGuideReminder(state, blocked)) return
    val t = LocalDesktopUiStrings.current
    DesktopModalSurface(reportActivity = false) {
        BoxWithConstraints(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            val reminderHeight = maxHeight
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = reminderHeight).background(DesktopBootstrapColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading(t(DesktopUiText.GUIDE_REMINDER)) }
                    DesktopIconAction(t(DesktopUiText.GUIDE_REMINDER_LATER), DesktopAppIcons.Close,
                        enabled = !state.busy, onClick = onLater)
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("guide-reminder-body").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.problem?.let { StatusText(t(if (it == DesktopGuideReminderProblem.READ)
                        DesktopUiText.GUIDE_REMINDER_READ_ERROR else DesktopUiText.GUIDE_REMINDER_SAVE_ERROR), DesktopBootstrapColors.warning) }
                    StatusText(t(DesktopUiText.GUIDE_REMINDER_BODY))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton(t(DesktopUiText.GUIDE_REMINDER_OPEN), enabled = !state.busy, onClick = onView)
                    BootstrapButton(t(DesktopUiText.GUIDE_REMINDER_LATER), secondary = true, enabled = !state.busy, onClick = onLater)
                    BootstrapButton(t(DesktopUiText.GUIDE_REMINDER_NEVER), secondary = true,
                        enabled = !state.busy && state.problem != DesktopGuideReminderProblem.READ, onClick = onNever)
                    if (state.problem == DesktopGuideReminderProblem.READ)
                        BootstrapButton(t(DesktopUiText.GUIDE_REMINDER_RETRY), secondary = true, enabled = !state.busy, onClick = onRetry)
                }
            }
        }
    }
}
