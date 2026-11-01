package com.example.chatbar.desktop

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Local import identity only; it is neither a generation task UUID nor serialized user data. */
internal data class DesktopExternalImportReceipt(val itemId: String, val attemptId: String, val targetId: String)
internal class DesktopExternalImportAttempt(val itemId: String, val id: String = UUID.randomUUID().toString()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopExternalImportAttempt>
    fun receipt(targetId: String) = DesktopExternalImportReceipt(itemId, id, targetId)
    fun matches(receipt: DesktopExternalImportReceipt?) = receipt?.itemId == itemId && receipt.attemptId == id
}
internal suspend fun currentExternalImportReceipt(targetId: String): DesktopExternalImportReceipt? =
    currentCoroutineContext()[DesktopExternalImportAttempt]?.receipt(targetId)
