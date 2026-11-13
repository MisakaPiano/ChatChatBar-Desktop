package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncGroupMembershipTest {
    private fun row(id: String) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, "合成归属", createdAt = 1, updatedAt = 1)).jsonObject)
    private fun withFile(work: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-membership-").toRealPath()
        try { work(Files.createFile(root.resolve("members.bin"))) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun membershipStreamsOnlySortedNativeIdsAndItsIdentityIgnoresHistoricalTextAndMedia() = withFile { path ->
        val records = listOf(row("b"), row("a")); val identity = LanSyncGroupMembership.write(path, records)
        val keys = mutableListOf<String>(); LanSyncGroupMembership.visit(path, identity, keys::add)
        assertEquals(listOf("character_cards/a", "character_cards/b"), keys)
        val rewritten = records.map { it.copy(body = JsonObject(it.body + ("name" to JsonPrimitive("另一份合成正文")))) }
        assertEquals(identity, LanSyncGroupMembership.identity(rewritten))
        assertFalse(Files.readString(path).contains("合成归属"))
    }

    @Test fun duplicateUnorderedTruncatedAndWrongIdentityListsRefuse() = withFile { path ->
        val header = "CCB-SYNC-NATIVE-MEMBERS/1\n"
        fun verify(body: String) {
            Files.writeString(path, body)
            val identity = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path)))
            assertFails { LanSyncGroupMembership.visit(path, identity) {} }
        }
        verify(header)
        verify(header + "\"character_cards/a\"\n\"character_cards/a\"\n")
        verify(header + "\"character_cards/b\"\n\"character_cards/a\"\n")
        verify(header + "\"character_cards/a\"")
        verify(header + "\"unknown_category/a\"\n")
        verify(header + "\"character_cards/" + "x".repeat(8193) + "\"\n")
        Files.write(path, (header + "\"").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"\n".toByteArray())
        val malformed = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path)))
        assertFails { LanSyncGroupMembership.visit(path, malformed) {} }
        val valid = LanSyncGroupMembership.write(Files.createFile(path.resolveSibling("valid.bin")), listOf(row("a")))
        assertFails { LanSyncGroupMembership.visit(path, valid) {} }
    }

    @Test fun nativeMembershipIsBoundByTheLogicalSignatureWithoutChangingLegacyVersionsThatHaveNoManifest() {
        val identity = LanSyncTlsIdentity.create(); val owner = SyncDeviceSignatures.decode(Base64.getEncoder().encodeToString(identity.certificate.encoded))
        val change = SyncUnitChange("a".repeat(64), SyncPlaintextIdentity(1, "b".repeat(64)), emptySet())
        val version = SyncCausalVersion(SyncStoreId.random(), SyncStoreId.random(), SyncVersionRef(SyncStoreId.random(), "c".repeat(64)),
            identity.fingerprint, 1, null, emptySet(), mapOf("character_cards/a" to change), "unsigned")
        fun sign(value: SyncCausalVersion) = value.copy(signature = Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(value.signingBytes())))
        val legacy = sign(version); assertTrue(owner.verifies(legacy.signingBytes(), legacy.signature))
        val withMembers = legacy.copy(changes = mapOf("character_cards/a" to change.copy(membership = SyncPlaintextIdentity(29, "d".repeat(64)))))
        assertFalse(owner.verifies(withMembers.signingBytes(), legacy.signature)); assertNotEquals(legacy.reference(), withMembers.reference())
        val signed = sign(withMembers); assertTrue(owner.verifies(signed.signingBytes(), signed.signature))
        val substituted = signed.copy(changes = signed.changes.mapValues { it.value.copy(membership = SyncPlaintextIdentity(29, "e".repeat(64))) })
        assertFalse(owner.verifies(substituted.signingBytes(), signed.signature))
    }
}
