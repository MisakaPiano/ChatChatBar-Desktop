package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** Only invoked by the Inspector after complete authentication, inventory and scope validation.
 * No paths, source IDs, text, prompts or raw objects leave this boundary. */
internal class MomentArchiveReadOnlyValidator {
    enum class State { SOURCE_GRAPH_VERIFIED_WITH_SCOPE, UNRESOLVED_DEPENDENCIES, BLOCKED, NOT_APPLICABLE }
    enum class Relation { IN_ARCHIVE, NOT_SELECTED, MISSING, UNKNOWN_SELECTION, CONFLICT, NULL_REFERENCE, SNAPSHOT_ONLY }
    enum class Field { SENDER_AVATAR, IMAGE_PATH, METADATA_IMAGE_PATH }
    enum class LinkField { CARD, SESSION, SENDER, TASK_POST }
    enum class Media { PRESENT, EMPTY, OMITTED_BY_SCOPE, MISSING_AT_SOURCE, MISSING, UNINTERPRETED }
    data class RawIdentity(val sourceSha256: String, val recordSha256: String, val length: Long)
    data class Link(val owner: RawIdentity, val field: LinkField, val state: Relation)
    data class Edge(val owner: RawIdentity, val field: Field, val referenceSha256: String?, val state: Media,
        val resourceSha256: String? = null, val resourceLength: Long? = null)
    data class Report(val state: State, val records: List<RawIdentity>, val edges: List<Edge>,
        val relations: Map<Relation, Int>, val posts: Int, val tasks: Int, val links: List<Link>, val failure: String? = null) {
        val restoreSupported = false
        val fullPortabilityAccepted = false
        val persistedSeal = false
        val taskRunnable = false
        val schedulerActivated = false
        val arbitraryTextCredentialFree = false
    }

    internal fun validate(manifest: InspectionManifest, inventory: Map<String, AppBackupFileRecord>,
        entities: Map<String, JsonObject>, sourceHash: String, knownCredentials: Boolean, active: () -> Unit): Report {
        val raw = mutableListOf<RawIdentity>()
        val edges = mutableListOf<Edge>()
        val relations = mutableMapOf<Relation, Int>()
        val posts = linkedMapOf<String, MomentPostSnapshot>()
        val tasks = linkedMapOf<String, MomentTaskSnapshot>()
        val postIdentities = mutableMapOf<String, RawIdentity>()
        val taskIdentities = mutableMapOf<String, RawIdentity>()
        val links = mutableListOf<Link>()
        fun relation(value: Relation) { relations[value] = (relations[value] ?: 0) + 1 }
        fun link(owner: RawIdentity, field: LinkField, value: Relation) { links += Link(owner, field, value); relation(value) }
        fun result(state: State, failure: String? = null) = Report(state, raw.toList(), edges.toList(), relations.toMap(), posts.size, tasks.size, links.toList(), failure)
        if (knownCredentials) return result(State.BLOCKED, "KNOWN_CREDENTIALS")
        try {
            for (path in entities.keys) {
                active()
                val type = DesktopBackupPolicy.entityType(path)
                if (type !in DesktopBackupPolicy.schemas && type != "moment_posts" && type != "moment_tasks")
                    return result(State.BLOCKED, "UNKNOWN_ENTITY_FAMILY")
            }
            val foldedResources = inventory.keys.associateBy { it.lowercase(java.util.Locale.ROOT) }
            val dependencies = entities.filterKeys { DesktopBackupPolicy.entityType(it) in setOf("character_cards", "chat_sessions") }
                .entries.groupBy { DesktopBackupPolicy.entityType(it.key) }
                .mapValues { (_, entries) -> entries.map { it.value }.groupBy { it["id"]?.jsonPrimitive?.contentOrNull } }
            entities.forEach { (path, value) ->
                active()
                val type = DesktopBackupPolicy.entityType(path)
                if (type == "moment_posts" || type == "moment_tasks") {
                    val record = inventory.getValue(path)
                    val identity = RawIdentity(sourceHash, record.sha256, record.size)
                    raw += identity // Hash/length of exact authenticated raw bytes, never re-encoded JSON.
                    if (type == "moment_posts") {
                        val post = backupJson.decodeFromJsonElement<MomentPostSnapshot>(value)
                        require(post.id.isNotBlank() && posts.put(post.id, post) == null)
                        postIdentities[post.id] = identity
                        val projected = manifest.scope?.momentsImages == false
                        require(!projected || (post.imagePath == null && post.generatedImageMetadata == null))
                        fun edge(field: Field, reference: String?, omitted: Boolean = false) {
                            val digest = reference?.let { DesktopBackupPolicy.digest(it.toByteArray(Charsets.UTF_8)) }
                            if (reference.isNullOrEmpty()) { edges += Edge(identity, field, digest, if (omitted) Media.OMITTED_BY_SCOPE else Media.EMPTY); return }
                            if (!reference.startsWith("chatbar-backup-file:")) { edges += Edge(identity, field, digest, Media.UNINTERPRETED); return }
                            val target = reference.removePrefix("chatbar-backup-file:")
                            DesktopBackupPolicy.path(target)
                            require(target.startsWith("images/") && target.none { it in ":<>\"|?*%" } && target.split('/').all { !it.endsWith('.') && !it.endsWith(' ') })
                            require(java.text.Normalizer.isNormalized(target, java.text.Normalizer.Form.NFC))
                            require(foldedResources[target.lowercase(java.util.Locale.ROOT)].let { it == null || it == target })
                            val resource = inventory[target]
                            val historical = resource == null && field == Field.SENDER_AVATAR && manifest.appVersionCode in 85..86
                            edges += Edge(identity, field, digest, when { resource != null -> Media.PRESENT; historical -> Media.MISSING_AT_SOURCE; else -> Media.MISSING }, resource?.sha256, resource?.size)
                        }
                        edge(Field.SENDER_AVATAR, post.senderAvatar)
                        edge(Field.IMAGE_PATH, post.imagePath, projected)
                        edge(Field.METADATA_IMAGE_PATH, post.generatedImageMetadata?.imagePath, projected)
                    } else {
                        val task = backupJson.decodeFromJsonElement<MomentTaskSnapshot>(value)
                        require(task.id.isNotBlank() && tasks.put(task.id, task) == null)
                        taskIdentities[task.id] = identity
                    }
                }
            }
            if (raw.isEmpty()) return result(State.NOT_APPLICABLE)
            fun absentDependency(type: String): Relation {
                val scope = manifest.scope ?: return Relation.MISSING
                val count = if (type == "character_cards") scope.characters else scope.sessions
                return if (count == 0) Relation.NOT_SELECTED else Relation.UNKNOWN_SELECTION
            }
            fun dependency(owner: RawIdentity, field: LinkField, id: String, type: String): JsonObject? {
                active()
                require(id.isNotBlank())
                val matches = dependencies[type]?.get(id).orEmpty()
                require(matches.size <= 1)
                link(owner, field, if (matches.isNotEmpty()) Relation.IN_ARCHIVE else absentDependency(type))
                return matches.singleOrNull()
            }
            fun owners(owner: RawIdentity, cardId: String, sessionId: String, senderId: String?) {
                val card = dependency(owner, LinkField.CARD, cardId, "character_cards")
                val session = dependency(owner, LinkField.SESSION, sessionId, "chat_sessions")
                if (session != null && session["characterCardId"]?.jsonPrimitive?.contentOrNull != cardId) relation(Relation.CONFLICT)
                if (senderId == null) link(owner, LinkField.SENDER, Relation.SNAPSHOT_ONLY)
                else if (card == null) link(owner, LinkField.SENDER, absentDependency("character_cards"))
                else {
                    val found = (card["characters"] as? JsonArray).orEmpty().count { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == senderId }
                    link(owner, LinkField.SENDER, if (found == 1) Relation.IN_ARCHIVE else if (found == 0) Relation.MISSING else Relation.CONFLICT)
                }
            }
            posts.values.forEach { owners(postIdentities.getValue(it.id), it.characterCardId, it.sessionId, it.senderCharacterId) }
            tasks.values.forEach { task ->
                val identity = taskIdentities.getValue(task.id)
                owners(identity, task.characterCardId, task.sessionId, null)
                val post = task.postId?.let(posts::get)
                link(identity, LinkField.TASK_POST, when { task.postId == null -> Relation.NULL_REFERENCE; post == null -> Relation.MISSING
                    post.characterCardId != task.characterCardId || post.sessionId != task.sessionId -> Relation.CONFLICT
                    else -> Relation.IN_ARCHIVE })
            }
            return result(when {
                edges.any { it.state in setOf(Media.MISSING, Media.UNINTERPRETED) } || Relation.CONFLICT in relations -> State.BLOCKED
                Relation.NOT_SELECTED in relations || Relation.MISSING in relations || Relation.UNKNOWN_SELECTION in relations -> State.UNRESOLVED_DEPENDENCIES
                else -> State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE
            })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { return result(State.BLOCKED, "MOMENT_SCHEMA_OR_GRAPH") }
    }
}
