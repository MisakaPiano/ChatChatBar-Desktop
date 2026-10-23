package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.DesktopDocumentIndexReadEvidence
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopTargetIdentityEvidenceTest {
    private class Lab(label: String) {
        val base: Path = Files.createDirectories(Path.of("build/g1b-t1/$label-${UUID.randomUUID()}")).toAbsolutePath().normalize()
        val native = ExplicitSyntheticDirectoryWitnessReader(base)
        val a = Files.createDirectory(base.resolve("A"))
        val b = Files.createDirectory(base.resolve("B"))
        val store = Files.createDirectory(base.resolve("declared-store"))
        var generation = 1L
        var targets = listOf(model())
        suspend fun input(root: Path = a, credential: Path = store, reader: SyntheticTargetConfigurationReader? = null) =
            DeclaredTargetConsumer(root, credential, native.observe(root), native.observe(credential),
                reader ?: SyntheticTargetConfigurationReader { TargetConfigurationSnapshot(generation, targets) })
        suspend fun observe(vararg inputs: DeclaredTargetConsumer) = DesktopTargetIdentityEvidence(native).observe(inputs.toList())
    }

    private fun blocked(result: TargetIdentityEvidence) {
        assertFalse(result.readyToCommit); assertFalse(result.restoreAuthorized)
        assertEquals(TargetEvidenceState.UNKNOWN, result.globalConsumerClosure)
        assertEquals(TargetEvidenceReason.UNDECLARED_CONSUMERS_POSSIBLE, result.closureReason)
        result.consumers.forEach {
            assertEquals(TargetEvidenceState.UNKNOWN, it.protectionContext)
            assertEquals(TargetEvidenceState.UNKNOWN, it.accountBinding)
            assertTrue(it.targets.all { target -> target.relation == ExistingSecretRelation.UNKNOWN })
        }
    }

    @Test fun nativeDirectoriesAreObservedWithoutWindowsAccountOrClosureClaims() = runBlocking {
        val lab = Lab("native"); val result = lab.observe(lab.input(), lab.input(lab.b))
        assertEquals(2, result.consumers.size)
        result.consumers.forEach {
            assertEquals(TargetEvidenceState.VERIFIED, it.dataIdentity)
            assertEquals(TargetEvidenceState.VERIFIED, it.credentialIdentity)
            assertEquals(TargetEvidenceState.VERIFIED, it.configuration)
        }
        // Raw identity evidence is from explicit synthetic paths, never an account/protector ID.
        val reader = DesktopDocumentIndexReadEvidence({}, extendedPaths = true)
        val a = reader.observe(lab.a, true); val b = reader.observe(lab.b, true)
        assertNotEquals(a.id, b.id)
        Files.writeString(lab.base.resolve("native-observation.txt"), "A_VOLUME=${a.volume}\nA_ID=${a.id}\nB_VOLUME=${b.volume}\nB_ID=${b.id}\n")
        blocked(result)
    }

    @Test fun missingBaselineCannotBecomeVerified() = runBlocking {
        val l = Lab("no-baseline")
        val i = DeclaredTargetConsumer(l.a, l.store, null, null, SyntheticTargetConfigurationReader { TargetConfigurationSnapshot(1, l.targets) })
        val r = l.observe(i); assertEquals(TargetEvidenceState.UNKNOWN, r.consumers.single().dataIdentity)
        assertTrue(TargetEvidenceReason.BASELINE_MISSING in r.consumers.single().reasons); blocked(r)
    }

    @Test fun cloneWithIdenticalContentsAndUuidDoesNotInheritNativeIdentity() = runBlocking {
        val l = Lab("clone"); Files.writeString(l.a.resolve("root.id"), "SYNTHETIC_UUID_SAME")
        val original = l.input()
        val clone = Files.createDirectory(l.base.resolve("clone"))
        Files.copy(l.a.resolve("root.id"), clone.resolve("root.id"))
        val i = DeclaredTargetConsumer(clone, l.store, original.expectedData, original.expectedCredential, original.configurations)
        val r = l.observe(i); assertEquals(TargetEvidenceState.CONFLICT, r.consumers.single().dataIdentity)
        assertTrue(TargetEvidenceReason.DIRECTORY_IDENTITY_CHANGED in r.consumers.single().reasons); blocked(r)
    }

    @Test fun replacedDirectoryAndCredentialDirectoryAreRejected() = runBlocking {
        for (store in listOf(false, true)) {
            val l = Lab("replacement-$store"); val i = l.input(); val p = if (store) l.store else l.a
            Files.move(p, p.resolveSibling("retained-original")); Files.createDirectory(p)
            val r = l.observe(i).consumers.single()
            assertEquals(TargetEvidenceState.CONFLICT, if (store) r.credentialIdentity else r.dataIdentity)
            assertNotEquals(TargetEvidenceState.VERIFIED, r.configuration)
        }
    }

    @Test fun offlineDeclaredRootRemainsUnavailableNotAbsent() = runBlocking {
        val l = Lab("offline"); val a = l.input(); val b = l.input(l.b)
        Files.move(l.b, l.base.resolve("B-offline"))
        val r = l.observe(a, b)
        assertEquals(TargetEvidenceState.UNAVAILABLE, r.consumers[1].dataIdentity)
        assertEquals(2, r.consumers.size); assertTrue(r.consumers[1].targets.isEmpty()); blocked(r)
    }

    @Test fun undeclaredThirdConsumerIsNeverRuledOut() = runBlocking {
        val l = Lab("unknown-consumer"); val a = l.input(); val b = l.input(l.b)
        val two = l.observe(a, b)
        Files.createDirectory(l.base.resolve("unregistered-C"))
        val three = l.observe(a, b)
        assertEquals(two.toString(), three.toString()); blocked(two); blocked(three)
        assertEquals(2, three.consumers.size) // No directory enumeration is performed.
    }

    @Test fun sharedModelIdWithDifferentEndpointOrProviderReportsConflict() = runBlocking {
        for (target in listOf(model(endpoint = "https://other.invalid/v1"), model(template = ModelTemplate.CLAUDE), model(remote = "other"))) {
            val l = Lab("binding-conflict"); val a = l.input()
            val b = l.input(l.b, reader = SyntheticTargetConfigurationReader { TargetConfigurationSnapshot(1, listOf(target)) })
            val r = l.observe(a, b)
            assertTrue(r.consumers.all { it.configuration == TargetEvidenceState.CONFLICT })
            assertTrue(r.consumers.all { TargetEvidenceReason.ACCOUNT_OR_ENDPOINT_CONFLICT in it.reasons }); blocked(r)
        }
    }

    @Test fun separateStoreDirectoriesDoNotResolveLogicalAccountConflictOrSelectTopology() = runBlocking {
        val l = Lab("separate-store"); val second = Files.createDirectory(l.base.resolve("other-store"))
        val r = l.observe(l.input(), l.input(l.b, second, SyntheticTargetConfigurationReader {
            TargetConfigurationSnapshot(1, listOf(model(endpoint = "https://other.invalid/v1")))
        }))
        assertTrue(r.consumers.all { TargetEvidenceReason.ACCOUNT_OR_ENDPOINT_CONFLICT in it.reasons }); blocked(r)
    }

    @Test fun globalKeysHaveUnknownConsumersEvenWithTwoObservedRoots() = runBlocking {
        val l = Lab("globals")
        l.targets = listOf(ImportCredentialKind.NOVEL_AI, ImportCredentialKind.FISH, ImportCredentialKind.SILICON_FLOW).map {
            ImportCredentialTarget(it, ExistingSecretRelation.SAME)
        }
        val r = l.observe(l.input(), l.input(l.b))
        assertTrue(r.consumers.all { TargetEvidenceReason.GLOBAL_KEY_SCOPE_UNKNOWN in it.reasons }); blocked(r)
    }

    @Test fun unknownCommunityAndDuplicateConflictsAreNotDowngraded() = runBlocking {
        val l = Lab("duplicate-community")
        l.targets = listOf(model(), model(), ImportCredentialTarget(ImportCredentialKind.COMMUNITY, ExistingSecretRelation.ABSENT))
        val r = l.observe(l.input()); val row = r.consumers.single()
        assertEquals(TargetEvidenceState.CONFLICT, row.configuration)
        assertTrue(TargetEvidenceReason.DUPLICATE_KEY in row.reasons)
        assertTrue(TargetEvidenceReason.NO_PROTECTED_SINK in row.reasons); blocked(r)
    }

    @Test fun duplicateRootDeclarationsAreConflictingEvidence() = runBlocking {
        val l = Lab("duplicate-root"); val i = l.input()
        val r = l.observe(i, i)
        assertTrue(r.consumers.all { it.configuration == TargetEvidenceState.CONFLICT })
        assertTrue(r.consumers.all { TargetEvidenceReason.DUPLICATE_ROOT in it.reasons }); blocked(r)
    }

    @Test fun nameCollisionAndInvalidAccountDoNotAuthorizeReplacement() = runBlocking {
        val l = Lab("name-collision")
        val r = l.observe(l.input(), l.input(l.b, reader = SyntheticTargetConfigurationReader { TargetConfigurationSnapshot(1, listOf(model(id = "other-id"))) }))
        assertTrue(r.consumers.all { TargetEvidenceReason.NAME_COLLISION in it.reasons }); blocked(r)
        l.targets = listOf(model(endpoint = "file:///private"))
        val invalid = l.observe(l.input())
        assertEquals(TargetEvidenceState.CONFLICT, invalid.consumers.single().configuration); blocked(invalid)
    }

    @Test fun metadataGenerationAndConfigurationChangeDuringObservationIsConflict() = runBlocking {
        for (generation in listOf(false, true)) {
            val l = Lab("change-$generation"); var calls = 0
            val i = l.input(reader = SyntheticTargetConfigurationReader {
                calls++; TargetConfigurationSnapshot(if (generation) calls.toLong() else 1,
                    listOf(if (!generation && calls > 1) model(endpoint = "https://changed.invalid/v1") else model()))
            })
            val r = l.observe(i)
            assertEquals(TargetEvidenceState.CONFLICT, r.consumers.single().configuration)
            assertTrue(TargetEvidenceReason.OBSERVATION_CHANGED in r.consumers.single().reasons); blocked(r)
        }
    }

    @Test fun directoryMutationDuringMetadataObservationIsDetected() = runBlocking {
        val l = Lab("directory-change"); var calls = 0
        val i = l.input(reader = SyntheticTargetConfigurationReader {
            if (++calls == 1) { Files.move(l.a, l.base.resolve("A-retained")); Files.createDirectory(l.a) }
            TargetConfigurationSnapshot(1, listOf(model()))
        })
        val r = l.observe(i); assertEquals(TargetEvidenceState.CONFLICT, r.consumers.single().dataIdentity); blocked(r)
    }

    @Test fun fileIdentityReadFailureIsUnavailableAndRedacted() = runBlocking {
        val l = Lab("identity-fail"); val i = l.input()
        val fail = TargetDirectoryWitnessReader { throw IllegalStateException("PRIVATE_T1_IDENTITY_PATH") }
        val r = DesktopTargetIdentityEvidence(fail).observe(listOf(i))
        assertEquals(TargetEvidenceState.UNAVAILABLE, r.consumers.single().dataIdentity)
        assertFalse(r.toString().contains("PRIVATE")); blocked(r)
    }

    @Test fun storeAndMetadataAccessUnavailableNeverMeansAbsent() = runBlocking {
        val l = Lab("store-unavailable"); val i = l.input(reader = SyntheticTargetConfigurationReader {
            throw IllegalStateException("PRIVATE_T1_STORE_TOKEN")
        })
        Files.move(l.store, l.base.resolve("retained-store"))
        val r = l.observe(i); val row = r.consumers.single()
        assertEquals(TargetEvidenceState.UNAVAILABLE, row.credentialIdentity)
        assertEquals(TargetEvidenceState.UNAVAILABLE, row.configuration)
        assertTrue(row.targets.isEmpty()); assertFalse(row.toString().contains("PRIVATE")); blocked(r)
    }

    @Test fun cancellationStopsObservationWithoutReturningPartialEvidence() = runBlocking {
        val l = Lab("cancel"); var reads = 0
        val i = l.input(reader = SyntheticTargetConfigurationReader { reads++; throw CancellationException("PRIVATE_T1_CANCEL") })
        val error = assertFailsWith<CancellationException> { l.observe(i) }
        assertEquals(1, reads)
        assertFalse(error.toString().contains("PRIVATE"))
        Files.move(l.a, l.base.resolve("after-cancel")) // All native handles have been closed.
    }

    @Test fun outOfScopeRelativeMissingAndFilePathsAreRejectedWithoutCreation() = runBlocking {
        val l = Lab("unsafe")
        for (p in listOf(l.base.parent, l.base, Path.of("relative"), l.base.resolve("missing"), Files.writeString(l.base.resolve("ordinary-file"), "synthetic"))) {
            assertFails { l.native.observe(p) }
        }
        assertFalse(Files.exists(l.base.resolve("missing")))
    }

    @Test fun unknownKindNegativeGenerationAndInvalidInvocationRemainIncomplete() = runBlocking {
        val l = Lab("invalid-input")
        l.targets = listOf(ImportCredentialTarget(ImportCredentialKind.UNKNOWN, ExistingSecretRelation.ABSENT))
        val unknown = l.observe(l.input()); assertEquals(TargetEvidenceState.UNKNOWN, unknown.consumers.single().configuration); blocked(unknown)
        l.generation = -1; val negative = l.observe(l.input())
        assertTrue(TargetEvidenceReason.CONFIGURATION_INVALID in negative.consumers.single().reasons); blocked(negative)
        blocked(l.observe())
        val i = l.input(); val many = DesktopTargetIdentityEvidence(l.native).observe(List(65) { i })
        assertTrue(TargetEvidenceReason.INPUT_INVALID in many.consumers.single().reasons); blocked(many)
    }

    @Test fun observationIsReadOnlyAndDiagnosticsOmitPathsIdsAndMetadata() = runBlocking {
        val l = Lab("read-only"); Files.writeString(l.a.resolve("data"), "SYNTHETIC_PRIVATE_T1_BODY")
        val i = l.input(); val before = inventory(l.base)
        val result = l.observe(i)
        assertEquals(before, inventory(l.base))
        val printed = listOf(result, result.consumers.single(), i, i.expectedData, l.native,
            TargetConfigurationSnapshot(1, l.targets), l.targets.single(), l.targets.single().account).joinToString()
        for (privateText in listOf(l.base.toString(), "private-t1-model-id", "https://synthetic.invalid", "SYNTHETIC_PRIVATE_T1_BODY"))
            assertFalse(printed.contains(privateText))
        Files.writeString(l.base.resolve("before-after-inventory.txt"), before.entries.joinToString("\n") { "${it.key} ${it.value}" })
        blocked(result)
    }

    @Test fun verifiedG2BCensusAndT1MetadataStillCannotProduceUsableTargetPlan() = runBlocking {
        val l = Lab("g2b-integration"); val bytes = CensusWireFixtures.bytes(files = emptyMap())
        val sourcePath = Files.write(l.base.resolve("synthetic.cbbackup"), bytes)
        val r = l.observe(l.input())
        DesktopBackupReadOnlyInspector().inspectCredentialSources(sourcePath, null).use { source ->
            assertTrue(source.complete); assertFalse(source.restoreAuthorized)
            val plan = DesktopCredentialImportPlan.classify(source.envelopeVersion, source.manifestVersion, source.appVersionCode,
                source.sourceSha256, source.records.map { it.source }, r.consumers.flatMap { it.targets }, protectedSinkAvailable = false)
            assertFalse(plan.candidateConsistent); assertFalse(plan.restoreAuthorized)
        }
        assertContentEquals(bytes, Files.readAllBytes(sourcePath)); blocked(r)
    }

    companion object {
        private fun model(id: String = "private-t1-model-id", endpoint: String = "https://synthetic.invalid/v1",
            template: ModelTemplate = ModelTemplate.OPENAI, remote: String = "synthetic") =
            ImportCredentialTarget(ImportCredentialKind.MODEL, ExistingSecretRelation.SAME,
                ImportAccountIdentity(id, "Synthetic", endpoint, remote, template))
        private fun inventory(base: Path): Map<String, String> = Files.walk(base).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate {
            base.relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { b -> "%02x".format(b) }
        } }
    }
}
