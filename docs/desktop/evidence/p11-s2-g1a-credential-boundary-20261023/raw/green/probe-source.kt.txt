package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.EmbeddingCredentialScope
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** G1A: real store/backup/journal, explicit synthetic paths and a NON-CRYPTOGRAPHIC protector. */
class P11S2CredentialTransactionBoundaryProbeTest {
    private class FakeProtector : SecretProtector {
        var plaintextBuffer: ByteArray? = null
        var ciphertextBuffer: ByteArray? = null
        override fun protect(plaintext: ByteArray): ByteArray {
            plaintextBuffer = plaintext
            return plaintext.reversedArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray().also { ciphertextBuffer = it }
        }
        override fun unprotect(ciphertext: ByteArray): ByteArray =
            ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray().reversedArray()
    }

    private class Fixture(val name: String) : AutoCloseable {
        val base = Files.createTempDirectory("p11-s2-g1a-").toAbsolutePath().normalize()
        val root = Files.createDirectory(base.resolve("root"))
        val secrets = Files.createDirectory(base.resolve("fake-secret-root"))
        val stage = Files.createDirectory(base.resolve("stage"))
        val work = Files.createDirectory(base.resolve("inspection-work"))
        val gate = DesktopDataOperationCoordinator()
        val protector = FakeProtector()
        val store = WindowsSecretStore(secrets, protector)
        val out = Files.createDirectories(Path.of("build", "p11-s2-g1a-credential-probe"))
        var owner: DesktopDataRootOwnership? = acquire()
        init { put(root, DATA, OLD_DATA); capture("created") }
        fun resolved() = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        fun acquire() = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved())).ownership
        fun seed() { store.save(A, OLD_A); store.save(B, OLD_B) }
        fun reopened() = WindowsSecretStore(secrets, FakeProtector()) // Not a Windows-user/process switch.
        fun faultWriter(failOn: Int, afterPublish: Boolean = false): WindowsSecretStore {
            var calls = 0
            return WindowsSecretStore(secrets, protector, SecretBlobWriter { target, bytes ->
                calls++
                if (calls == failOn && !afterPublish) throw IOException("SYNTHETIC_PRE_PUBLISH_WRITE_FAULT")
                AtomicSecretBlobWriter.write(target, bytes)
                if (calls == failOn && afterPublish) throw IOException("SYNTHETIC_POST_PUBLISH_WRITE_FAULT")
            })
        }
        suspend fun schedule(): DesktopFullBackupCommitJournal {
            val old = root.resolve(DATA)
            val next = put(stage, "new-data", NEW_DATA)
            val journal = DesktopFullBackupCommitJournal(root)
            gate.withExclusiveMaintenance {
                journal.schedule(mapOf(DATA to next), mapOf(DATA to digest(Files.readAllBytes(old))))
            }
            return journal
        }
        fun capture(label: String) {
            val entries = Files.walk(base).use { paths -> paths.sorted().toList().map { path ->
                check(!Files.isSymbolicLink(path))
                buildJsonObject {
                    val relative = base.relativize(path).joinToString("/")
                    put("path", relative); put("kind", if (Files.isDirectory(path, NOFOLLOW_LINKS)) "DIRECTORY" else "FILE")
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        put("length", Files.size(path))
                        if (path.fileName.toString() == DesktopDataRootOwnership.LOCK_FILE_NAME && DesktopDataRootOwnership.isHeld(root))
                            put("digestStatus", "HELD_LOCK_LENGTH_ONLY")
                        else put("sha256", digest(Files.readAllBytes(path)))
                        if (path.startsWith(secrets)) {
                            Files.copy(path, out.resolve("$name-$label-${path.fileName}.protected.fixture.bin"), REPLACE_EXISTING)
                            for (fake in FAKE_VALUES) assertFalse(contains(Files.readAllBytes(path), fake.toByteArray()), "Protected test blob contained plaintext")
                        }
                    }
                }
            } }
            Files.writeString(out.resolve("$name-$label.json"), buildJsonObject {
                put("rootOwned", DesktopDataRootOwnership.isHeld(root)); put("coordinator", gate.state.name)
                put("entries", JsonArray(entries))
            }.toString())
        }
        fun outcome(value: String) {
            assertTrue(FAKE_VALUES.none { value.contains(it) })
            Files.writeString(out.resolve("$name-outcome.txt"), value)
            println("G1A $name $value")
        }
        override fun close() {
            try { runBlocking { gate.closeAndDrain() } } finally { owner?.close(); owner = null }
            assertFalse(DesktopDataRootOwnership.isHeld(root)); assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            capture("released-final"); acquire().close()
            assertTrue(Files.list(secrets).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })
            require(base.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            require(base.fileName.toString().startsWith("p11-s2-g1a-"))
            check(base.toFile().deleteRecursively())
            Files.writeString(out.resolve("$name-cleanup.txt"), "OWNED_TEMP_REMOVED=true ROOT_LOCK_RELEASED=true FAKE_PROTECTOR_ONLY=true")
        }
    }

    @Test fun `all credential namespaces and delimiter variants retain independent identities`() = Fixture("identity").use { f ->
        val keys = listOf(A, B, DesktopCredentialKey.SiliconFlowApiKey, DesktopCredentialKey.ModelApiKey("same:id"),
            DesktopCredentialKey.ModelApiKey("same/id"), DesktopCredentialKey.ModelApiKey("same"),
            DesktopCredentialKey.LegacyEmbeddingApiKey("same:id"), DesktopCredentialKey.LegacyEmbeddingApiKey("same/id"),
            DesktopCredentialKey.SingletonEmbeddingApiKey)
        keys.forEachIndexed { index, key -> f.store.save(key, if (index % 2 == 0) OLD_A else OLD_B) }
        f.capture("saved")
        val paths = keys.map(f.store::secretPath)
        assertEquals(keys.size, paths.distinct().size)
        paths.forEach { assertTrue(it.parent == f.secrets && it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.ccbsecret"))) }
        keys.forEachIndexed { index, key -> assertTrue(f.reopened().load(key) == if (index % 2 == 0) OLD_A else OLD_B, "Logical key mismatch") }
        f.outcome("SYNTHETIC_OBSERVED EXISTING_SUPPORTED 9 distinct namespaced keys; import account mapping NOT_PROVEN")
    }

    @Test fun `model hydration binds exact ID not display name and does not authenticate provider identity`() = runBlocking<Unit> {
        Fixture("model-mapping").use { f ->
            val key = DesktopCredentialKey.ModelApiKey("target-model")
            f.store.save(key, OLD_A)
            val policy = DesktopModelCredentialPersistencePolicy(f.store)
            val target = ModelConfig("target-model", "Same synthetic name", "https://target.invalid/v1", "", "synthetic", createdAt = 1)
            assertTrue(policy.hydrate(target).apiKey == OLD_A)
            assertTrue(policy.hydrate(target.copy(id = "source-model", baseUrl = "https://source.invalid/v1")).apiKey.isEmpty())
            assertTrue(policy.hydrate(target.copy(baseUrl = "https://different.invalid/v1")).apiKey == OLD_A)
            val failure = assertFailsWith<UnsafeDesktopPlaintextCredentialException> { policy.hydrate(target.copy(apiKey = NEW_A)) }
            safeError(failure); f.capture("hydrated")
            f.outcome("SYNTHETIC_OBSERVED exact model ID only; same-name different ID UNMAPPED_DEPENDENCY; same ID changed endpoint not independently authenticated")
        }
    }

    @Test fun `legacy embedding and singleton bindings do not alias even for identical DTO ID`() = runBlocking<Unit> {
        Fixture("embedding-mapping").use { f ->
            val dto = EmbeddingConfig("e", "Same synthetic name", "https://embedding.invalid/v1", "", "synthetic")
            f.store.save(DesktopCredentialKey.LegacyEmbeddingApiKey("e"), OLD_A)
            f.store.save(DesktopCredentialKey.SingletonEmbeddingApiKey, OLD_B)
            val policy = DesktopEmbeddingCredentialPersistencePolicy(f.store)
            assertTrue(policy.hydrate(EmbeddingCredentialScope.Legacy("e"), dto).apiKey == OLD_A)
            assertTrue(policy.hydrate(EmbeddingCredentialScope.Singleton, dto).apiKey == OLD_B)
            assertTrue(policy.hydrate(EmbeddingCredentialScope.Legacy("other"), dto).apiKey.isEmpty())
            f.capture("hydrated"); f.outcome("SYNTHETIC_OBSERVED EXISTING_SUPPORTED distinct embedding scopes; source-to-target scope mapping NEEDS_ADAPTER")
        }
    }

    @Test fun `first key prepublish failure retains both old protected blobs`() = Fixture("first-write-fault").use { f ->
        f.seed(); val oldA = Files.readAllBytes(f.store.secretPath(A)); val oldB = Files.readAllBytes(f.store.secretPath(B)); f.capture("before")
        val faulty = f.faultWriter(1)
        val error = assertFailsWith<DesktopSecretStoreException> { faulty.save(A, NEW_A); faulty.save(B, NEW_B) }
        safeError(error); assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, error.kind)
        assertTrue(f.reopened().load(A) == OLD_A && f.reopened().load(B) == OLD_B, "Unexpected key state")
        assertContentEquals(oldA, Files.readAllBytes(f.store.secretPath(A))); assertContentEquals(oldB, Files.readAllBytes(f.store.secretPath(B)))
        f.capture("failed"); f.outcome("SYNTHETIC_OBSERVED SINGLE_KEY_ATOMIC prepublish fault1 BOTH_OLD; native move crash NOT_TESTED")
    }

    @Test fun `second key failure leaves first key new and second key old`() = Fixture("second-write-fault").use { f ->
        f.seed(); val oldB = Files.readAllBytes(f.store.secretPath(B)); f.capture("before")
        val faulty = f.faultWriter(2)
        safeError(assertFailsWith<DesktopSecretStoreException> { faulty.save(A, NEW_A); f.capture("after-first"); faulty.save(B, NEW_B) })
        assertTrue(f.reopened().load(A) == NEW_A && f.reopened().load(B) == OLD_B, "Unexpected partial state")
        assertContentEquals(oldB, Files.readAllBytes(f.store.secretPath(B)))
        f.capture("failed"); f.outcome("SYNTHETIC_OBSERVED MULTI_KEY_PARTIAL A_NEW_B_OLD after fault2; no multi-key transaction")
    }

    @Test fun `postpublish injected exception can report failure after a whole key was replaced`() = Fixture("postpublish-fault").use { f ->
        f.seed(); f.capture("before")
        safeError(assertFailsWith<DesktopSecretStoreException> { f.faultWriter(1, afterPublish = true).save(A, NEW_A) })
        assertTrue(f.reopened().load(A) == NEW_A && f.reopened().load(B) == OLD_B, "Unexpected committed key state")
        f.capture("failed-after-publish")
        f.outcome("SYNTHETIC_OBSERVED WHOLE_KEY_PUBLISHED_CALL_FAILED injected writer boundary; failure does not universally imply OLD; not native-fault proof")
    }

    @Test fun `real store wipes owned plaintext ciphertext and envelope byte buffers`() = Fixture("buffer-wipe").use { f ->
        var envelope: ByteArray? = null
        val store = WindowsSecretStore(f.secrets, f.protector, SecretBlobWriter { target, bytes -> envelope = bytes; AtomicSecretBlobWriter.write(target, bytes) })
        store.save(A, NEW_A)
        assertTrue(f.protector.plaintextBuffer!!.all { it == 0.toByte() })
        assertTrue(f.protector.ciphertextBuffer!!.all { it == 0.toByte() })
        assertTrue(envelope!!.all { it == 0.toByte() })
        assertTrue(f.reopened().load(A) == NEW_A, "Reopen failed")
        f.capture("saved"); f.outcome("SYNTHETIC_OBSERVED OWNED_BYTE_BUFFERS_ZEROED; immutable Strings and OS DPAPI heap NOT_PROVEN")
    }

    @Test fun `protection failure preserves existing blob and returns fixed safe category`() = Fixture("protect-fault").use { f ->
        f.seed(); val old = Files.readAllBytes(f.store.secretPath(A)); f.capture("before")
        val protector = object : SecretProtector {
            override fun protect(plaintext: ByteArray): ByteArray = throw DesktopSecretStoreException(DesktopSecretStoreFailureKind.PROTECTION_FAILURE, "SYNTHETIC_PROTECT_FAULT")
            override fun unprotect(ciphertext: ByteArray): ByteArray = error("UNUSED")
        }
        val error = assertFailsWith<DesktopSecretStoreException> { WindowsSecretStore(f.secrets, protector).save(A, NEW_A) }
        safeError(error); assertEquals(DesktopSecretStoreFailureKind.PROTECTION_FAILURE, error.kind)
        assertContentEquals(old, Files.readAllBytes(f.store.secretPath(A)))
        assertTrue(f.reopened().load(A) == OLD_A); f.capture("failed")
        f.outcome("SYNTHETIC_OBSERVED SINGLE_KEY_ATOMIC protection failure preserves OLD; fake protector not real DPAPI")
    }

    @Test fun `existing entity policy rolls back one key on entity callback failure`() = runBlocking<Unit> {
        Fixture("entity-rollback").use { f ->
            val key = DesktopCredentialKey.ModelApiKey("m"); f.store.save(key, OLD_A); f.capture("before")
            val dto = ModelConfig("m", "Synthetic", "https://example.invalid/v1", NEW_A, "synthetic", createdAt = 1)
            safeError(assertFailsWith<IOException> { DesktopModelCredentialPersistencePolicy(f.store).persist(dto) {
                assertTrue(it.apiKey.isEmpty()); throw IOException("SYNTHETIC_ENTITY_CALLBACK_FAULT")
            } })
            assertTrue(f.reopened().load(key) == OLD_A); assertTrue(Files.readString(f.root.resolve(DATA)) == OLD_DATA)
            f.capture("rolled-back"); f.outcome("SYNTHETIC_OBSERVED EXISTING_SINGLE_ENTITY_ROLLBACK; not whole-root or multi-key recovery")
        }
    }

    @Test fun `entity rollback write failure is explicit and retains the new key`() = runBlocking<Unit> {
        Fixture("entity-rollback-fault").use { f ->
            val key = DesktopCredentialKey.ModelApiKey("m"); f.store.save(key, OLD_A); f.capture("before")
            val dto = ModelConfig("m", "Synthetic", "https://example.invalid/v1", NEW_A, "synthetic", createdAt = 1)
            val error = assertFailsWith<DesktopCredentialConsistencyException> {
                DesktopModelCredentialPersistencePolicy(f.faultWriter(2)).persist(dto) { throw IOException("SYNTHETIC_ENTITY_CALLBACK_FAULT") }
            }
            safeError(error); assertEquals(1, error.suppressed.size)
            assertTrue(f.reopened().load(key) == NEW_A); assertTrue(Files.readString(f.root.resolve(DATA)) == OLD_DATA)
            f.capture("rollback-failed"); f.outcome("SYNTHETIC_OBSERVED EXPLICIT_ROLLBACK_FAILURE DATA_OLD_KEY_NEW; recovery requires adapter")
        }
    }

    @Test fun `official v1 plain and password inputs separate readable classification from credential refusal`() = runBlocking<Unit> {
        for (encrypted in listOf(false, true)) for (kind in WireKind.entries) Fixture("wire-${kind.name.lowercase()}-$encrypted").use { f ->
            f.seed(); val secretProof = blobProof(f.secrets)
            val source = Files.write(f.base.resolve("source.cbbackup"), wire(kind, encrypted))
            val sourceBytes = Files.readAllBytes(source); f.capture("before-inspection")
            val inspected = DesktopBackupReadOnlyInspector().inspect(source, if (encrypted) PASSWORD.toCharArray() else null)
            assertTrue(inspected.formatReadable); assertFalse(inspected.restoreSupported); assertFalse(inspected.fullPortabilityAccepted)
            assertEquals(kind.category?.let(::setOf) ?: emptySet(), inspected.credentials)
            assertTrue(FAKE_VALUES.none { inspected.toString().contains(it) })
            if (kind == WireKind.EMPTY) {
                DesktopFullBackupArchive(f.work).inspect(source, if (encrypted) PASSWORD.toCharArray() else null).use { prepared ->
                    assertTrue(prepared.records.isNotEmpty()); f.capture("prepared-no-key")
                }
            } else {
                val error = assertFailsWith<DesktopBackupCompatibilityException> {
                    DesktopFullBackupArchive(f.work).inspect(source, if (encrypted) PASSWORD.toCharArray() else null)
                }
                assertEquals(DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED, error.kind); safeError(error)
            }
            assertContentEquals(sourceBytes, Files.readAllBytes(source)); assertEquals(secretProof, blobProof(f.secrets))
            assertEquals(0L, Files.list(f.work).use { it.count() }); f.capture("after-inspection")
            Files.copy(source, f.out.resolve("${f.name}.fixture.cbbackup"), REPLACE_EXISTING)
            f.outcome(if (kind == WireKind.EMPTY) "SYNTHETIC_OBSERVED v1 credential-free Prepared closed; no restore scheduled"
                else "SYNTHETIC_OBSERVED CURRENT_PRODUCTION_REJECTION category=${kind.category} no Prepared source/secrets unchanged; approved direct-import goal NOT_IMPLEMENTED")
        }
    }

    @Test fun `wrong password cannot produce read-only result or Prepared or alter target keys`() = runBlocking<Unit> {
        Fixture("wrong-password").use { f ->
            f.seed(); val proof = blobProof(f.secrets)
            val source = Files.write(f.base.resolve("source.cbbackup"), wire(WireKind.EMPTY, true)); val before = Files.readAllBytes(source)
            f.capture("before")
            assertTrue(DesktopBackupReadOnlyInspector().inspect(source, PASSWORD.toCharArray()).formatReadable)
            val error = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspect(source, "FAKE_WRONG_PASSWORD".toCharArray()) }
            assertEquals("AUTHENTICATION", error.stage); safeError(error)
            safeError(assertFails { DesktopFullBackupArchive(f.work).inspect(source, "FAKE_WRONG_PASSWORD".toCharArray()) })
            assertEquals(proof, blobProof(f.secrets)); assertContentEquals(before, Files.readAllBytes(source))
            assertEquals(0L, Files.list(f.work).use { it.count() }); f.capture("rejected")
            Files.copy(source, f.out.resolve("wrong-password.fixture.cbbackup"), REPLACE_EXISTING)
            f.outcome("SYNTHETIC_OBSERVED AUTHENTICATION_REJECTED source and fake secrets unchanged")
        }
    }

    @Test fun `key first then failure before journal intent leaves old data and new key after recovery`() = runBlocking<Unit> {
        Fixture("key-first").use { f ->
            f.seed(); f.capture("before")
            safeError(assertFailsWith<IOException> { f.store.save(A, NEW_A); f.capture("key-published"); throw IOException("SYNTHETIC_BEFORE_INTENT_FAULT") })
            DesktopFullBackupCommitJournal(f.root).recover()
            assertTrue(Files.readString(f.root.resolve(DATA)) == OLD_DATA && f.reopened().load(A) == NEW_A)
            f.capture("reopened"); f.outcome("SYNTHETIC_OBSERVED OBSERVED_NONATOMIC_SEQUENCE DATA_OLD_KEY_NEW CROSS_DOMAIN_ATOMICITY_NOT_PROVEN")
        }
    }

    @Test fun `data commit followed by key failure cannot be undone by existing journal recovery`() = runBlocking<Unit> {
        Fixture("data-first").use { f ->
            f.seed(); f.capture("before"); f.schedule(); f.capture("scheduled")
            DesktopFullBackupCommitJournal(f.root).recover(); f.capture("data-committed")
            safeError(assertFailsWith<DesktopSecretStoreException> { f.faultWriter(1).save(A, NEW_A) })
            DesktopFullBackupCommitJournal(f.root).recover()
            assertTrue(Files.readString(f.root.resolve(DATA)) == NEW_DATA && f.reopened().load(A) == OLD_A)
            f.capture("reopened"); f.outcome("SYNTHETIC_OBSERVED OBSERVED_NONATOMIC_SEQUENCE DATA_NEW_KEY_OLD CROSS_DOMAIN_ATOMICITY_NOT_PROVEN")
        }
    }

    @Test fun `journal row exception rolls data back without rolling back a separate secret write`() = runBlocking<Unit> { journalFault(false) }

    @Test fun `journal row cancellation rolls data back without rolling back a separate secret write`() = runBlocking<Unit> { journalFault(true) }

    private suspend fun journalFault(cancel: Boolean) {
        Fixture("journal-${if (cancel) "cancel" else "exception"}").use { f ->
            f.seed(); f.capture("before"); f.schedule(); f.capture("scheduled")
            val fault = DesktopFullBackupCommitJournal(f.root) {
                f.store.save(A, NEW_A); f.capture("row-installed-key-published")
                if (cancel) throw CancellationException("SYNTHETIC_AFTER_ROW_CANCEL") else throw IOException("SYNTHETIC_AFTER_ROW_FAULT")
            }
            val error = assertFails { fault.recover() }; safeError(error)
            assertTrue(if (cancel) error is CancellationException else error is IOException)
            DesktopFullBackupCommitJournal(f.root).recover()
            assertTrue(Files.readString(f.root.resolve(DATA)) == OLD_DATA && f.reopened().load(A) == NEW_A)
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending()); f.capture("after-recovery")
            f.outcome("SYNTHETIC_OBSERVED OBSERVED_NONATOMIC_SEQUENCE registered data rollback only DATA_OLD_KEY_NEW; object reopen not OS/process restart")
        }
    }

    @Test fun `actual startup gate recovers registered data before business but has no secret transaction participant`() = runBlocking<Unit> {
        Fixture("startup").use { f ->
            f.seed(); f.schedule(); f.capture("scheduled")
            f.owner!!.close(); f.owner = null; f.capture("before-startup")
            var admitted = false
            DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(f.resolved()) }, presenter = { error("UNEXPECTED_STARTUP_REFUSAL") }).run {
                assertTrue(DesktopDataRootOwnership.isHeld(f.root))
                assertTrue(Files.readString(f.root.resolve(DATA)) == NEW_DATA && f.reopened().load(A) == OLD_A)
                f.capture("business-after-recovery"); admitted = true
            }
            assertTrue(admitted); assertFalse(DesktopDataRootOwnership.isHeld(f.root)); f.capture("startup-returned")
            f.outcome("SYNTHETIC_OBSERVED STARTUP_REGISTERED_ROWS_RECOVERED DATA_NEW_KEY_OLD; no input credential mapping or OS user switch")
        }
    }

    private enum class WireKind(val category: DesktopBackupReadOnlyInspector.CredentialCategory?) {
        EMPTY(null), NOVEL_AI(DesktopBackupReadOnlyInspector.CredentialCategory.NOVEL_AI),
        FISH(DesktopBackupReadOnlyInspector.CredentialCategory.FISH_AUDIO), COMMUNITY(DesktopBackupReadOnlyInspector.CredentialCategory.COMMUNITY),
        MODEL(DesktopBackupReadOnlyInspector.CredentialCategory.MODEL), LEGACY_EMBEDDING(DesktopBackupReadOnlyInspector.CredentialCategory.EMBEDDING),
        SINGLETON_EMBEDDING(DesktopBackupReadOnlyInspector.CredentialCategory.EMBEDDING), SETTINGS(DesktopBackupReadOnlyInspector.CredentialCategory.SILICON_FLOW)
    }

    private fun wire(kind: WireKind, encrypted: Boolean): ByteArray {
        // Minimal entity JSON goldens use required fields/defaults audited against official 1.4.5.
        val model = buildJsonObject {
            put("id", "source-model"); put("displayName", "Synthetic"); put("baseUrl", "https://source.invalid/v1")
            put("apiKey", if (kind == WireKind.MODEL) NEW_A else ""); put("modelName", "synthetic"); put("createdAt", 1)
        }
        val embedding = buildJsonObject {
            put("id", "source-embedding"); put("displayName", "Synthetic"); put("baseUrl", "https://embedding.invalid/v1")
            put("apiKey", if (kind in setOf(WireKind.LEGACY_EMBEDDING, WireKind.SINGLETON_EMBEDDING)) NEW_B else ""); put("modelName", "synthetic")
        }
        val files = linkedMapOf("entities/app_settings.json" to buildJsonObject { if (kind == WireKind.SETTINGS) put("siliconFlowApiKey", NEW_A) }.toString().toByteArray(),
            "entities/model_configs/source-model.json" to model.toString().toByteArray())
        files[if (kind == WireKind.SINGLETON_EMBEDDING) "entities/embedding_model_config.json" else "entities/embedding_configs/source-embedding.json"] = embedding.toString().toByteArray()
        val communitySession = buildJsonObject {
            put("accessToken", NEW_A); put("refreshToken", NEW_B); put("tokenType", "bearer")
            put("expiresAtMillis", 0); put("userId", "synthetic-user"); put("displayName", "Synthetic")
        }
        assertEquals(setOf("accessToken", "refreshToken", "tokenType", "expiresAtMillis", "userId", "displayName"), communitySession.keys)
        val preferences = officialWireJson.encodeToString(Official145Preferences(
            novelAiToken = if (kind == WireKind.NOVEL_AI) NEW_A else null,
            fishAudioKey = if (kind == WireKind.FISH) NEW_B else null,
            communitySession = if (kind == WireKind.COMMUNITY) communitySession.toString() else null)).toByteArray()
        val counts = files.keys.groupingBy { it.removePrefix("entities/").substringBefore('/').removeSuffix(".json") }.eachCount()
        val manifest = officialWireJson.encodeToString(Official145Manifest(appVersionCode = 84, appVersionName = "1.4.5",
            createdAt = 1, sourceFilesRoot = "/data/user/0/synthetic.ccb/files", counts = counts, fileCount = files.size,
            totalBytes = files.values.sumOf { it.size.toLong() }, preferencesSha256 = digest(preferences))).toByteArray()
        val obj = officialWireJson.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject
        assertFalse("scope" in obj); assertFalse("retainedPreferences" in officialWireJson.parseToJsonElement(preferences.toString(Charsets.UTF_8)).jsonObject)
        officialWireJson.decodeFromString<Official145Manifest>(manifest.toString(Charsets.UTF_8))
        val inventory = files.entries.joinToString("\n", postfix = "\n") { (name, bytes) -> officialWireJson.encodeToString(OfficialWireFileRecord(name, bytes.size.toLong(), digest(bytes))) }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(Official147FixtureCodec.encrypting(out, if (encrypted) PASSWORD.toCharArray() else null, 1)).use { zip ->
                for ((name, bytes) in files.mapKeys { "files/${it.key}" } + mapOf("preferences.json" to preferences, "inventory.jsonl" to inventory, "manifest.json" to manifest)) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    companion object {
        private val A = DesktopCredentialKey.NovelAiToken
        private val B = DesktopCredentialKey.FishAudioApiKey
        private const val OLD_A = "FAKE_P11_G1A_OLD_A_NOT_A_REAL_KEY"
        private const val OLD_B = "FAKE_P11_G1A_OLD_B_NOT_A_REAL_KEY"
        private const val NEW_A = "FAKE_P11_G1A_NEW_A_NOT_A_REAL_KEY"
        private const val NEW_B = "FAKE_P11_G1A_NEW_B_NOT_A_REAL_KEY"
        private const val PASSWORD = "FAKE_P11_G1A_ARCHIVE_PASSWORD"
        private val FAKE_VALUES = listOf(OLD_A, OLD_B, NEW_A, NEW_B, PASSWORD)
        private const val DATA = "entities/app_settings.json"
        private const val OLD_DATA = "{\"automaticImageGeneration\":false}"
        private const val NEW_DATA = "{\"automaticImageGeneration\":true}"
        private fun put(root: Path, relative: String, text: String): Path = root.resolve(relative).also {
            Files.createDirectories(it.parent); Files.writeString(it, text)
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun contains(bytes: ByteArray, needle: ByteArray): Boolean = bytes.indices.any { start ->
            start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] }
        }
        private fun blobProof(root: Path) = Files.list(root).use { paths -> paths.sorted().toList().associate { it.fileName.toString() to digest(Files.readAllBytes(it)) } }
        private fun safeError(error: Throwable) {
            for (item in generateSequence(error) { it.cause }.toList() + error.suppressed) {
                assertTrue(FAKE_VALUES.none { item.toString().contains(it) }, "Exception leaked a synthetic secret")
            }
        }
    }
}
