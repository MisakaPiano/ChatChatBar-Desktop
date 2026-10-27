package com.example.chatbar.desktop.backup
import com.example.chatbar.desktop.SyntheticSaveSlotCorpus
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*
class Lr2ResourceIdentityTest {
    @Test fun `official logical model id is not prematurely treated as Windows filename`() {
        val raw=CensusWireFixtures.bytes(prefs="{}",files=mapOf("entities/model_configs/synthetic:model.json" to
            CensusWireFixtures.model(id="synthetic:model",key="\"\"").toByteArray()))
        val r=LargeArchiveReadOnly.consume(raw.inputStream(),null)
        assertEquals(1,r.typedRecords);assertFalse(r.restoreSupported)
        assertTrue("WINDOWS_NAMES_REQUIRE_MATERIALIZATION_ADAPTER" in r.reasons)
    }
    @Test fun `official package resource id excludes extension and counts references not files`() {
        val dir=Files.createTempDirectory("lr2-resource-synthetic-")
        val message=SyntheticSaveSlotCorpus.message(images=listOf("chatbar-save-slot-image:one","chatbar-save-slot-image:one"))
        val source=SyntheticSaveSlotCorpus.archive(dir.resolve("synthetic.cbsave"),messages=listOf(message),
            media=mapOf("media/images/one.png" to byteArrayOf(1,2,3)))
        val outer=CensusWireFixtures.bytes(prefs="{}",files=mapOf("save_slot_packages/synthetic.cbsave" to Files.readAllBytes(source)))
        val r=LargeArchiveReadOnly.consume(outer.inputStream(),null)
        assertEquals(1,r.nestedPackages)
    }
}
