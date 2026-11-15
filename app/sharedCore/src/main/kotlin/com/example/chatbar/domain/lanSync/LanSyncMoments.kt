package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.serialization.json.decodeFromJsonElement

/** Front-end history has its own graph; a missing historical chat/card is not a chat orphan. */
internal object LanSyncMoments {
    val types = setOf("moment_posts", "moment_tasks")

    fun components(snapshot: LanSyncSnapshot): Map<String, Set<String>> = snapshot.units()
        .filterValues { rows -> rows.any { it.type in types } }.mapValues { (_, rows) -> rows.map { it.key }.toSet() }

    data class Comparison(val computer: Map<String, List<LanSyncRecord>>, val phone: Map<String, List<LanSyncRecord>>,
        val ancestor: Map<String, String>)

    /** Both current graphs and prior common membership define one review boundary. Hashes alone
     * cannot recover a vanished link. Never guess a component's history from names or clocks. */
    fun comparison(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline): Comparison {
        fun momentUnit(key: String) = key.startsWith("moment-task/") || key.startsWith("moment-post/")
        val old = baseline.hashes.filterKeys(::momentUnit)
        require(old.keys == baseline.componentRecords.keys && baseline.componentRecords.values.sumOf { it.size.toLong() } <= 100_000) { "SYNC_COMPONENT_HISTORY_MISSING" }
        val retainedKeys = baseline.componentRecords.values.flatten()
        require(retainedKeys.distinct().size == retainedKeys.size) { "SYNC_COMPONENT_HISTORY_MISSING" }
        val parent = linkedMapOf<String, String>()
        fun find(key: String): String {
            var current = parent.getOrPut(key) { key }
            while (parent.getValue(current) != current) current = parent.getValue(current)
            var child = key
            while (parent.getValue(child) != current) { val next = parent.getValue(child); parent[child] = current; child = next }
            return current
        }
        fun join(a: String, b: String) { val first = find(a); val second = find(b); if (first != second) parent[second] = first }
        val rows = (computer.records + phone.records).filter { it.type in types }
        rows.forEach { record ->
            find(record.key)
            if (record.type == "moment_tasks") record.text("postId")?.takeIf { it.isNotBlank() }?.let { join(record.key, "moment_posts/$it") }
        }
        baseline.componentRecords.forEach { (unit, keys) ->
            require(keys.isNotEmpty() && keys.all { key -> key.length <= 600 && types.any { key.startsWith("$it/") } }) { "SYNC_COMPONENT_HISTORY_MISSING" }
            val anchor = if (unit.startsWith("moment-task/")) "moment_tasks/${unit.removePrefix("moment-task/")}"
                else "moment_posts/${unit.removePrefix("moment-post/")}"
            require(anchor in keys) { "SYNC_COMPONENT_HISTORY_MISSING" }
            val first = keys.first(); keys.forEach { join(first, it) }
        }
        val oldByRoot = baseline.componentRecords.entries.groupBy { find(it.value.first()) }
        val keysByRoot = parent.keys.toList().groupBy(::find)
        val anchors = keysByRoot.mapValues { (root, keys) ->
            oldByRoot[root]?.minOf { it.key } ?: keys.filter { it.startsWith("moment_tasks/") }.minOrNull()
                ?.let { "moment-task/${it.removePrefix("moment_tasks/")}" }
                ?: "moment-post/${keys.min().removePrefix("moment_posts/")}"
        }
        fun group(snapshot: LanSyncSnapshot) = snapshot.records.groupBy { record ->
            if (record.type in types) anchors.getValue(find(record.key)) else record.unit
        }
        val ancestral = baseline.hashes.filterKeys { !momentUnit(it) }.toMutableMap()
        oldByRoot.forEach { (root, entries) ->
            // Joining multiple prior groups changes the review boundary. If the sides differ,
            // neither can be proven unchanged from combined opaque hashes; require a choice.
            ancestral[anchors.getValue(root)] = if (entries.size == 1) old.getValue(entries.single().key)
                else "joined:" + lanSyncHash(kotlinx.serialization.json.JsonObject(entries.associate { it.key to
                    kotlinx.serialization.json.JsonPrimitive(old.getValue(it.key)) }))
        }
        return Comparison(group(computer), group(phone), ancestral)
    }

    fun units(records: List<LanSyncRecord>): Map<String, List<LanSyncRecord>> {
        // A task remains the anchor when PENDING gains its reserved/final post. Historical
        // duplicate task links share that component rather than duplicating the post record.
        val linked = records.filter { it.type == "moment_tasks" && !it.text("postId").isNullOrBlank() }
            .groupBy { requireNotNull(it.text("postId")) }
        val owner = linked.mapValues { (_, tasks) -> tasks.minOf { it.id } }
        return records.groupBy { record -> when (record.type) {
            "moment_tasks" -> "moment-task/${owner[record.text("postId")] ?: record.id}"
            "moment_posts" -> owner[record.id]?.let { "moment-task/$it" } ?: "moment-post/${record.id}"
            else -> record.unit
        } }
    }

    fun validate(record: LanSyncRecord) {
        require(!record.text("characterCardId").isNullOrBlank() && !record.text("sessionId").isNullOrBlank()) { "SYNC_MOMENT_BINDING" }
        if (record.type != "moment_posts") return
        val post = lanSyncJson.decodeFromJsonElement<MomentPost>(record.body)
        val versions = post.desktopImageVersions
        require(versions.size <= 4096 && versions.map { it.id }.distinct().size == versions.size &&
            versions.map { it.imagePath }.distinct().size == versions.size) { "SYNC_MOMENT_VERSIONS" }
        versions.forEach { version ->
            require(version.id.isNotBlank() && version.id.length <= 128 && version.imagePath.isNotBlank() &&
                version.imagePath.length <= 1024 && version.generatedImageMetadata?.let { it.imagePath == version.imagePath } != false) { "SYNC_MOMENT_VERSIONS" }
        }
        val selected = post.imagePath?.takeIf { it.isNotBlank() } ?: return
        val match = versions.firstOrNull { it.imagePath == selected }
        if (match == null) require(versions.size < 4096) { "SYNC_MOMENT_VERSIONS" }
        else require(match.imagePrompt == post.imagePrompt && match.generatedImageMetadata == post.generatedImageMetadata &&
            match.imageBrief == post.imageBrief) { "SYNC_MOMENT_VERSIONS" }
    }
}
