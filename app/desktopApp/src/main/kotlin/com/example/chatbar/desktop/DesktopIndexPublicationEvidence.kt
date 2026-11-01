package com.example.chatbar.desktop

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Volatile observations of existing journal boundaries, never publication or retry authority. */
internal class DesktopIndexPublicationEvidence(private val parent: DesktopIndexPublicationEvidence? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopIndexPublicationEvidence>
    private val lock = Any()
    private var ownPublication = false
    private val children = mutableListOf<DesktopIndexPublicationEvidence>()
    init { parent?.register(this) }
    val mayHaveChanged: Boolean get() {
        val (own, snapshot) = synchronized(lock) { ownPublication to children.toList() }
        return own || snapshot.any { it.mayHaveChanged }
    }
    private fun register(child: DesktopIndexPublicationEvidence) { synchronized(lock) { children += child } }
    fun publicationStarted() { synchronized(lock) { ownPublication = true } }
    fun rollbackConfirmed() { synchronized(lock) { ownPublication = false } }
}
