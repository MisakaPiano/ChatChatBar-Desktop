package com.example.chatbar.desktop.backup

import java.io.StringWriter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.test.*

class BackupMergeJsonStreamTest {
    @Test fun `typed identity mapping preserves user text and escaped payload tokens`() {
        val original = """{ "id":"source-id", "content":"Synthetic source-id \\u4e00 \\"quoted\\"", "extensions":{"id":"source-id"} }"""
        // Use a serialized arbitrary payload so test wording is not a product Prompt contract.
        val payload = "source-id / \"id\":\"source-id\" / \u4e00 / \n"
        val raw = buildJsonObject { put("id", "source-id"); put("content", payload)
            put("extensions", buildJsonObject { put("id", "source-id") }) }.toString()
        val out = StringWriter()
        BackupJsonStream(raw.reader(), out, mappedFields = setOf("id"),
            mapString = { value, fields -> if (fields == listOf("id")) "fork-id" else value }) { path, _ -> path }.copy()
        val result = backupJson.parseToJsonElement(out.toString()).jsonObject
        assertEquals(JsonPrimitive("fork-id"), result["id"])
        assertEquals(JsonPrimitive(payload), result["content"])
        assertEquals(JsonPrimitive("source-id"), result.getValue("extensions").jsonObject["id"])
        assertTrue(out.toString().contains(backupJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payload))))
    }

    @Test fun `replacing a value still strictly validates its original subtree`() {
        for (raw in listOf("""{"state":{"id":"a","id":"b"}}""", """{"state":{"bad":01}}""")) {
            assertFails {
                BackupJsonStream(raw.reader(), StringWriter(), replaceValue = { "null".takeIf { it == listOf("state") } }) { p, _ -> p }.copy()
            }
        }
    }

    @Test fun `streamed replacements suppress discarded identity observations`() {
        val observed = mutableListOf<String>(); val out = StringWriter()
        BackupJsonStream("""{"state":{"id":"discarded"},"id":"retained"}""".reader(), out,
            capturedFields = setOf("id"), observeField = { _, value -> observed += value },
            replaceValueStream = { fields, writer ->
                if (fields == listOf("state")) { writer.write("{\"replacement\":true}"); true } else false
            }) { p, _ -> p }.copy()
        assertEquals(listOf("retained"), observed)
        assertEquals(JsonPrimitive(true), backupJson.parseToJsonElement(out.toString()).jsonObject.getValue("state").jsonObject["replacement"])
    }

    @Test fun `typed map keys and resource dictionary keys use separate namespaces`() {
        val raw = """{"staleSourcesByNodeId":{"node-old":["turn-unchanged"]},"imageResources":{"image-old":{"data":"AAAA"}},"extensions":{"node-old":"unchanged"}}"""
        val out = StringWriter(); val resourceKeys = mutableListOf<String>()
        BackupJsonStream(raw.reader(), out,
            mapKey = { key, fields -> if (fields.lastOrNull() == "staleSourcesByNodeId") "node-new" else key }) { path, _ ->
            resourceKeys += path; "image-new"
        }.copy()
        val value = backupJson.parseToJsonElement(out.toString()).jsonObject
        assertEquals(setOf("node-new"), value.getValue("staleSourcesByNodeId").jsonObject.keys)
        assertEquals(JsonPrimitive("turn-unchanged"), value.getValue("staleSourcesByNodeId").jsonObject.getValue("node-new").jsonArray.single())
        assertEquals(setOf("image-new"), value.getValue("imageResources").jsonObject.keys)
        assertEquals(listOf("image-old"), resourceKeys)
        assertEquals(setOf("node-old"), value.getValue("extensions").jsonObject.keys)
    }

    @Test fun `sequence mapping keeps record ordering and object observation balanced`() {
        val events = mutableListOf<Pair<List<String>, Boolean>>(); val ids = mutableListOf<String>(); val out = StringWriter()
        BackupJsonStream("{\"id\":\"first\"}\n{\"id\":\"second\"}\n".reader(), out,
            mappedFields = setOf("id"), mapString = { value, _ -> "copy-$value" },
            capturedFields = setOf("id"), observeField = { _, value -> ids += value },
            observeObject = { fields, start -> events += fields to start }) { p, _ -> p }.copySequence()
        assertEquals(listOf("first", "second"), ids)
        assertEquals(listOf("copy-first", "copy-second"), out.toString().lineSequence().filter(String::isNotBlank).map {
            backupJson.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content
        }.toList())
        assertEquals(listOf(emptyList<String>() to true, emptyList<String>() to false,
            emptyList<String>() to true, emptyList<String>() to false), events)
    }

    @Test fun `large unmapped text remains streaming and cancellation remains cancellation`() {
        val raw = "{\"content\":\"" + "x".repeat(40000) + "\"}"
        val out = StringWriter()
        BackupJsonStream(raw.reader(), out) { p, _ -> p }.copy()
        assertEquals(raw, out.toString())
        assertFailsWith<CancellationException> {
            BackupJsonStream(raw.reader(), StringWriter(), checkActive = { throw CancellationException("Synthetic cancel") }) { p, _ -> p }.copy()
        }
    }
}
