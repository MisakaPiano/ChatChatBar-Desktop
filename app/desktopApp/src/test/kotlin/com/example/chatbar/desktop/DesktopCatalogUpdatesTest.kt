package com.example.chatbar.desktop

import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCatalogUpdatesTest {
    private fun info() = DanbooruCatalogUpdateInfo(DanbooruCatalogMetadata("a".repeat(40), "inline", 1),
        "b".repeat(40), "inline", "https://raw.githubusercontent.com/synthetic/tag.sqlite", "", 42)

    @Test fun `manual combined checks retain independent results failure and retries and never install implicitly`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var appFails = false; var catalogFails = true; var appCalls = 0; var catalogCalls = 0; var installs = 0
        val notices = DesktopUpdateNotices(scope, "0.1.0-preview.1", null, false) {
            appCalls++; if (appFails) error("synthetic app failure") else DesktopUpdateResult(null, false)
        }
        val catalog = DesktopCatalogUpdates(scope, { catalogCalls++; if (catalogFails) error("synthetic catalog failure") else info() }, { _, _, _ -> installs++ })
        try {
            checkDesktopProgramAndCatalog(notices, catalog)
            assertNotNull(notices.state.value.result); assertNull(notices.state.value.error)
            assertNotNull(catalog.state.value.error); assertEquals(0, installs)
            catalogFails = false; appFails = true
            checkDesktopProgramAndCatalog(notices, catalog)
            assertNotNull(notices.state.value.error); assertEquals(info(), catalog.state.value.update)
            assertNull(catalog.state.value.error); assertEquals(0, installs)
            catalog.check()!!.join(); assertEquals(2, appCalls); assertEquals(3, catalogCalls)
            assertEquals(0, installs)
            catalog.install()!!.join(); assertEquals(1, installs); assertNull(catalog.state.value.update)
            assertTrue(catalog.workRows.value.all { it.kind in setOf(DesktopWorkKind.CATALOG_CHECK, DesktopWorkKind.CATALOG_UPDATE) })
            assertTrue(catalog.workRows.value.none { it.kind == DesktopWorkKind.IMAGE })
        } finally { catalog.closeAndDrain(); notices.closeAndDrain(); scope.cancel() }
    }

    @Test fun `shared owner single flight and exact stop retain busy until cancellation cleanup drains before close`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleaning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = DesktopCatalogUpdates(scope, {
            calls++
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }, { _, _, _ -> error("no download") })
        try {
            val job = owner.check()!!; val id = owner.workRows.value.single().id
            assertSame(job, owner.check()); assertSame(job, owner.install()); assertEquals(1, calls)
            assertFalse(owner.stopTask("stale")); assertTrue(owner.stopTask(id)); cleaning.await()
            assertTrue(owner.state.value.busy); assertTrue(owner.workRows.value.single().active)
            val close = async { owner.closeAndDrain() }; yield(); assertFalse(close.isCompleted)
            release.complete(Unit); close.await(); assertFalse(owner.state.value.busy)
            assertEquals(DesktopWorkPhase.CANCELLED, owner.workRows.value.single().phase)
            assertNull(owner.check()); assertEquals(1, calls)
        } finally { release.complete(Unit); owner.closeAndDrain(); scope.cancel() }
    }

    @Test fun `late publication failure stays unconfirmed without image classification blind retry or raw exception text`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = DesktopCatalogUpdates(scope, { info() }, { _, _, publication -> publication(); error("SYNTHETIC_PRIVATE_PATH_AND_BODY") })
        try {
            owner.check()!!.join(); owner.install()!!.join()
            assertFalse(owner.state.value.busy); assertNull(owner.state.value.update); assertNotNull(owner.state.value.error)
            assertFalse(owner.state.value.details.orEmpty().contains("SYNTHETIC_PRIVATE_PATH_AND_BODY"))
            assertEquals(DesktopWorkPhase.UNCONFIRMED, owner.workRows.value.first().phase)
            assertEquals(DesktopWorkKind.CATALOG_UPDATE, owner.workRows.value.first().kind)
            assertFalse(owner.workRows.value.first().canRetry); assertNull(owner.install())
        } finally { owner.closeAndDrain(); scope.cancel() }
    }
}
