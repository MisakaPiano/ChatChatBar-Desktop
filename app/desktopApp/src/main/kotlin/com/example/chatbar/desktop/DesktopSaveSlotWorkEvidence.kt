package com.example.chatbar.desktop

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Observation of the original Job only; it neither authorizes nor changes publication. */
internal class DesktopSaveSlotWorkEvidence : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopSaveSlotWorkEvidence>
    @Volatile var publicationAttempted = false
    @Volatile var pickerCancelled = false
}
