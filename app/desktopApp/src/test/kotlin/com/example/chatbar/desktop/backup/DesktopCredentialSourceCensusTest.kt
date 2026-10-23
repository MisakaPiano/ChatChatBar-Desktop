package com.example.chatbar.desktop.backup

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCredentialSourceCensusTest {
    private val inspector = DesktopBackupReadOnlyInspector()
    private val stamp = SyntheticObservationStamp(UUID(0, 1), UUID(0, 2), 4, true)
    private class Reader(val stampValue: SyntheticObservationStamp, val relation: ExistingSecretRelation = ExistingSecretRelation.SAME) : SyntheticCredentialTargetReader {
        var accesses = 0
        var reads = 0
        var secondStamp: SyntheticObservationStamp? = null
        var failRead = false
        var failStamp = false
        var cancel = false
        var held: ScopedCredentialComparison? = null
        var targetAccount: ImportAccountIdentity? = null
        override fun stamp(): SyntheticObservationStamp { accesses++; if (failStamp) error("PRIVATE_G2B_STAMP"); return if (accesses > 1) secondStamp ?: stampValue else stampValue }
        override fun observe(source: ImportCredentialSource, comparison: ScopedCredentialComparison): ImportCredentialTarget {
            assertFalse(source.kind == ImportCredentialKind.COMMUNITY, "Community must never enter API-key comparison")
            reads++; held = comparison
            if (cancel) throw CancellationException("SYNTHETIC_CANCEL")
            if (failRead) error("PRIVATE_G2B_TARGET_FAILURE")
            if (source.presence == ImportSecretPresence.NONEMPTY) {
                val bytes = CensusWireFixtures.SECRET.toCharArray()
                try { assertTrue(comparison.matches(bytes)) } finally { bytes.fill('\u0000') }
            }
            return ImportCredentialTarget(source.kind, relation, targetAccount ?: source.account)
        }
    }
    private suspend fun census(bytes: ByteArray = CensusWireFixtures.bytes(), label: String = "default", action: (VerifiedCredentialSourceMetadata) -> Unit) =
        CensusWireFixtures.input(bytes, label) { path -> inspector.inspectCredentialSources(path, null).use(action) }

    @Test fun `official v1 code84 plain and password have authenticated complete six-family sources`() = runBlocking {
        for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(encrypted = encrypted)
            CensusWireFixtures.input(bytes, "positive-$encrypted") { path ->
                val password = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                try { inspector.inspectCredentialSources(path, password).use { source ->
                    assertTrue(source.complete); assertFalse(source.restoreAuthorized)
                    assertEquals(CensusWireFixtures.digest(bytes), source.sourceSha256); assertEquals(bytes.size.toLong(), source.sourceLength)
                    assertEquals(7, source.records.size)
                    assertEquals(6, source.records.count { it.fieldState == CredentialFieldState.NONEMPTY })
                    assertEquals(84, source.appVersionCode); assertEquals(1, source.envelopeVersion)
                    val result = source.observeAndPlan(Reader(stamp), stamp)
                    assertEquals(TargetObservationStatus.OBSERVED, result.status)
                    assertTrue(requireNotNull(result.plan).candidateConsistent); assertFalse(result.plan.restoreAuthorized)
                } } finally { password?.fill('\u0000') }
            }
        }
    }
    @Test fun `four source field states retain evidence and explicit G2A presence mapping`() = runBlocking {
        for ((raw, expected) in listOf("{}" to CredentialFieldState.MISSING, "{\"novelAiToken\":null}" to CredentialFieldState.EXPLICIT_NULL,
            "{\"novelAiToken\":\"\"}" to CredentialFieldState.EMPTY, "{\"novelAiToken\":\"${CensusWireFixtures.SECRET}\"}" to CredentialFieldState.NONEMPTY)) {
            census(CensusWireFixtures.bytes(raw, emptyMap()), "state-${expected.name}") {
                val record = it.records.first(); assertEquals(expected, record.fieldState)
                assertEquals(raw.toByteArray().size.toLong(), record.fileLength)
                assertEquals(CensusWireFixtures.digest(raw.toByteArray()), record.fileSha256)
                assertEquals(when (expected) { CredentialFieldState.MISSING, CredentialFieldState.EXPLICIT_NULL -> ImportSecretPresence.ABSENT; CredentialFieldState.EMPTY -> ImportSecretPresence.EMPTY; else -> ImportSecretPresence.NONEMPTY }, record.source.presence)
            }
        }
    }
    @Test fun `model and embedding default fields use exact distinct scoped identities`() = runBlocking {
        census(label = "defaults") {
            val records = it.records.filter { record -> record.source.account != null }
            assertEquals(3, records.size)
            assertTrue(records.all { record -> record.source.account!!.valid(record.source.kind) })
            val result = it.observeAndPlan(Reader(stamp, ExistingSecretRelation.ABSENT), stamp)
            assertEquals(6, result.plan!!.decisions.mapNotNull { d -> d.candidate }.distinct().size)
        }
    }
    @Test fun `source record file SHA and length bind actual original model bytes`() = runBlocking {
        val files = CensusWireFixtures.files()
        census(CensusWireFixtures.bytes(files = files), "record-proof") {
            val record = it.records.single { r -> r.source.kind == ImportCredentialKind.MODEL }
            val bytes = files.getValue("entities/model_configs/m.json")
            assertEquals(bytes.size.toLong(), record.fileLength); assertEquals(CensusWireFixtures.digest(bytes), record.fileSha256)
        }
    }
    @Test fun `unknown entity and nested package never mean no credentials or access target`() = runBlocking {
        for ((name, files) in listOf("unknown" to mapOf("entities/future/x.json" to "{}".toByteArray()),
            "package" to mapOf("save_slot_packages/slot.bin" to byteArrayOf(1, 2)),
            "custom" to mapOf("entities/model_configs/m.json" to CensusWireFixtures.model(extra = ",\"customParams\":{\"private\":{\"type\":\"string\",\"value\":\"HIDDEN\"}}").toByteArray()))) {
            census(CensusWireFixtures.bytes(files = files), "incomplete-$name") {
                assertFalse(it.complete); val reader = Reader(stamp)
                assertEquals(TargetObservationStatus.SOURCE_INCOMPLETE, it.observeAndPlan(reader, stamp).status)
                assertEquals(0, reader.accesses); assertEquals(0, reader.reads)
            }
        }
    }
    @Test fun `duplicate model and singleton identities make source incomplete`() = runBlocking {
        val base = CensusWireFixtures.files()
        val variants = listOf(base + ("entities/model_configs/other.json" to CensusWireFixtures.model().toByteArray()),
            base + ("entities/embedding_model_config/other.json" to CensusWireFixtures.embedding().toByteArray()))
        for ((i, files) in variants.withIndex()) census(CensusWireFixtures.bytes(files = files), "duplicate-id-$i") {
            assertFalse(it.complete); assertTrue(CensusLimitation.DUPLICATE_ID in it.limitations)
        }
    }
    @Test fun `duplicate JSON fields and escaped equivalent keys are rejected by shared strict parser`() = runBlocking {
        for ((i, prefs) in listOf("{\"novelAiToken\":null,\"novelAiToken\":\"PRIVATE\"}", "{\"novelAiToken\":null,\"novelAi\\u0054oken\":\"PRIVATE\"}").withIndex())
            rejected(CensusWireFixtures.bytes(prefs), "duplicate-field-$i")
    }
    @Test fun `unknown preference field wrong field type and invalid model reject without publication`() = runBlocking {
        rejected(CensusWireFixtures.bytes("{\"unknownSecret\":\"PRIVATE\"}"), "unknown-pref")
        rejected(CensusWireFixtures.bytes("{\"novelAiToken\":3}"), "wrong-type")
        rejected(CensusWireFixtures.bytes(files = mapOf("entities/model_configs/m.json" to "{}".toByteArray())), "missing-model")
    }
    @Test fun `malformed or future community session is incomplete and no target is consulted`() = runBlocking {
        for ((i, session) in listOf("PRIVATE_BROKEN", "{\\\"future\\\":true}").withIndex()) {
            census(CensusWireFixtures.bytes("{\"communitySession\":\"$session\"}", emptyMap()), "bad-community-$i") { assertFalse(it.complete) }
        }
    }
    @Test fun `valid official community defaults retain unmapped status`() = runBlocking {
        val prefs = """{"communitySession":"{\"accessToken\":\"${CensusWireFixtures.SECRET}\",\"userId\":\"synthetic\",\"displayName\":\"Synthetic\"}"}"""
        census(CensusWireFixtures.bytes(prefs, emptyMap()), "community") {
            assertTrue(it.complete)
            val plan = it.observeAndPlan(Reader(stamp), stamp).plan!!
            assertFalse(plan.candidateConsistent); assertEquals(CredentialPlanReason.NO_PROTECTED_SINK, plan.decisions.last().reason)
        }
    }
    @Test fun `invalid endpoint preserves redacted incomplete source identity`() = runBlocking {
        val files = CensusWireFixtures.files().toMutableMap()
        files["entities/model_configs/m.json"] = CensusWireFixtures.model().replace("https://synthetic.invalid/v1", "https://user:PRIVATE@synthetic.invalid").toByteArray()
        census(CensusWireFixtures.bytes(files = files), "bad-endpoint") { assertFalse(it.complete); assertFalse(it.toString().contains("PRIVATE")) }
    }
    @Test fun `same different absent unknown observations remain explicit and nonrestorable`() = runBlocking {
        for (relation in ExistingSecretRelation.entries) census(label = "relation-$relation") {
            val result = it.observeAndPlan(Reader(stamp, relation), stamp)
            val plan = result.plan!!; assertFalse(plan.restoreAuthorized)
            assertEquals(relation in setOf(ExistingSecretRelation.ABSENT, ExistingSecretRelation.SAME), plan.candidateConsistent)
            if (relation == ExistingSecretRelation.UNKNOWN) assertTrue(plan.decisions.filter { d -> d.kind != ImportCredentialKind.COMMUNITY }.all { d -> d.reason == CredentialPlanReason.TARGET_STATE_UNKNOWN })
        }
    }
    @Test fun `target read failure is unknown not absent and private cause is discarded`() = runBlocking {
        census(label = "read-fault") {
            val reader = Reader(stamp).also { r -> r.failRead = true }
            val result = it.observeAndPlan(reader, stamp)
            assertFalse(result.plan!!.candidateConsistent); assertFalse(result.toString().contains("PRIVATE"))
            assertTrue(result.plan.decisions.filter { d -> d.kind != ImportCredentialKind.COMMUNITY }.all { d -> d.reason == CredentialPlanReason.TARGET_STATE_UNKNOWN })
        }
    }
    @Test fun `wrong root context unknown consumers and failed stamp cannot create a plan`() = runBlocking {
        for ((i, observed) in listOf(stamp.copy(root = UUID(0, 3)), stamp.copy(context = UUID(0, 3)), stamp.copy(consumersKnown = false)).withIndex())
            census(label = "context-$i") {
                val reader = Reader(observed); assertNull(it.observeAndPlan(reader, stamp).plan); assertEquals(0, reader.reads)
            }
        census(label = "stamp-fault") { assertNull(it.observeAndPlan(Reader(stamp).also { r -> r.failStamp = true }, stamp).plan) }
    }
    @Test fun `generation or context changes during observation invalidate the whole plan`() = runBlocking {
        for ((i, changed) in listOf(stamp.copy(generation = 5), stamp.copy(context = UUID(0, 9))).withIndex()) census(label = "changed-$i") {
            val result = it.observeAndPlan(Reader(stamp).also { r -> r.secondStamp = changed }, stamp)
            assertEquals(TargetObservationStatus.GENERATION_OR_CONTEXT_CHANGED, result.status); assertNull(result.plan)
        }
    }
    @Test fun `model endpoint and name conflicts flow to G2A without automatic binding`() = runBlocking {
        census(label = "endpoint-conflict") {
            val reader = Reader(stamp).also { r -> r.targetAccount = ImportAccountIdentity("m", "Synthetic", "https://other.invalid/v1", "synthetic", com.example.chatbar.data.local.entity.ModelTemplate.OPENAI) }
            assertFalse(it.observeAndPlan(reader, stamp).plan!!.candidateConsistent)
        }
    }
    @Test fun `closed source and escaped comparator cannot be reused`() = runBlocking {
        census(label = "lifetime") {
            val reader = Reader(stamp); val result = it.observeAndPlan(reader, stamp)
            assertNotNull(result.plan); assertFailsWith<IllegalStateException> { reader.held!!.matches(charArrayOf()) }
            assertEquals(TargetObservationStatus.SOURCE_CLOSED, it.observeAndPlan(reader, stamp).status)
        }
    }
    @Test fun `observer cancellation closes copied buffers and comparison scope`() = runBlocking {
        census(label = "observer-cancel") {
            val reader = Reader(stamp).also { r -> r.cancel = true }
            assertFailsWith<CancellationException> { it.observeAndPlan(reader, stamp) }
            assertFailsWith<IllegalStateException> { reader.held!!.matches(charArrayOf()) }
            assertEquals(TargetObservationStatus.SOURCE_CLOSED, it.observeAndPlan(reader, stamp).status)
        }
    }
    @Test fun `authentication success followed by bad inventory or manifest never publishes or accesses observer`() = runBlocking {
        for ((i, bytes) in listOf(CensusWireFixtures.bytes(badInventory = true), CensusWireFixtures.bytes(badManifest = true)).withIndex()) {
            var authenticated = false; val reader = Reader(stamp)
            CensusWireFixtures.input(bytes, "late-failure-$i") { path ->
                assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> {
                    DesktopBackupReadOnlyInspector { authenticated = true }.inspectCredentialSources(path, null).use { it.observeAndPlan(reader, stamp) }
                }
            }
            assertTrue(authenticated); assertEquals(0, reader.accesses)
        }
    }
    @Test fun `wrong password tag and truncated source fail authentication without values in errors`() = runBlocking {
        val bytes = CensusWireFixtures.bytes(encrypted = true)
        CensusWireFixtures.input(bytes, "wrong-password") { path ->
            val failure = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { inspector.inspectCredentialSources(path, "FAKE_G2B_WRONG_PASSWORD".toCharArray()) }
            assertEquals("AUTHENTICATION", failure.stage); assertNull(failure.cause)
        }
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for ((i, input) in listOf(tampered, bytes.copyOf(bytes.size - 12)).withIndex()) CensusWireFixtures.input(input, "auth-tamper-$i") { path ->
            assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { inspector.inspectCredentialSources(path, CensusWireFixtures.PASSWORD.toCharArray()) }
        }
    }
    @Test fun `invalid UTF8 duplicate zip central CRC and path violation fail closed`() = runBlocking {
        rejected(CensusWireFixtures.bytes(files = mapOf("entities/app_settings.json" to byteArrayOf(0xc3.toByte(), 0x28))), "utf8")
        rejected(CensusWireFixtures.bytes(files = mapOf("images/../bad" to byteArrayOf(1))), "path")
        val crc = CensusWireFixtures.bytes()
        val central = (0..crc.size - 4).first { crc[it] == 0x50.toByte() && crc[it+1] == 0x4b.toByte() && crc[it+2] == 1.toByte() && crc[it+3] == 2.toByte() }
        crc[central + 16] = (crc[central + 16].toInt() xor 1).toByte(); rejected(crc, "crc")
        val duplicates = CensusWireFixtures.bytes(files = mapOf("images/a" to byteArrayOf(1), "images/b" to byteArrayOf(2)))
        val needle = "files/images/b".toByteArray()
        for (i in 0..duplicates.size - needle.size) if (needle.indices.all { duplicates[i+it] == needle[it] }) duplicates[i+needle.lastIndex] = 'a'.code.toByte()
        rejected(duplicates, "duplicate-zip")
    }
    @Test fun `v2 and later source codes remain readable but census unsupported`() = runBlocking {
        for ((i, bytes) in listOf(CensusWireFixtures.bytes("{}", emptyMap(), version = 2, code = 85), CensusWireFixtures.bytes("{}", emptyMap(), code = 85)).withIndex()) census(bytes, "unsupported-$i") {
            assertFalse(it.complete); assertTrue(CensusLimitation.SOURCE_VERSION in it.limitations)
        }
    }
    @Test fun `scan cancellation after authentication never publishes and handles release`() = runBlocking {
        val bytes = CensusWireFixtures.bytes()
        CensusWireFixtures.input(bytes, "scan-cancel") { path ->
            val job = Job()
            assertFailsWith<CancellationException> { withContext(job) { DesktopBackupReadOnlyInspector { job.cancel() }.inspectCredentialSources(path, null) } }
            inspector.inspectCredentialSources(path, null).close()
        }
    }
    @Test fun `metadata diagnostics do not contain secret value raw JSON private account or path`() = runBlocking {
        census(label = "redaction") {
            val output = listOf(it.toString(), it.records.toString(), it.observeAndPlan(Reader(stamp), stamp).toString()).joinToString()
            assertFalse(output.contains(CensusWireFixtures.SECRET)); assertFalse(output.contains("https://synthetic")); assertFalse(output.contains("apiKey"))
        }
    }
    @Test fun `owned copied character buffers are zeroed on success close and cancellation`() = runBlocking {
        for (mode in 0..2) census(label = "buffer-wipe-$mode") {
            val field = VerifiedCredentialSourceMetadata::class.java.getDeclaredField("secrets").also { f -> f.isAccessible = true }
            @Suppress("UNCHECKED_CAST") val copies = field.get(it) as List<CharArray?>
            assertTrue(copies.any { value -> value?.any { c -> c != '\u0000' } == true })
            when (mode) {
                0 -> it.observeAndPlan(Reader(stamp), stamp)
                1 -> it.close()
                else -> assertFailsWith<CancellationException> { it.observeAndPlan(Reader(stamp).also { r -> r.cancel = true }, stamp) }
            }
            assertTrue(copies.all { value -> value == null || value.all { c -> c == '\u0000' } })
        }
    }
    @Test fun `legacy transport cannot claim official145 completeness with modern nullable fields`() = runBlocking {
        census(CensusWireFixtures.bytes(manifestExtra = ",\"scope\":null"), "modern-manifest") { assertFalse(it.complete) }
        census(CensusWireFixtures.bytes("{\"retainedPreferences\":[]}"), "modern-prefs") { assertFalse(it.complete) }
    }
    @Test fun `full pinned model fields and known nested appearance settings stay interpreted`() = runBlocking {
        val extra = """, "selectableForChat":true,"isMultimodal":false,"visionModelId":null,"templateType":"OPENAI","customParams":{},"reasoningEffort":null,"enableThinking":null,"maxOutputTokens":null,"outputTokenParameter":"MAX_TOKENS","formatPromptPosition":"BOTH","supportsJsonMode":false,"supportsDisableThinking":false,"sourcePresetKey":null,"sourcePresetVersion":null"""
        val settings = """{"defaultModelId":null,"defaultImageModelId":null,"modelConfigurationMode":"CUSTOM_API","presetDefaultModelKey":null,"siliconFlowApiKey":"${CensusWireFixtures.SECRET}","allowCleartextModelApi":false,"defaultEmbeddingId":null,"defaultFormatCardId":null,"automaticFormatCheckEnabled":false,"formatRepairModelId":null,"memoryRagTopK":3,"memoryRagSimilarityThreshold":0.35,"docRagTopK":3,"docRagSimilarityThreshold":0.55,"ragInjectionMode":"STANDARD","defaultContextWindowSize":20,"episodeMaxSourceTurns":2,"excludeAssistantStatusFromHistory":true,"themeMode":"SYSTEM","themeColor":{"hueDegrees":168.0,"saturation":0.5,"value":0.5},"themeColorHistory":[{"hueDegrees":168.0,"saturation":0.5,"value":0.5}],"chatBubbleFontScale":1.0,"chatBackgroundImageOpacity":0.16,"assistantSegmentedBubblesEnabled":true,"tutorialVersion":0,"webSearchSettingsVersion":0,"webSearchEnabled":true,"characterAutoFillWebSearchEnabled":true,"characterRewriteWebSearchEnabled":true,"characterAutoFillResearchSourceMode":"ENCYCLOPEDIA_SEARCH","characterRewriteResearchSourceMode":"ENCYCLOPEDIA_SEARCH","worldBookAiResearchSourceMode":"ENCYCLOPEDIA_SEARCH","webSearchMaxResultsPerQuery":1,"novelAiImageModel":"V4_5_FULL","novelAiImageAspectRatio":"","novelAiPromptTranslationConsent":"DISABLED","imagePromptToolPreference":"","fishAudioTtsModelId":"s2.1-pro-free","voiceTagModelId":null,"audiobookModeEnabled":false,"momentsEnabled":false,"momentsImagesEnabled":true,"momentsMinDelayHours":2,"momentsMaxDelayHours":13,"momentsBackgroundGuideDismissed":false,"momentsAutoStartConfirmed":false,"lastSeenMomentsAt":0,"lastSeenChatAt":0}"""
        census(CensusWireFixtures.bytes(files = mapOf("entities/model_configs/m.json" to CensusWireFixtures.model(extra = extra).toByteArray(), "entities/app_settings.json" to settings.toByteArray())), "full-pinned-fields") {
            assertTrue(it.complete); assertTrue(it.observeAndPlan(Reader(stamp), stamp).plan!!.candidateConsistent)
        }
    }
    @Test fun `R1 community expiry absent zero positive and both Long bounds are valid numeric wire values`() = runBlocking {
        for ((i, expiry) in listOf<String?>(null, "0", "123", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString()).withIndex()) {
            communityExpiry(expiry, "r1-valid-$i") { metadata ->
                assertTrue(metadata.complete)
                val reader = Reader(stamp)
                val result = metadata.observeAndPlan(reader, stamp)
                assertEquals(TargetObservationStatus.OBSERVED, result.status)
                assertEquals(CredentialPlanReason.NO_PROTECTED_SINK, result.plan!!.decisions.last().reason)
                assertFalse(result.plan.restoreAuthorized)
                assertFalse(result.toString().contains(CensusWireFixtures.SECRET))
            }
        }
    }
    @Test fun `R1 community expiry strings null booleans floats containers and overflow are incomplete before observation`() = runBlocking {
        val invalid = listOf("\"123\"", "null", "true", "false", "1.5", "1.0", "1e2", "[]", "{}", "9223372036854775808", "-9223372036854775809")
        for ((i, expiry) in invalid.withIndex()) communityExpiry(expiry, "r1-invalid-$i") { metadata ->
            val reader = Reader(stamp)
            val result = metadata.observeAndPlan(reader, stamp)
            assertFalse(metadata.complete)
            assertTrue(CensusLimitation.NESTED_CONTENT in metadata.limitations)
            assertEquals(TargetObservationStatus.SOURCE_INCOMPLETE, result.status)
            assertNull(result.plan); assertEquals(0, reader.accesses); assertEquals(0, reader.reads)
            assertFalse((metadata.toString() + metadata.records + result).contains(CensusWireFixtures.SECRET))
        }
    }
    @Test fun `R1 future community field stays incomplete without observer or token disclosure`() = runBlocking {
        communityExpiry("0", "r1-future", ",\"futureSecret\":\"${CensusWireFixtures.SECRET}\"") { metadata ->
            val reader = Reader(stamp)
            assertFalse(metadata.complete); assertNull(metadata.observeAndPlan(reader, stamp).plan)
            assertEquals(0, reader.accesses); assertEquals(0, reader.reads)
            assertFalse(metadata.toString().contains(CensusWireFixtures.SECRET))
        }
    }
    private suspend fun communityExpiry(expiry: String?, label: String, extra: String = "", action: (VerifiedCredentialSourceMetadata) -> Unit) {
        // Independent raw official CommunitySession wire; transport uses existing independent145 fixture model.
        val session = "{\"accessToken\":\"${CensusWireFixtures.SECRET}\",\"userId\":\"synthetic\",\"displayName\":\"Synthetic\"" +
            (expiry?.let { ",\"expiresAtMillis\":$it" } ?: "") + extra + "}"
        val prefs = "{\"communitySession\":" + kotlinx.serialization.json.JsonPrimitive(session).toString() + "}"
        census(CensusWireFixtures.bytes(prefs, emptyMap()), label, action)
    }
    private suspend fun rejected(bytes: ByteArray, label: String) = CensusWireFixtures.input(bytes, label) { path ->
        val error = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { inspector.inspectCredentialSources(path, null) }
        assertNull(error.cause); assertFalse(error.toString().contains("PRIVATE")); assertFalse(error.toString().contains(CensusWireFixtures.SECRET))
    }
}
