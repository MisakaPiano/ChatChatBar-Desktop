package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LanSyncPlan(val transaction: String, val snapshot: LanSyncSnapshot, val parents: Map<String, String>,
    val supersedes: String? = null)

object LanSyncPlanner {
    fun plan(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline,
        choices: Map<String, LanSyncChoice>, transaction: String): LanSyncPlan {
        val comparison = LanSyncCompare.compare(computer, phone, baseline)
        require(choices.keys == comparison.conflicts.map { it.unit }.toSet()) { "SYNC_CHOICES" }
        val left = computer.units(); val right = phone.units()
        val retained = mutableListOf<LanSyncRecord>()
        val incoming = mutableListOf<LanSyncRecord>()
        val identity = LanSyncIdentity(transaction = transaction)
        val parents = linkedMapOf<String, String>()
        comparison.automatic.forEach { (unit, useLeft) ->
            if (useLeft) retained += left[unit].orEmpty() else incoming += right[unit].orEmpty()
        }
        comparison.conflicts.forEach { conflict ->
            when (choices.getValue(conflict.unit)) {
                LanSyncChoice.KEEP_COMPUTER -> retained += left[conflict.unit].orEmpty()
                LanSyncChoice.KEEP_PHONE -> incoming += right[conflict.unit].orEmpty()
                LanSyncChoice.KEEP_BOTH -> {
                    retained += left[conflict.unit].orEmpty()
                    val fork = right[conflict.unit].orEmpty()
                    fork.forEach { record -> identity.declarations(record).forEach { (type, id) ->
                        identity.bind(type, id, lanSyncDerivedId(transaction, "$type/$id"))
                    } }
                    incoming += fork
                    if (conflict.unit.startsWith("conversation/") && fork.isNotEmpty()) {
                        val original = conflict.unit.removePrefix("conversation/")
                        parents[identity.value("chat_sessions", original)] = original
                    }
                }
            }
        }
        // One remote-side namespace also rebinds remote conversations to a copied card/world book.
        val mapper = LanSyncGraphRemapper(phone.records, identity)
        val rewritten = incoming.map { record ->
            val body = (if (record.type == "save_slots" && record.body["packageRef"] is JsonObject) {
                // The shared library envelope deliberately retains the complete immutable source archive.
                JsonObject(record.body + mapOf("id" to JsonPrimitive(identity.value("save_slots", record.id)),
                    "sessionId" to JsonPrimitive(identity.value("chat_sessions", requireNotNull(record.text("sessionId"))))))
            } else mapper.rewrite(record)).let { mapped ->
                if (record.type == "chat_sessions" && identity.value("chat_sessions", record.id) != record.id) {
                    val title = (mapped["displayTitleOverride"] as? JsonPrimitive)?.contentOrNull
                        ?: (mapped["title"] as? JsonPrimitive)?.contentOrNull ?: "对话"
                    JsonObject(mapped + mapOf("displayTitleOverride" to JsonPrimitive(title.take(60) + "（同步分支）"),
                        "lanSyncOrigin" to buildJsonObject {
                            put("parentSessionId", record.id); put("transaction", transaction); put("sourceDeviceId", phone.deviceId)
                        }))
                } else mapped
            }
            record.copy(id = requireNotNull(LanSyncScope.identity(record.type, body)), body = body)
        }
        val records = retained + rewritten
        val result = LanSyncSnapshot(deviceId = computer.deviceId, records = records,
            resources = (computer.resources + phone.resources).filterKeys { it in LanSyncScope.referencedResources(records) },
            excludedTypes = computer.excludedTypes + phone.excludedTypes)
        result.validate()
        LanSyncScope.validateGraph(result)
        return LanSyncPlan(transaction, result, parents)
    }
}
