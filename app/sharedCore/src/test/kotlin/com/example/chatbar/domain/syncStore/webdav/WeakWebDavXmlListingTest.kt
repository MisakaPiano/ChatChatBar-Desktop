package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.CiphertextStoreFailure
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class WeakWebDavXmlListingTest {
    private val base = "https://synthetic.invalid/sync/weak-attempts-v1/commits/".toHttpUrl()
    private fun xml(children: List<Pair<String, Boolean>>, self: Boolean = true) =
        "<d:multistatus xmlns:d=\"DAV:\">" + (if (self) davXmlEntry(base.encodedPath, true) else "") +
            children.joinToString("") { davXmlEntry(it.first, it.second) } + "</d:multistatus>"
    private fun parse(value: String, shape: WeakWebDavListingShape = WeakWebDavListingShape.KEYS,
        after: String? = null, limit: Int = 100, maximum: Long = 1024 * 1024) =
        WeakWebDavXmlListing.parse(value.byteInputStream(), base, shape, after, limit, maximum)

    @Test fun sortedBoundedDuplicateDiscoverySupportsFullRescansAfterNewLowerIdsAppear() {
        val eight = "8".repeat(32); val nine = "9".repeat(32); val one = "1".repeat(32)
        val first = xml(listOf("$nine/" to true, "$eight/" to true, "$eight/" to true))
        assertEquals(listOf(eight), parse(first, limit = 1))
        assertEquals(listOf(nine), parse(first, after = eight))
        val added = xml(listOf("$nine/" to true, "$one/" to true, "$eight/" to true))
        assertEquals(listOf(nine), parse(added, after = eight))
        assertEquals(listOf(one, eight, nine), parse(added))
    }

    @Test fun eachLevelRequiresItsExactCollectionOrCanonicalAttemptShapeWithoutAnAttachmentCap() {
        val hash = "a".repeat(64); val operation = "b".repeat(32); val nonce = "c".repeat(32)
        assertEquals(listOf(hash), parse(xml(listOf("$hash/" to true)), WeakWebDavListingShape.HASHES))
        val leaf = "${Long.MAX_VALUE - WebDavStoreRecord.HEADER_BYTES}-$operation-$nonce.bin"
        assertEquals(listOf(leaf), parse(xml(listOf(leaf to false)), WeakWebDavListingShape.ATTEMPTS))
        for (bad in listOf("01-$operation-$nonce.bin", "${Long.MAX_VALUE}-$operation-$nonce.bin",
            "1-$operation.bin", "1-$operation-$nonce.bin/")) {
            davFailure(CiphertextStoreFailure.UNSAFE_PATH) {
                parse(xml(listOf(bad to false)), WeakWebDavListingShape.ATTEMPTS)
            }
        }
        davFailure(CiphertextStoreFailure.UNSAFE_PATH) { parse(xml(listOf("${"a".repeat(32)}/" to false))) }
        davFailure(CiphertextStoreFailure.UNSAFE_PATH) { parse(xml(listOf("${"a".repeat(32)}/" to true)), WeakWebDavListingShape.HASHES) }
    }

    @Test fun hostileOriginsPathsXmlEntitiesStatusesAndBudgetFailBeforeAnyCandidateEscapes() {
        val id = "a".repeat(32)
        for (href in listOf("https://other.invalid/$id/", "http://synthetic.invalid/${base.encodedPath}$id/",
            "../$id/", "$id/?q=1", "$id/#fragment", "${id.take(16)}%2f${id.take(16)}/")) {
            davFailure(CiphertextStoreFailure.UNSAFE_PATH) { parse(xml(listOf(href to true))) }
        }
        davFailure(CiphertextStoreFailure.INTEGRITY) { parse(xml(listOf("$id/" to true), self = false)) }
        val entity = "<?xml version=\"1.0\"?><!DOCTYPE d:multistatus [<!ENTITY x SYSTEM \"https://never-request.invalid/\">]>" +
            xml(listOf("$id/" to true)).replace(id, "&x;")
        davFailure(CiphertextStoreFailure.INTEGRITY) { parse(entity) }
        davFailure(CiphertextStoreFailure.PERMISSION) {
            parse("<d:multistatus xmlns:d=\"DAV:\">" + davXmlEntry(base.encodedPath, true) + davXmlEntry("$id/", true, 403) + "</d:multistatus>")
        }
        davFailure(CiphertextStoreFailure.UNSUPPORTED) { parse(xml(listOf("$id/" to true)), maximum = 1) }
    }
}
