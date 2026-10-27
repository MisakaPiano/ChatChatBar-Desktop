package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent minimal official145 raw positives, NOT an Android-exported archive.
 * The strict test decoder deliberately accepts only the small wire subset used here.
 * Full archive authentication/schema/inventory validation is performed first by unchanged S0.
 * No production decoder, serializer, restore adapter or credential transport is added.
 */
class G2AOfficialWireTest {
    @Serializable private data class ModelWire(
        val id: String, val displayName: String, val baseUrl: String, val apiKey: String,
        val modelName: String, val templateType: String = "OPENAI", val createdAt: Long,
    )
    @Serializable private data class EmbeddingWire(
        val id: String, val displayName: String, val baseUrl: String, val apiKey: String,
        val modelName: String, val dimensions: Int = 1536,
    )
    @Serializable private data class SettingsWire(val siliconFlowApiKey: String = "")
    @Serializable private data class CommunityWire(
        val accessToken: String, val refreshToken: String? = null, val tokenType: String = "bearer",
        val expiresAtMillis: Long = 0, val userId: String, val displayName: String,
    )
    private class SafeFixtureFailure : IllegalArgumentException("G2A_TEST_WIRE_REJECTED")
    private fun text(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun metadata(prefs: ByteArray, files: Map<String, ByteArray>): List<ImportCredentialSource> = try {
        val p = officialWireJson.decodeFromString<Official145Preferences>(text(prefs))
        fun presence(value: String?) = when { value == null -> ImportSecretPresence.ABSENT; value.isEmpty() -> ImportSecretPresence.EMPTY; else -> ImportSecretPresence.NONEMPTY }
        val records = mutableListOf(
            G2AMetadata.source(ImportCredentialKind.NOVEL_AI, presence(p.novelAiToken)),
            G2AMetadata.source(ImportCredentialKind.FISH, presence(p.fishAudioKey)),
            G2AMetadata.source(ImportCredentialKind.COMMUNITY, presence(p.communitySession)),
        )
        if (!p.communitySession.isNullOrEmpty()) officialWireJson.decodeFromString<CommunityWire>(p.communitySession)
        files.forEach { (path, bytes) ->
            val json = text(bytes)
            when {
                path == "entities/app_settings.json" -> records += G2AMetadata.source(ImportCredentialKind.SILICON_FLOW, presence(officialWireJson.decodeFromString<SettingsWire>(json).siliconFlowApiKey))
                path.startsWith("entities/model_configs/") -> {
                    val model = officialWireJson.decodeFromString<ModelWire>(json)
                    val template = com.example.chatbar.data.local.entity.ModelTemplate.valueOf(model.templateType)
                    records += G2AMetadata.source(ImportCredentialKind.MODEL, presence(model.apiKey),
                        ImportAccountIdentity(model.id, model.displayName, model.baseUrl, model.modelName, template))
                }
                path.startsWith("entities/embedding_configs/") || path == "entities/embedding_model_config.json" -> {
                    val model = officialWireJson.decodeFromString<EmbeddingWire>(json)
                    val kind = if (path == "entities/embedding_model_config.json") ImportCredentialKind.SINGLETON_EMBEDDING else ImportCredentialKind.LEGACY_EMBEDDING
                    records += G2AMetadata.source(kind, presence(model.apiKey), ImportAccountIdentity(model.id, model.displayName, model.baseUrl, model.modelName))
                }
                else -> throw SafeFixtureFailure()
            }
        }
        records
    } catch (_: Exception) { throw SafeFixtureFailure() }

    private fun files() = linkedMapOf(
        "entities/app_settings.json" to """{"siliconFlowApiKey":"FAKE_G2A_GLOBAL_NOT_REAL"}""".toByteArray(),
        "entities/model_configs/m.json" to """{"id":"m","displayName":"Model","baseUrl":"https://model.invalid/v1","apiKey":"FAKE_G2A_MODEL_NOT_REAL","modelName":"synthetic","createdAt":1}""".toByteArray(),
        "entities/embedding_configs/e.json" to """{"id":"e","displayName":"Embedding","baseUrl":"https://embedding.invalid/v1","apiKey":"FAKE_G2A_EMBEDDING_NOT_REAL","modelName":"synthetic"}""".toByteArray(),
        "entities/embedding_model_config.json" to """{"id":"e","displayName":"Embedding","baseUrl":"https://embedding.invalid/v1","apiKey":"FAKE_G2A_SINGLETON_NOT_REAL","modelName":"synthetic"}""".toByteArray(),
    )
    private fun prefs(community: Boolean = false) = officialWireJson.encodeToString(Official145Preferences(
        novelAiToken = "FAKE_G2A_NAI_NOT_REAL", fishAudioKey = "FAKE_G2A_FISH_NOT_REAL",
        communitySession = if (community) """{"accessToken":"FAKE_G2A_COMMUNITY_NOT_REAL","userId":"synthetic-user","displayName":"Synthetic"}""" else null)).toByteArray()
    private fun archive(preferences: ByteArray, files: Map<String, ByteArray>, encrypted: Boolean): Pair<ByteArray, ByteArray> {
        val counts = files.keys.groupingBy { it.removePrefix("entities/").substringBefore('/').removeSuffix(".json") }.eachCount()
        val manifest = officialWireJson.encodeToString(Official145Manifest(appVersionCode = 84, appVersionName = "1.4.5", createdAt = 1,
            sourceFilesRoot = "/data/user/0/synthetic.ccb/files", counts = counts, fileCount = files.size,
            totalBytes = files.values.sumOf { it.size.toLong() }, preferencesSha256 = digest(preferences))).toByteArray()
        val inventory = files.entries.joinToString("\n", postfix = "\n") { (path, bytes) ->
            officialWireJson.encodeToString(OfficialWireFileRecord(path, bytes.size.toLong(), digest(bytes))) }.toByteArray()
        val result = ByteArrayOutputStream().also { out ->
            val password = if (encrypted) PASSWORD.toCharArray() else null
            try {
                ZipOutputStream(Official147FixtureCodec.encrypting(out, password, 1)).use { zip ->
                    (files.mapKeys { "files/${it.key}" } + mapOf("preferences.json" to preferences, "inventory.jsonl" to inventory, "manifest.json" to manifest)).forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    }
                }
            } finally { password?.fill('\u0000') }
        }.toByteArray()
        return result to manifest
    }
    private suspend fun isolated(bytes: ByteArray, action: suspend (Path, Path) -> Unit) {
        val base = Files.createTempDirectory("p11-g2a-wire-").toAbsolutePath().normalize()
        try {
            val input = Files.write(base.resolve("source.cbbackup"), bytes)
            val before = digest(Files.readAllBytes(input)); val work = base.resolve("inspection-work")
            action(input, work)
            assertTrue(before == digest(Files.readAllBytes(input)))
        } finally {
            check(base.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            check(base.fileName.toString().startsWith("p11-g2a-wire-")); check(base.toFile().deleteRecursively())
        }
    }
    private fun retain(name: String, bytes: ByteArray) {
        val out = Files.createDirectories(Path.of("build/p11-s2-g2a/fixtures"))
        Files.write(out.resolve(name), bytes)
    }

    @Test fun `authenticated official code84 plain and password map all six protected families without enabling restore`() = runBlocking {
        val p = prefs(); val files = files()
        for (encrypted in listOf(false, true)) {
            val (bytes, manifest) = archive(p, files, encrypted)
            val label = if (encrypted) "password" else "plain"
            retain("$label.cbbackup", bytes); retain("manifest.json", manifest); retain("preferences.json", p)
            files.entries.forEachIndexed { i, (_, value) -> retain("entity-$i.json", value) }
            isolated(bytes) { input, work ->
                val password = if (encrypted) PASSWORD.toCharArray() else null
                try {
                    val report = DesktopBackupReadOnlyInspector().inspect(input, password)
                    assertTrue(report.formatReadable); assertFalse(report.restoreSupported); assertFalse(report.fullPortabilityAccepted)
                    assertEquals(84, report.appVersionCode); assertEquals(1, report.envelopeVersion)
                    val records = metadata(p, files)
                    val plan = G2AMetadata.plan(records, sha = report.sourceSha256)
                    assertTrue(plan.candidateConsistent); assertFalse(plan.restoreAuthorized)
                    assertEquals(6, plan.decisions.count { it.status == CredentialPlanStatus.CANDIDATE })
                    val failure = assertFailsWith<DesktopBackupCompatibilityException> { DesktopFullBackupArchive(work).inspect(input, password).close() }
                    assertEquals(DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED, failure.kind)
                    assertFalse(plan.toString().contains("FAKE_G2A"))
                } finally { password?.fill('\u0000') }
            }
        }
    }
    @Test fun `official145 exact transport field sets and independent default behavior`() {
        val p = prefs(); val (_, manifest) = archive(p, files(), false)
        val m = officialWireJson.parseToJsonElement(text(manifest)).jsonObject
        assertEquals(setOf("formatVersion", "appVersionCode", "appVersionName", "createdAt", "sourceFilesRoot", "counts", "fileCount", "totalBytes", "preferencesSha256"), m.keys)
        assertEquals(setOf("novelAiToken", "fishAudioKey", "communitySession", "brushSize", "brushType"), officialWireJson.parseToJsonElement(text(p)).jsonObject.keys)
        officialWireJson.decodeFromString<Official145Manifest>(text(manifest))
        val model = officialWireJson.decodeFromString<ModelWire>(text(files().getValue("entities/model_configs/m.json")))
        val embedding = officialWireJson.decodeFromString<EmbeddingWire>(text(files().getValue("entities/embedding_configs/e.json")))
        assertEquals("OPENAI", model.templateType); assertEquals(1536, embedding.dimensions)
        retain("field-contract.txt", ("Manifest: ${m.keys.sorted()}\nPreferences: novelAiToken,fishAudioKey,communitySession,brushSize,brushType\nOmitted: scope,retainedPreferences,templateType,dimensions\nDefaults: OPENAI,1536\n").toByteArray())
    }
    @Test fun `community strict default metadata becomes unmapped without exposing session`() = runBlocking {
        val p = prefs(true); val (bytes, _) = archive(p, emptyMap(), false)
        retain("community.cbbackup", bytes)
        isolated(bytes) { input, _ ->
            val report = DesktopBackupReadOnlyInspector().inspect(input, null)
            val plan = G2AMetadata.plan(metadata(p, emptyMap()), sha = report.sourceSha256)
            assertFalse(plan.candidateConsistent)
            assertEquals(CredentialPlanReason.NO_PROTECTED_SINK, plan.decisions.last().reason)
            assertFalse(plan.toString().contains("FAKE_G2A"))
        }
    }
    @Test fun `absent empty corrupt DTO unknown fields family and invalid UTF8 fail closed at test decoder boundary`() {
        val empty = metadata("{}".toByteArray(), emptyMap())
        assertTrue(empty.all { it.presence == ImportSecretPresence.ABSENT })
        val nullEmpty = metadata("""{"novelAiToken":null,"fishAudioKey":""}""".toByteArray(), emptyMap())
        assertEquals(ImportSecretPresence.ABSENT, nullEmpty[0].presence); assertEquals(ImportSecretPresence.EMPTY, nullEmpty[1].presence)
        val cases = listOf(
            byteArrayOf(0xc3.toByte(), 0x28) to emptyMap(),
            "null".toByteArray() to emptyMap(), "{bad".toByteArray() to emptyMap(),
            """{"novelAiToken":3}""".toByteArray() to emptyMap(),
            """{"retainedPreferences":[]}""".toByteArray() to emptyMap(),
            "{}".toByteArray() to mapOf("entities/unknown/x.json" to "{}".toByteArray()),
            "{}".toByteArray() to mapOf("entities/model_configs/m.json" to "{}".toByteArray()),
            "{}".toByteArray() to mapOf("entities/embedding_configs/e.json" to byteArrayOf(0xff.toByte())),
            """{"communitySession":"PRIVATE_BROKEN_SESSION"}""".toByteArray() to emptyMap(),
        )
        for ((p, files) in cases) {
            val error = assertFailsWith<SafeFixtureFailure> { metadata(p, files) }
            assertNull(error.cause); assertFalse(error.stackTraceToString().contains("PRIVATE_BROKEN_SESSION"))
        }
    }
    @Test fun `wrong password and authenticated invalid UTF8 never reach mapping`() = runBlocking {
        val (bytes, _) = archive(prefs(), files(), true)
        retain("wrong-password-source.cbbackup", bytes)
        isolated(bytes) { input, _ ->
            var mapped = false
            val error = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> {
                val report = DesktopBackupReadOnlyInspector().inspect(input, "FAKE_G2A_WRONG_PASSWORD".toCharArray())
                G2AMetadata.plan(metadata(prefs(), files()), sha = report.sourceSha256); mapped = true
            }
            assertFalse(mapped); assertEquals("AUTHENTICATION", error.stage); assertNull(error.cause)
        }
        val (invalid, _) = archive(byteArrayOf(0xc3.toByte(), 0x28), emptyMap(), false)
        isolated(invalid) { input, _ ->
            val error = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspect(input, null) }
            assertNull(error.cause)
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object { private const val PASSWORD = "FAKE_G2A_ARCHIVE_PASSWORD_NOT_REAL" }
}
