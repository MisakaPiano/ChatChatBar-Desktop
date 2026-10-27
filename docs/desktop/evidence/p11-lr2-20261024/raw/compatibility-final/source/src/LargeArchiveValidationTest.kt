package com.example.chatbar.desktop.backup

import java.io.*
import java.nio.file.*
import java.util.zip.*
import org.junit.Test
import kotlin.test.*

class LargeArchiveValidationTest {
    private fun fixture(files: Map<String, ByteArray> = emptyMap(), encrypted: Boolean = false, version: Int = 1) =
        CensusWireFixtures.bytes(prefs = "{}", files = files, encrypted = encrypted, version = version, code = if (version == 1) 84 else 86)
    private fun read(bytes: ByteArray, password: String? = null) = LargeArchiveReadOnly.consume(bytes.inputStream(), password?.toCharArray())
    private fun mutate(bytes: ByteArray, action: (ByteArray) -> Unit): ByteArray {
        val zip = AppBackupCodec.decryptingForInspection(bytes.inputStream(), null) { _, _ -> }.use { it.readBytes() }
        action(zip)
        return ByteArrayOutputStream().also { out -> AppBackupCodec.encrypting(out, null).use { it.write(zip) } }.toByteArray()
    }
    private fun sig(b: ByteArray, type: Int) = b.indices.first { it + 4 <= b.size && b[it] == 0x50.toByte() &&
        b[it+1] == 0x4b.toByte() && b[it+2] == type.toByte() && b[it+3] == (if (type == 1) 2 else 6).toByte() }
    private fun rejects(bytes: ByteArray) { val e = assertFailsWith<LargeArchiveReadOnly.Refusal> { read(bytes) }
        assertNull(e.cause); assertFalse(e.toString().contains(CensusWireFixtures.SECRET)) }

    @Test fun `pinned independent official code84 plain and password and code86 are separate`() {
        for (version in 1..2) for (encrypted in listOf(false,true)) {
            val bytes = fixture(mapOf("entities/app_settings.json" to "{}".toByteArray()), encrypted, version)
            val r = read(bytes, if (encrypted) CensusWireFixtures.PASSWORD else null)
            assertEquals(version, r.version); assertEquals(if (version == 1) 84 else 86, r.code)
            assertEquals(1,r.typedRecords); assertEquals(0,r.unsupportedRecords); assertFalse(r.restoreSupported)
            val dir = Files.createDirectories(Path.of("build/p11-lr2/synthetic"))
            Files.write(dir.resolve("code${r.code}-${if(encrypted) "encrypted" else "plain"}.cbbackup"),bytes)
        }
    }
    @Test fun `known fake credentials never enter report`() {
        val r = read(CensusWireFixtures.bytes())
        assertTrue("CREDENTIAL_TRANSACTION_NOT_AUTHORIZED" in r.reasons)
        assertFalse(r.toString().contains(CensusWireFixtures.SECRET)); assertFalse(r.restoreSupported)
    }
    @Test fun `strict typed unknown or malformed entity prevents semantic claim`() {
        for (entry in listOf("entities/future/a.json" to "{}", "entities/model_configs/a.json" to "{\"id\":true}")) {
            val r = read(fixture(mapOf(entry.first to entry.second.toByteArray())))
            assertEquals(1, r.unsupportedRecords); assertEquals("SEMANTICALLY_BLOCKED",r.semanticState)
        }
    }
    @Test fun `missing portable resource and owner remain unresolved`() {
        val value = """{"id":"p","characterCardId":"c","sessionId":"s","senderName":"Synthetic","text":"Synthetic","imagePath":"chatbar-backup-file:images/absent.png","scheduledAt":1,"generatedAt":1}"""
        val r = read(fixture(mapOf("entities/moment_posts/p.json" to value.toByteArray())))
        assertEquals(1, r.missingResources); assertTrue(r.dependencyIssues >= 2)
        assertEquals("BLOCKED",r.moments)
    }
    @Test fun `duplicate JSON and illegal UTF8 rejected without parser snippets`() {
        rejects(fixture(mapOf("entities/app_settings.json" to "{\"x\":1,\"x\":2}".toByteArray())))
        rejects(fixture(mapOf("entities/app_settings.json" to byteArrayOf(0xc3.toByte(),0x28))))
    }
    @Test fun `manifest inventory and unsupported scope fail closed`() {
        rejects(CensusWireFixtures.bytes(badManifest=true)); rejects(CensusWireFixtures.bytes(badInventory=true))
        rejects(CensusWireFixtures.bytes(manifestExtra=",\"scope\":{}"))
        rejects(CensusWireFixtures.bytes(manifestExtra=",\"future\":1"))
    }
    @Test fun `bad password and final authentication fail without result`() {
        val b=fixture(encrypted=true)
        assertFailsWith<LargeArchiveReadOnly.Refusal> { read(b,"WRONG_SYNTHETIC_ONLY") }
        b[b.lastIndex]=(b.last().toInt() xor 1).toByte()
        assertFailsWith<LargeArchiveReadOnly.Refusal> { read(b,CensusWireFixtures.PASSWORD) }
    }
    @Test fun `central offset crc count and trailing bytes rejected`() {
        for (delta in listOf(16,20,24,42)) rejects(mutate(fixture()) { val p=sig(it,1)+delta; it[p]=(it[p].toInt() xor 1).toByte() })
        rejects(mutate(fixture()) { val p=sig(it,5)+10; it[p]=9 })
        val zip=AppBackupCodec.decryptingForInspection(fixture().inputStream(),null){_,_->}.use{it.readBytes()}
        rejects(ByteArrayOutputStream().also { out->AppBackupCodec.encrypting(out,null).use{it.write(zip);it.write(7)} }.toByteArray())
    }
    @Test fun `ZIP64 split disk and symlink central attributes rejected`() {
        rejects(mutate(fixture()){val p=sig(it,1);it[p+6]=45})
        rejects(mutate(fixture()){val p=sig(it,1);it[p+34]=1})
        rejects(mutate(fixture()){val p=sig(it,1);it[p+5]=3;it[p+41]=0xa0.toByte()})
    }
    @Test fun `duplicate names path traversal and unsupported flags rejected`() {
        rejects(fixture(linkedMapOf("images/A.png" to byteArrayOf(1),"images/a.png" to byteArrayOf(2))))
        rejects(fixture(mapOf("images/../escape" to byteArrayOf(1))))
        rejects(mutate(fixture()){it[6]=(it[6].toInt() or 1).toByte()})
    }
    @Test fun `expanded entry directory and compressed budgets enforced`() {
        val b=fixture(mapOf("images/synthetic.bin" to ByteArray(2000){it.toByte()}))
        for(limits in listOf(StrictStreamingZip.Limits(entry=100),StrictStreamingZip.Limits(expanded=100),
            StrictStreamingZip.Limits(compressed=100),StrictStreamingZip.Limits(directory=10),StrictStreamingZip.Limits(entries=1)))
            assertFailsWith<LargeArchiveReadOnly.Refusal>{LargeArchiveReadOnly.consume(b.inputStream(),null,limits)}
    }
    @Test fun `high expansion rejects and cancellation closes source`() {
        rejects(fixture(mapOf("images/bomb.bin" to ByteArray(4*1024*1024))))
        var closed=false
        val stream=object: ByteArrayInputStream(fixture()){override fun close(){closed=true;super.close()}}
        assertFailsWith<LargeArchiveReadOnly.Refusal>{LargeArchiveReadOnly.consume(stream,null,active={throw InterruptedIOException()})}
        assertTrue(closed)
    }
    @Test fun `nested package must be actual bounded valid v8 ZIP`() {
        rejects(fixture(mapOf("save_slot_packages/fake.cbsave" to "not a zip".toByteArray())))
        val nested=ByteArrayOutputStream().also { out-> ZipOutputStream(out).use{z->
            z.putNextEntry(ZipEntry("manifest.json"));z.write("{}".toByteArray());z.closeEntry()
        }}.toByteArray()
        rejects(fixture(mapOf("save_slot_packages/invalid.cbsave" to nested)))
    }
    @Test fun `official shared v8 nested counts valid and deliberately mismatched claims refuse`() {
        val dir=Files.createTempDirectory("lr2-nested-synthetic-")
        val file=com.example.chatbar.desktop.SyntheticSaveSlotCorpus.archive(dir.resolve("good.cbsave"))
        val r=read(fixture(mapOf("save_slot_packages/good.cbsave" to Files.readAllBytes(file))))
        assertEquals(1,r.nestedPackages)
        assertTrue("NESTED_SAVESLOT_DOMAIN_GRAPH_NOT_VERIFIED" in r.reasons)
        val bad=com.example.chatbar.desktop.SyntheticSaveSlotCorpus.archive(dir.resolve("bad.cbsave"),
            manifest=com.example.chatbar.desktop.SyntheticSaveSlotCorpus.slot().copy(
                packageRef=com.example.chatbar.data.local.entity.SaveSlotPackageRef("bad.cbsave",2)))
        rejects(fixture(mapOf("save_slot_packages/bad.cbsave" to Files.readAllBytes(bad))))
    }
    @Test fun `unknown preference retention names cannot be mistaken for known categories`() {
        rejects(CensusWireFixtures.bytes(prefs="""{"retainedPreferences":["future"]}""",files=emptyMap()))
        val r=read(CensusWireFixtures.bytes(prefs="""{"retainedPreferences":["novelai_credentials"]}""",files=emptyMap(),version=2,code=86))
        assertTrue("PREFERENCE_RETENTION_REQUIRES_TARGET" in r.reasons)
    }
    @Test fun `code84 must not acquire modern absent field defaults on wire`() {
        rejects(CensusWireFixtures.bytes(prefs="""{"retainedPreferences":[]}""",files=emptyMap()))
        rejects(CensusWireFixtures.bytes(prefs="{}",files=emptyMap(),manifestExtra=",\"scope\":null"))
    }
    @Test fun `file source bytes unchanged on success and refusal`() {
        val dir=Files.createTempDirectory("lr2-input-synthetic-")
        for(b in listOf(fixture(),fixture(mapOf("images/../escape" to byteArrayOf(1))))) {
            val path=dir.resolve("synthetic.cbbackup");Files.write(path,b)
            try { Files.newInputStream(path).use { LargeArchiveReadOnly.consume(it,null) } } catch(_:LargeArchiveReadOnly.Refusal) {}
            assertContentEquals(b,Files.readAllBytes(path))
        }
    }
    @Test fun `large media uses split budget without altering S0`() {
        val media=ByteArray(65*1024*1024);java.util.Random(84).nextBytes(media)
        val r=read(fixture(mapOf("images/synthetic.bin" to media)))
        assertEquals(1,r.files);assertEquals(64*1024*1024,DesktopBackupReadOnlyInspector.MAX_COMPRESSED)
    }
}
