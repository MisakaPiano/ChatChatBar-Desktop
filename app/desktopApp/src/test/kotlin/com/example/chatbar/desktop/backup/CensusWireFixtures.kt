package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString

/** Independent145 raw wire source; no Inspector DTO or production census builder creates inputs. */
internal object CensusWireFixtures {
    const val SECRET = "FAKE_G2B_SOURCE_NOT_A_REAL_KEY"
    const val PASSWORD = "FAKE_G2B_PASSWORD_NOT_REAL"
    fun model(id: String = "m", key: String = "\"$SECRET\"", extra: String = "") =
        """{"id":"$id","displayName":"Synthetic","baseUrl":"https://synthetic.invalid/v1","apiKey":$key,"modelName":"synthetic","createdAt":1$extra}"""
    fun embedding(id: String = "e") = """{"id":"$id","displayName":"Synthetic","baseUrl":"https://embedding.invalid/v1","apiKey":"$SECRET","modelName":"synthetic"}"""
    fun files() = linkedMapOf("entities/app_settings.json" to """{"siliconFlowApiKey":"$SECRET"}""".toByteArray(),
        "entities/model_configs/m.json" to model().toByteArray(), "entities/embedding_configs/e.json" to embedding().toByteArray(),
        "entities/embedding_model_config.json" to embedding().toByteArray())
    fun bytes(prefs: String = """{"novelAiToken":"$SECRET","fishAudioKey":"$SECRET"}""", files: Map<String, ByteArray> = files(),
        encrypted: Boolean = false, badInventory: Boolean = false, badManifest: Boolean = false,
        version: Int = 1, code: Int = 84, manifestExtra: String = "", scope: OfficialWireScope? = null): ByteArray {
        val preferences = prefs.toByteArray()
        val counts = files.keys.filter { it.startsWith("entities/") }.groupingBy { it.removePrefix("entities/").substringBefore('/').removeSuffix(".json") }.eachCount()
        val manifest = if (version == 1) officialWireJson.encodeToString(Official145Manifest(appVersionCode = code,
            appVersionName = "1.4.5", createdAt = 1, sourceFilesRoot = "/data/user/0/synthetic.ccb/files", counts = counts,
            fileCount = files.size + if (badManifest) 1 else 0, totalBytes = files.values.sumOf { it.size.toLong() }, preferencesSha256 = digest(preferences)))
        else officialWireJson.encodeToString(OfficialModernManifest(formatVersion = 2, appVersionCode = code, appVersionName = "synthetic",
            createdAt = 1, sourceFilesRoot = "/data/user/0/synthetic.ccb/files", counts = counts, fileCount = files.size,
            totalBytes = files.values.sumOf { it.size.toLong() }, preferencesSha256 = digest(preferences),
            scope = scope ?: OfficialWireScope(emptySet(), 0, 0, 0, 0, 0, false, false, false, false)))
        val inventory = files.entries.joinToString("\n", postfix = "\n") { (path, value) ->
            officialWireJson.encodeToString(OfficialWireFileRecord(path, value.size.toLong(), if (badInventory) "0".repeat(64) else digest(value))) }
        return ByteArrayOutputStream().also { out ->
            val password = if (encrypted) PASSWORD.toCharArray() else null
            try { ZipOutputStream(Official147FixtureCodec.encrypting(out, password, version)).use { zip ->
                (files.mapKeys { "files/${it.key}" } + mapOf("preferences.json" to preferences, "inventory.jsonl" to inventory.toByteArray(), "manifest.json" to (manifest.dropLast(1) + manifestExtra + "}").toByteArray())).forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
                }
            } } finally { password?.fill('\u0000') }
        }.toByteArray()
    }
    suspend fun <T> input(bytes: ByteArray, label: String, action: suspend (Path) -> T): T {
        val base = Files.createTempDirectory("p11-g2b-").toAbsolutePath().normalize()
        val out = Files.createDirectories(Path.of("build/p11-s2-g2b/fixtures"))
        Files.write(out.resolve("$label.cbbackup"), bytes)
        try {
            val source = Files.write(base.resolve("source.cbbackup"), bytes)
            try { return action(source) } finally { check(Files.readAllBytes(source).contentEquals(bytes)) }
        } finally {
            check(base.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            check(base.fileName.toString().startsWith("p11-g2b-")); check(base.toFile().deleteRecursively())
            Files.writeString(out.resolve("$label-cleanup.txt"), "SOURCE_BYTES_UNCHANGED;OWNED_TEMP_REMOVED")
        }
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
