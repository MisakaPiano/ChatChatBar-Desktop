package com.example.chatbar.desktop.backup
import com.example.chatbar.desktop.SyntheticSaveSlotCorpus as C
import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import org.junit.Test
import kotlin.test.*

class Lr4ContinuationTest {
    private fun nested(owner:String="m",version:String="historic",media:Boolean=true):ByteArray {
        val v=GeneratedVoiceMessage("v","s",owner,version,sourceOrder=1,sourceSegmentKind="DIALOGUE",sourceSpeakerName="Synthetic",
            sourceText="Synthetic",taggedText="Synthetic",characterId="c",characterName="Synthetic",voice=FishAudioVoiceBinding("ref","Synthetic"),
            fishModelId="synthetic",audioPath="chatbar-save-slot-audio:a",durationMs=1,byteLength=3,createdAt=1,updatedAt=1)
        return Files.readAllBytes(C.archive(Files.createTempDirectory("lr4-synthetic-").resolve("synthetic.cbsave"),voices=listOf(v),
            media=if(media) mapOf("media/audio/a.mp3" to byteArrayOf(1,2,3)) else emptyMap()))
    }
    private fun outer(encrypted:Boolean=false,badInventory:Boolean=false,files:Map<String,ByteArray> = mapOf("save_slot_packages/a.cbsave" to nested()))=
        CensusWireFixtures.bytes(prefs="{}",files=files,encrypted=encrypted,badInventory=badInventory)
    private fun read(b:ByteArray,password:CharArray?=null)=LargeArchiveReadOnly.consume(b.inputStream(),password,preserveHistorical=true)
    @Test fun multipleHistoricPackagesReachEndButNeverAuthorizeRestore(){
        val r=read(outer(files=mapOf("save_slot_packages/a.cbsave" to nested(),"save_slot_packages/b.cbsave" to nested())))
        assertEquals(2,r.nestedPackages);assertFalse(r.restoreSupported);assertEquals("SEMANTICALLY_BLOCKED",r.semanticState)
    }
    @Test fun lateInventoryOverridesHistoricalObservation(){
        val e=assertFailsWith<LargeArchiveReadOnly.Refusal>{read(outer(badInventory=true))};assertEquals("MANIFEST_INVENTORY",e.stage)
    }
    @Test fun lateCentralCorruptionOverridesHistoricalObservation(){
        val raw=AppBackupCodec.decryptingForInspection(outer().inputStream(),null){_,_->}.use{it.readBytes()}
        val i=raw.indices.first{it+4<raw.size&&raw[it]==0x50.toByte()&&raw[it+1]==0x4b.toByte()&&raw[it+2]==1.toByte()&&raw[it+3]==2.toByte()}
        raw[i+42]=(raw[i+42].toInt() xor 1).toByte()
        val bytes=ByteArrayOutputStream().also{out->AppBackupCodec.encrypting(out,null).use{it.write(raw)}}.toByteArray()
        val e=assertFailsWith<LargeArchiveReadOnly.Refusal>{read(bytes)};assertTrue(e.stage.contains("CENTRAL"),e.stage)
    }
    @Test fun lateAuthenticationFailureNeverProducesReport(){
        val padding=ByteArray(2500000);java.util.Random(45).nextBytes(padding)
        val b=outer(true,files=linkedMapOf("save_slot_packages/a.cbsave" to nested(),"images/synthetic-padding.bin" to padding))
        b[b.lastIndex]=(b.last().toInt() xor 1).toByte()
        val out=Files.createDirectories(java.nio.file.Path.of("build/p11-lr4/synthetic"));Files.write(out.resolve("late-authentication-synthetic.cbbackup"),b)
        val e=assertFailsWith<LargeArchiveReadOnly.Refusal>{read(b,CensusWireFixtures.PASSWORD.toCharArray())}
        assertTrue(e.stage.contains("MEDIA_STREAM") && e.stage.endsWith("RECORDS_1"),e.stage)
    }
    @Test fun missingOwnerRemainsFatal(){assertFailsWith<LargeArchiveReadOnly.Refusal>{read(outer(files=mapOf("save_slot_packages/a.cbsave" to nested(owner="missing"))))}}
    @Test fun missingMediaRemainsFatal(){assertFailsWith<LargeArchiveReadOnly.Refusal>{read(outer(files=mapOf("save_slot_packages/a.cbsave" to nested(media=false))))}}
    @Test fun unknownDomainRemainsUninterpreted(){val r=read(outer(files=mapOf("entities/future/a.json" to "{}".toByteArray(),"save_slot_packages/a.cbsave" to nested())));assertEquals(1,r.unsupportedRecords);assertFalse(r.restoreSupported)}
    @Test fun cancellationAndLimitsRemainFatal(){val b=outer();assertFailsWith<LargeArchiveReadOnly.Refusal>{LargeArchiveReadOnly.consume(b.inputStream(),null,active={throw java.io.InterruptedIOException()})};assertFailsWith<LargeArchiveReadOnly.Refusal>{LargeArchiveReadOnly.consume(b.inputStream(),null,StrictStreamingZip.Limits(entries=1))}}
}
