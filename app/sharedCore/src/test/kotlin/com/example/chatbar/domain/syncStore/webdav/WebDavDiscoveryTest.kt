package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class WebDavDiscoveryTest {
    @Test fun shuffledDuplicateListingProducesBoundedPagesAndCursorSurvivesReopen() = davFixture { f ->
        val keys = (1..5).map { commitKey(it) }
        val store = f.store()
        for (key in keys.reversed()) { val bytes = byteArrayOf(key.id.value.last().code.toByte()); store.put(davRequest(bytes, key), bytes.inputStream()) }
        f.dav.overrideRequest = { request ->
            if (request.method == "PROPFIND" && request.getHeader("Depth") == "1") {
                MockResponse().setResponseCode(207).setBody(davListing("/sync/commits/",
                    listOf(keys[4], keys[0], keys[3], keys[0], keys[2], keys[1], keys[4]).map(::davPath)))
            } else null
        }
        val first = store.listCommits(pageSize = 2)
        assertEquals(keys.take(2), first.entries.map { it.key })
        val second = f.store().listCommits(assertNotNull(first.nextCursor), 2)
        assertEquals(keys.drop(2).take(2), second.entries.map { it.key })
        val third = f.store().listCommits(assertNotNull(second.nextCursor), 2)
        assertEquals(keys.takeLast(1), third.entries.map { it.key }); assertNull(third.nextCursor)
    }

    @Test fun earlierIdAppearingAfterAPageRequiresAnExplicitFreshScan() = davFixture { f ->
        val store = f.store()
        for (value in listOf(2, 4)) putCommit(store, value)
        val first = store.listCommits(pageSize = 1)
        putCommit(store, 1)
        assertEquals(listOf(commitKey(4)), store.listCommits(assertNotNull(first.nextCursor), 10).entries.map { it.key })
        assertEquals(listOf(1, 2, 4).map(::commitKey), store.listCommits().entries.map { it.key })
        assertEquals(CommitListingSemantics.KEYSET_RESCAN_REQUIRED, store.capabilities.commitListing)
    }

    @Test fun failedOrTruncatedListingDoesNotReturnAPartialPageAndSameCursorCanRetry() = davFixture { f ->
        val store = f.store(); (1..3).forEach { putCommit(store, it) }
        val first = store.listCommits(pageSize = 1); val cursor = assertNotNull(first.nextCursor)
        f.dav.overrideRequest = { request -> if (request.method == "PROPFIND") MockResponse().setResponseCode(503) else null }
        davFailure(CiphertextStoreFailure.TRANSIENT) { store.listCommits(cursor, 1) }
        f.dav.overrideRequest = { request -> if (request.method == "PROPFIND") MockResponse().setResponseCode(207)
            .setBody(davListing("/sync/commits/", listOf(davPath(commitKey(2)))).removeSuffix("</d:multistatus>")) else null }
        davFailure(CiphertextStoreFailure.INTEGRITY) { store.listCommits(cursor, 1) }
        f.dav.overrideRequest = null
        assertEquals(listOf(commitKey(2)), f.store().listCommits(cursor, 1).entries.map { it.key })
    }

    @Test fun everyItemStatusAndCollectionShapeMustBeValid() = davFixture { f ->
        val store = f.store()
        for ((code, failure) in listOf(403 to CiphertextStoreFailure.PERMISSION, 404 to CiphertextStoreFailure.TRANSIENT)) {
            f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody(davListing("/sync/commits/", listOf(davPath(commitKey(1))), code)) }
            davFailure(failure) { store.listCommits() }
        }
        f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">" +
            davXmlEntry("/sync/commits/", false) + "</d:multistatus>") }
        davFailure(CiphertextStoreFailure.INTEGRITY) { store.listCommits() }
        f.dav.overrideRequest = { MockResponse().setResponseCode(200).setBody("a login page") }
        davFailure(CiphertextStoreFailure.IO) { store.listCommits() }
    }

    @Test fun maliciousHrefsNeverEscapeTheConfiguredOriginOrCollection() = davFixture { f ->
        for (href in listOf("https://example.invalid/${commitKey(1).id.value}.bin", "../../../outside.bin",
            "/sync/commits/${commitKey(1).id.value}%2fother.bin", "/sync/commits/${commitKey(1).id.value}.bin?download=1",
            "/sync/objects/${commitKey(1).id.value}.bin")) {
            f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody(davListing("/sync/commits/", listOf(href))) }
            davFailure(CiphertextStoreFailure.UNSAFE_PATH) { f.store().listCommits() }
        }
    }

    @Test fun externalAndInternalDtdEntitiesAreRejectedWithoutContactingTheirEndpoint() = davFixture { f ->
        MockWebServer().use { external ->
            external.start()
            val bodies = listOf(
                "<!DOCTYPE d:multistatus [<!ENTITY outside SYSTEM \"${external.url("/private")}\">]><d:multistatus xmlns:d=\"DAV:\">&outside;</d:multistatus>",
                "<!DOCTYPE d:multistatus [<!ENTITY inside \"expanded\">]><d:multistatus xmlns:d=\"DAV:\">&inside;</d:multistatus>",
            )
            for (body in bodies) {
                f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody(body) }
                davFailure(CiphertextStoreFailure.INTEGRITY) { f.store().listCommits() }
            }
            assertEquals(0, external.requestCount)
        }
    }

    @Test fun xmlFieldsAndNestingHaveWorkingBudgetsWithoutCappingTotalObjectStorage() = davFixture { f ->
        val excessiveHref = davListing("/sync/commits/", listOf("x".repeat(8193)))
        val deep = "<d:multistatus xmlns:d=\"DAV:\">" + "<x>".repeat(33) + "</x>".repeat(33) + "</d:multistatus>"
        for (body in listOf(excessiveHref, deep)) {
            f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody(body) }
            davFailure(CiphertextStoreFailure.INTEGRITY) { f.store().listCommits() }
        }
        f.dav.overrideRequest = { MockResponse().setResponseCode(207).setBody(davListing("/sync/commits/",
            (1..20).map { davPath(commitKey(it)) })) }
        davFailure(CiphertextStoreFailure.UNSUPPORTED) {
            f.store(budget = WebDavIoBudget(maximumListingBytes = 512)).listCommits()
        }
    }

    @Test fun cursorIsBoundToThePersistedRouteAndEndpointAndInvalidCursorMakesNoRequest() = davFixture { f ->
        val store = f.store(); putCommit(store, 1); putCommit(store, 2)
        val cursor = assertNotNull(store.listCommits(pageSize = 1).nextCursor)
        val before = f.server.requestCount
        davFailure(CiphertextStoreFailure.INVALID_CURSOR) { store.listCommits(CommitCursor("../outside")) }
        assertEquals(before, f.server.requestCount)
        val otherRoot = Files.createDirectory(f.root.resolve("other-route"))
        val differentRoute = WebDavCiphertextStore(WebDavHttpTransport(f.server.url("/sync/"), f.client,
            policy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP), otherRoot, SyncStoreId.random())
        davFailure(CiphertextStoreFailure.INVALID_CURSOR) { differentRoute.listCommits(cursor) }
        assertEquals(before, f.server.requestCount)
    }

    @Test fun missingCommitCollectionIsEmptyButForbiddenCollectionIsNot() = davFixture { f ->
        assertTrue(f.store().listCommits().entries.isEmpty())
        f.dav.overrideRequest = { MockResponse().setResponseCode(403) }
        davFailure(CiphertextStoreFailure.PERMISSION) { f.store().listCommits() }
    }

    private fun commitKey(value: Int) = CiphertextObjectKey(CiphertextObjectKind.COMMIT,
        SyncStoreId.parse(value.toString(16).padStart(32, '0')))
    private fun putCommit(store: CiphertextStore, value: Int) {
        val bytes = byteArrayOf(value.toByte()); store.put(davRequest(bytes, commitKey(value)), bytes.inputStream())
    }
}
