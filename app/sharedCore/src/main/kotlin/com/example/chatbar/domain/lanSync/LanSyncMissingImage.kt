package com.example.chatbar.domain.lanSync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Device-local evidence in frozen plans and checkpoints. This reference is never a resource on the wire. */
internal object LanSyncMissingImage {
    const val CODE = "SYNC_GROUP_LOCAL_IMAGE_MISSING"
    private const val PREFIX = "ccb-lan-missing-image/"
    private val marker = Regex("${PREFIX}([a-f0-9-]{36})/[a-f0-9]{64}")

    private val attachmentPath = listOf("images")
    private val generatedPath = listOf("generatedImageMetadata", "imagePath")

    fun allows(type: String, path: List<String>) = type == "chat_messages" && (path == attachmentPath || path == generatedPath)

    fun reference(mediaId: String, relativePath: String): String =
        "$PREFIX$mediaId/${lanSyncHash("ccb-lan-missing-image-path/$relativePath".toByteArray(Charsets.UTF_8))}"

    fun isMarker(reference: String) = reference.startsWith(PREFIX)

    fun mediaId(reference: String): String? = if (isMarker(reference)) marker.matchEntire(reference)?.groupValues?.get(1) else null

    private fun references(record: LanSyncRecord): Sequence<String> = if (record.type != "chat_messages") emptySequence() else {
        val attached = (record.body[attachmentPath.single()] as? JsonArray).orEmpty().asSequence()
        val generated = (record.body[generatedPath.first()] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.get(generatedPath.last()) }
        (attached + generated).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter(::isMarker)
    }

    fun records(snapshot: LanSyncSnapshot): Sequence<LanSyncRecord> = snapshot.records.asSequence()
        .filter { references(it).any() }

    fun units(snapshot: LanSyncSnapshot): Set<String> = records(snapshot).map(LanSyncRecord::unit).toSet()

    /** A missing image reserves its real media identity across the entire local component. */
    fun requireRetained(current: LanSyncSnapshot, target: LanSyncSnapshot) {
        val before = records(current).associateBy(LanSyncRecord::key)
        require(records(target).associateBy(LanSyncRecord::key) == before) { CODE }
        if (before.isEmpty()) return
        val media = before.values.asSequence().flatMap(::references).mapNotNull(::mediaId).toSet()
        require(linkedUnits(current, media) == linkedUnits(target, media)) { CODE }
    }

    private fun linkedUnits(snapshot: LanSyncSnapshot, media: Set<String>): Map<String, String> {
        val prefix = "ccb-lan-v1/images/"
        val keys = snapshot.records.filter { record ->
            var linked = false
            LanSyncWorkspace.mapResources(record.body) { reference, _ ->
                if (reference.startsWith(prefix) && reference.substringAfter(prefix).substringBefore('/') in media) linked = true
                reference
            }
            linked
        }.mapTo(hashSetOf(), LanSyncRecord::key)
        return lanSyncUnitHashes(snapshot.units().filterValues { records -> records.any { it.key in keys } })
    }
}
