package com.example.chatbar.desktop

import androidx.compose.runtime.*

internal data class DesktopStudioModels(
    val catalog: DesktopStudioModelCatalog,
    val authentication: DesktopDesignAuthentication,
)

/** Retained Studio projection. Authentication keeps its original protected cold-read owner. */
@Composable
internal fun rememberDesktopStudioModels(controller: DesktopNovelAiStudioController, selectedId: String?,
    accountRevision: Long? = null, readRetry: Int = 0, authRevision: Int = 0, auxiliary: String? = null): DesktopStudioModels {
    val catalog by produceState<DesktopStudioModelCatalog>(DesktopStudioModelCatalog.Loading,
        controller, accountRevision, readRetry) {
        value = DesktopStudioModelCatalog.Loading
        controller.observeModels().collect { value = it }
    }
    val authentication by produceState(DesktopDesignAuthentication(), controller, selectedId, auxiliary,
        accountRevision, authRevision, catalog) {
        value = controller.designAuthentication()
    }
    return DesktopStudioModels(catalog, authentication)
}
