package com.example.chatbar.desktop

/** Presentation-only browser state. No repository entity is changed by disclosure or picker navigation. */
internal data class DesktopPrimaryChatBrowserState(
    val expandedSessionId: String? = null,
    val newChatOpen: Boolean = false,
    val characterQuery: String = "",
    val settingsSessionId: String? = null,
    val renameSessionId: String? = null,
    val wideBrowserExpanded: Boolean = true,
) {
    fun toggleWideBrowser() = copy(wideBrowserExpanded = !wideBrowserExpanded)
    fun browserVisible(size: DesktopShellSize, compactBrowser: Boolean): Boolean =
        if (size == DesktopShellSize.COMPACT) compactBrowser else wideBrowserExpanded
    fun toggleSummary(id: String): DesktopPrimaryChatBrowserState =
        copy(expandedSessionId = if (expandedSessionId == id) null else id)

    fun visiblePreview(item: DesktopPrimarySessionItem): String? =
        item.lastMessagePreview?.takeIf { expandedSessionId == item.id && it.isNotBlank() }

    fun openNewChat(): DesktopPrimaryChatBrowserState = copy(newChatOpen = true, characterQuery = "")

    fun closeNewChat(): DesktopPrimaryChatBrowserState = copy(newChatOpen = false, characterQuery = "")

    fun openSettings(id: String): DesktopPrimaryChatBrowserState = copy(settingsSessionId = id)

    fun filteredCharacters(characters: List<DesktopPrimaryChoice>): List<DesktopPrimaryChoice> =
        characters.filter { it.label.contains(characterQuery.trim(), ignoreCase = true) }
}

/** A session switch starts collapsed rather than reusing another session's disclosure. */
internal data class DesktopDiagnosticDisclosure(val sessionId: String?, val expanded: Boolean = false) {
    fun select(id: String?) = if (id == sessionId) this else DesktopDiagnosticDisclosure(id)
    fun toggle(id: String?) = select(id).let { it.copy(expanded = !it.expanded) }
}
