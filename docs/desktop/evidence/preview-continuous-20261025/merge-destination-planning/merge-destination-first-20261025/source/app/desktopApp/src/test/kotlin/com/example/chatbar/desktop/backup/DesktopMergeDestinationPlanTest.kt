package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.SyntheticSaveSlotCorpus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeDestinationPlanTest {
    private fun obj(raw: String) = backupJson.parseToJsonElement(raw).jsonObject
    private fun session(id: String, message: String, text: String) = mapOf(
        "entities/chat_sessions/$id.json" to obj("""{"id":"$id","characterCardId":"c","title":"Synthetic $id"}"""),
        "entities/chat_messages/${id}_$message.json" to backupJson.encodeToJsonElement(ChatMessage.serializer(),
            ChatMessage(message, id, MessageRole.USER, text, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun selection(pc: Map<String, JsonObject>, phone: Map<String, JsonObject>, choice: DesktopMergePreview.Choice = DesktopMergePreview.Choice.BOTH) =
        DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap()).let { it.select(it.conflicts.associate { c -> c.key to choice }) }

    @Test fun `both forks complete sessions with coherent destinations and keeps computer untouched`() {
        val pc = session("s", "m", "Computer"); val phone = session("s", "m", "Phone") +
            ("entities/chat_drafts/s.json" to obj("""{"sessionId":"s","content":"Phone draft"}"""))
        val plan = DesktopMergeDestinationPlan.create(selection(pc, phone), emptyMap(), emptyMap())
        val newSession = plan.identity.value("chat_sessions", "s"); val newMessage = plan.identity.value("chat_messages", "m")
        assertNotEquals("s", newSession); assertTrue(newMessage.length <= 128)
        assertEquals(pc["entities/chat_messages/s_m.json"], plan.entities["entities/chat_messages/s_m.json"])
        val message = plan.entities.getValue("entities/chat_messages/${newSession}_${newMessage}.json")
        assertEquals(JsonPrimitive("Phone"), message["content"]); assertEquals(JsonPrimitive("m"), message["currentAlternativeVersionId"])
        assertEquals(JsonPrimitive(newSession), plan.entities.getValue("entities/chat_drafts/$newSession.json")["sessionId"])
        assertEquals(5, plan.entities.size)
    }

    @Test fun `both model identities use Windows safe filenames and incoming consumers follow fork`() {
        fun model(endpoint: String) = "entities/model_configs/model:id.json" to obj("""{"id":"model:id","displayName":"Synthetic","baseUrl":"$endpoint","templateType":"OPENAI","modelName":"remote","apiKey":""}""")
        val pc = mapOf(model("https://pc.invalid")); val phone = mapOf(model("https://phone.invalid")) +
            ("entities/chat_sessions/phone.json" to obj("""{"id":"phone","characterCardId":"c","title":"Phone","modelId":"model:id"}"""))
        val plan = DesktopMergeDestinationPlan.create(selection(pc, phone), emptyMap(), emptyMap())
        val id = plan.identity.value("model_configs", "model:id")
        assertNotEquals("model:id", id)
        assertEquals(JsonPrimitive(id), plan.entities.getValue("entities/chat_sessions/phone.json")["modelId"])
        assertEquals(2, plan.entities.keys.count { it.startsWith("entities/model_configs/") })
        assertTrue(plan.entities.keys.none { ':' in it })
    }

    @Test fun `same media path with different bytes gets distinct flat destinations`() {
        val pc = mapOf("entities/character_cards/c.json" to obj("""{"id":"c","name":"Computer","avatar":"chatbar-backup-file:images/same.png"}"""))
        val phone = mapOf("entities/character_cards/c.json" to obj("""{"id":"c","name":"Phone","avatar":"chatbar-backup-file:images/same.png"}"""))
        val plan = DesktopMergeDestinationPlan.create(selection(pc, phone), mapOf("images/same.png" to "a".repeat(64)), mapOf("images/same.png" to "b".repeat(64)))
        val refs = plan.entities.values.map { it.getValue("avatar").jsonPrimitive.content.removePrefix(DesktopBackupPolicy.PREFIX) }
        assertEquals(2, refs.toSet().size); assertEquals(refs.toSet(), plan.resources.keys)
        assertTrue(refs.all { it.count { c -> c == '/' } == 1 })
        assertEquals(setOf(DesktopMergeDestinationPlan.Side.COMPUTER, DesktopMergeDestinationPlan.Side.PHONE), plan.resources.values.map { it.side }.toSet())
    }

    @Test fun `chosen settings fields merge without resetting unselected computer values`() {
        val pc = mapOf("entities/app_settings.json" to obj("""{"themeMode":"DARK","defaultModelId":"pc-model"}"""))
        val phone = mapOf("entities/app_settings.json" to obj("""{"themeMode":"LIGHT","defaultModelId":"phone-model"}"""))
        val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap(), InspectionScope(globalOptions = setOf("themeMode")))
        val plan = DesktopMergeDestinationPlan.create(preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE }), emptyMap(), emptyMap())
        assertEquals(obj("""{"themeMode":"LIGHT","defaultModelId":"pc-model"}"""), plan.entities.getValue("entities/app_settings.json"))
    }

    @Test fun `nested historical message declarations join compact ordered fork namespace`() = runBlocking<Unit> {
        val home = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-destination").toAbsolutePath()), "case-")
        val messages = listOf(SyntheticSaveSlotCorpus.message("a".repeat(128)), SyntheticSaveSlotCorpus.message("z".repeat(128)))
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), messages = messages)
        val slot = DesktopMergeRecord("entities/save_slots/slot.json", backupJson.encodeToJsonElement(SaveSlot.serializer(),
            SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", 2))).jsonObject)
        val pc = session("s", "m", "Computer"); val phone = session("s", "m", "Phone") + (slot.relative to slot.value)
        val select = selection(pc, phone)
        assertFails { DesktopMergeDestinationPlan.create(select, emptyMap(), emptyMap()) }
        val catalog = DesktopMergeSaveSlotPackage.catalog(source, slot, home)
        val plan = DesktopMergeDestinationPlan.create(select, emptyMap(), emptyMap(), mapOf(slot.relative to catalog))
        val mapped = messages.map { plan.identity.value("chat_messages", it.id) }
        assertTrue(mapped.all { it.length <= 128 }); assertEquals(mapped.sorted(), mapped)
        val request = plan.packages.single()
        DesktopMergeSaveSlotPackage.prepare(source, slot, plan.identity, plan.incomingRecords, home).use { prepared ->
            assertEquals(request.expectedSha256, prepared.originalSha256)
            assertEquals(request.destination, "save_slot_packages/${prepared.fileName}")
        }
    }

    @Test fun `missing resources case aliases and conflicting retained domains refuse`() {
        val phone = mapOf("entities/character_cards/c.json" to obj("""{"id":"c","avatar":"chatbar-backup-file:images/missing.png"}"""))
        assertEquals("MERGE_RESOURCE_NOT_SELECTED", assertFails { DesktopMergeDestinationPlan.create(selection(emptyMap(), phone), emptyMap(), emptyMap()) }.message)
        val empty = selection(emptyMap(), emptyMap())
        assertEquals("MERGE_RESOURCE_COLLISION", assertFails { DesktopMergeDestinationPlan.create(empty,
            mapOf("danbooru_catalog/x" to "a".repeat(64)), mapOf("danbooru_catalog/x" to "b".repeat(64))) }.message)
        assertFails { DesktopMergeDestinationPlan.create(empty, mapOf("audio/a.mp3" to "a".repeat(64), "audio/A.mp3" to "a".repeat(64)), emptyMap()) }
        assertFailsWith<CancellationException> { DesktopMergeDestinationPlan.create(empty, emptyMap(), emptyMap()) { throw CancellationException("Synthetic") } }
    }
}
