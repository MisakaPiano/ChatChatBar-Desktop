package com.example.chatbar.desktop.backup
import java.nio.file.*
import org.junit.Test
import kotlin.test.*
class Lr4PrivateWitnessTest {
    @Test fun newSyntheticEvidenceDirectoryRetainsOnlyHashWitness(){val d=Files.createTempDirectory("lr4-private-synthetic-");PrivateSourceWitness.retain(d,"a".repeat(64),"a".repeat(64));val s=Files.readString(d.resolve("source-witness.private.txt"));assertTrue(s.contains("SOURCE_BYTES=UNCHANGED"));assertFalse(s.contains("password"))}
    @Test fun existingDirectoryContentsNeverOverwritten(){val d=Files.createTempDirectory("lr4-private-synthetic-");val p=Files.writeString(d.resolve("existing"),"synthetic");assertFails{PrivateSourceWitness.retain(d,"a".repeat(64),"a".repeat(64))};assertEquals("synthetic",Files.readString(p))}
    @Test fun malformedOrDifferentDigestCannotBecomeWitness(){val d=Files.createTempDirectory("lr4-private-synthetic-");assertFails{PrivateSourceWitness.retain(d,"PRIVATE_FAKE","PRIVATE_FAKE")};assertFails{PrivateSourceWitness.retain(d,"a".repeat(64),"b".repeat(64))};assertEquals(0L,Files.list(d).use{it.count()})}
}
