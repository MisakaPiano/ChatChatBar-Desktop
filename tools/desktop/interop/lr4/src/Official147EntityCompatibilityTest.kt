package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Independent, invented wire records. No user archive or preset is a test fixture. */
class Official147EntityCompatibilityTest {
    private fun archive(type: String, json: String, code: Int = 86, encrypted: Boolean = false,
        manifestLast: Boolean = false): ByteArray {
        val path = if (type == "character_cards") "entities/character_cards/synthetic.json" else "entities/$type.json"
        val original = CensusWireFixtures.bytes(prefs = "{}", files = mapOf(path to json.toByteArray()),
            version = if (code == 84) 1 else 2, code = code)
        val entries = linkedMapOf<String, ByteArray>()
        AppBackupCodec.decryptingForInspection(original.inputStream(), null) { _, _ -> }.use { plain ->
            ZipInputStream(plain).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    var bytes = zip.readBytes()
                    if (entry.name == "manifest.json" && code != 84) {
                        val manifest = backupJson.parseToJsonElement(bytes.decodeToString()).jsonObject
                        val scope = manifest.getValue("scope").jsonObject
                        bytes = JsonObject(manifest + ("scope" to JsonObject(scope + mapOf(
                            "characters" to JsonPrimitive(if (type == "character_cards") 1 else 0),
                            "studioSettings" to JsonPrimitive(type == "novelai_studio_draft")
                        )))).toString().toByteArray()
                    }
                    entries[entry.name] = bytes
                }
            }
        }
        val ordered = entries.entries.sortedBy { if (manifestLast && it.key == "manifest.json") 1 else 0 }
        return ByteArrayOutputStream().also { out ->
            Official147FixtureCodec.encrypting(out, if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null,
                if (code == 84) 1 else 2).use { envelope ->
                ZipOutputStream(envelope).use { zip -> ordered.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                } }
            }
        }.toByteArray()
    }

    private val card = """{"id":"synthetic","name":"Synthetic","createdAt":1,"updatedAt":1"""
    private fun read(type: String, json: String, code: Int = 86, encrypted: Boolean = false,
        manifestLast: Boolean = false) = archive(type, json, code, encrypted, manifestLast).let { bytes ->
        val before = bytes.copyOf()
        LargeArchiveReadOnly.consume(bytes.inputStream(), if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null)
            .also { assertContentEquals(before, bytes); assertFalse(it.restoreSupported); assertEquals("SEMANTICALLY_BLOCKED", it.semanticState) }
    }
    private fun recognized(type: String, json: String, encrypted: Boolean = false, manifestLast: Boolean = false) {
        val r = read(type, json, encrypted = encrypted, manifestLast = manifestLast)
        assertEquals(1, r.typedRecords); assertEquals(0, r.unsupportedRecords)
    }
    private fun unsupported(type: String, json: String, code: Int = 86) {
        val r = read(type, json, code); assertEquals(1, r.unsupportedRecords); assertEquals(0, r.typedRecords)
    }

    @Test fun official147NullableCardImageSettings() = recognized("character_cards", "$card,\"defaultImageGenerationSettings\":null}")
    @Test fun official147StudioVarietyFalseAndTrue() {
        for (value in listOf("false", "true")) recognized("novelai_studio_draft",
            """{"v45Settings":{"varietyPlus":$value},"v5Settings":{"varietyPlus":$value}}""")
    }
    @Test fun official147NondefaultCardSettingsAreStrictlyTyped() = recognized("character_cards",
        "$card," + """"defaultImageGenerationSettings":{"sampler":"DPM_PLUS_PLUS_2M_SDE","steps":32,"guidance":7.5,"cfgRescale":0.4,"varietyPlus":true}}""")
    @Test fun official147StudioSamplerKnownOnlyToNewerSchema() = recognized("novelai_studio_draft",
        """{"v45Settings":{"sampler":"DPM_PLUS_PLUS_2M_SDE","varietyPlus":true}}""")
    @Test fun encryptedAndManifestLastUseAuthenticatedSourceVersion() {
        recognized("character_cards", "$card,\"defaultImageGenerationSettings\":null}", encrypted = true, manifestLast = true)
    }
    @Test fun oldSchemaRemainsStrict() {
        assertEquals(0, read("character_cards", "$card}", 84).unsupportedRecords)
        unsupported("character_cards", "$card,\"defaultImageGenerationSettings\":null}", 84)
        unsupported("novelai_studio_draft", """{"v45Settings":{"varietyPlus":false}}""", 84)
    }
    @Test fun futureFieldsStillUnsupported() {
        unsupported("character_cards", "$card,\"defaultImageGenerationSettings\":null,\"future\":1}")
        unsupported("character_cards", "$card," + """"defaultImageGenerationSettings":{"future":1}}""")
        unsupported("novelai_studio_draft", """{"v45Settings":{"varietyPlus":false,"future":1}}""")
        unsupported("novelai_studio_draft", """{"v45Settings":{"varietyPlus":false},"future":1}""")
    }
    @Test fun invalidVarietyTypesAndUnknownEnumsRemainUnsupported() {
        for (value in listOf("null", "\"false\"", "0", "[]", "{}")) {
            unsupported("novelai_studio_draft", """{"v45Settings":{"varietyPlus":$value}}""")
            unsupported("character_cards", "$card," + """"defaultImageGenerationSettings":{"varietyPlus":$value}}""")
        }
        unsupported("novelai_studio_draft", """{"v5Settings":{"sampler":"FUTURE"}}""")
        unsupported("novelai_studio_draft", """{"v45Settings":null}""")
        unsupported("character_cards", "$card,\"defaultImageGenerationSettings\":false}")
    }
}
