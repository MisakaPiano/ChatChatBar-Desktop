package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.SyntheticSaveSlotCorpus as Corpus
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import java.nio.file.Files
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class Lr3VoiceSemanticsTest {
    private fun voice(version:String?="v", owner:String="m",session:String="s")=GeneratedVoiceMessage(
        "voice",session,owner,version,sourceOrder=1,sourceSegmentKind="DIALOGUE",sourceSpeakerName="Synthetic",
        sourceText="Synthetic",taggedText="Synthetic",characterId="c",characterName="Synthetic",
        voice=FishAudioVoiceBinding("ref","Synthetic"),fishModelId="synthetic",audioPath="chatbar-save-slot-audio:audio",
        durationMs=1,byteLength=3,createdAt=1,updatedAt=1)
    private fun checkCase(v:GeneratedVoiceMessage,messages:List<ChatMessage> = listOf(Corpus.message()), category:String?=null)=runBlocking<Unit> {
        val dir=Files.createTempDirectory("lr3-synthetic-")
        val file=Corpus.archive(dir.resolve("source.cbsave"),messages=messages,voices=listOf(v),media=mapOf("media/audio/audio.mp3" to byteArrayOf(1,2,3)))
        val before=Files.readAllBytes(file)
        val official=SaveSlotPackageStorage(dir.resolve("reader").toFile(),{_,_->error("NO_IMAGE")})
        // Executes the shared official Reader/import validation, not our diagnosis.
        official.importPackage(Files.newInputStream(file),"synthetic-target"){it}
        if(category==null) NestedSlotClaims.validate(Files.newInputStream(file)){}
        else {
            val e=assertFailsWith<NestedSlotClaims.Refusal>{NestedSlotClaims.validate(Files.newInputStream(file)) {}}
            assertTrue(e.phase.contains(category),e.phase);assertNull(e.cause)
            assertFalse(e.toString().contains("Synthetic"))
        }
        assertContentEquals(before,Files.readAllBytes(file))
    }
    @Test fun known()=checkCase(voice())
    @Test fun nullableLegacy()=checkCase(voice(null))
    @Test fun prunedHistoricAlternative()=checkCase(voice("v"),messages=listOf(com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy.append(Corpus.message(),"new","next",maxVersions=1)),category="NONNULL_VERSION_NOT_IN_OFFICIAL_VERSIONS")
    @Test fun missingOwner()=checkCase(voice(owner="missing"),category="OWNER_NOT_PRESENT_IN_SELECTED_PACKAGE")
    @Test fun selectedPackageMissingDependency()=checkCase(voice(),messages=emptyList(),category="OWNER_NOT_PRESENT_IN_SELECTED_PACKAGE")
    @Test fun explicitBlankLegacyStillBlocked()=checkCase(voice(""),category="LEGACY_NONNULL_BLANK_REPRESENTATION")
    @Test fun crossSession()=checkCase(voice(session="other"),category="CROSS_SESSION_VOICE")
    @Test fun duplicateMessageRefuses()=runBlocking<Unit> {
        val d=Files.createTempDirectory("lr3-duplicate-");val p=Corpus.archive(d.resolve("bad.cbsave"),messages=listOf(Corpus.message(),Corpus.message()))
        val refusal=assertFailsWith<StrictStreamingZip.Refusal>{NestedSlotClaims.validate(Files.newInputStream(p)) {}}
        assertTrue(refusal.phase.contains("DUPLICATE_OR_MALFORMED_IDENTITY"))
        val reader=SaveSlotPackageStorage(d.resolve("reader").toFile(),{_,_->error("NO_IMAGE")})
        assertFails{reader.importPackage(Files.newInputStream(p),"s"){it}}
    }
    @Test fun actualVoicesBeforeMessages()=runBlocking<Unit> {
        val d=Files.createTempDirectory("lr3-order-");val original=Corpus.archive(d.resolve("original.cbsave"),voices=listOf(voice()),media=mapOf("media/audio/audio.mp3" to byteArrayOf(1,2,3)))
        val reordered=d.resolve("reordered.cbsave")
        java.util.zip.ZipFile(original.toFile()).use { z -> java.util.zip.ZipOutputStream(Files.newOutputStream(reordered)).use { out ->
            z.entries().asSequence().toList().sortedBy{if(it.name=="voices.jsonl") 0 else 1}.forEach{e->out.putNextEntry(java.util.zip.ZipEntry(e.name));z.getInputStream(e).use{it.copyTo(out)};out.closeEntry()}
        } }
        NestedSlotClaims.validate(Files.newInputStream(reordered)){}
        SaveSlotPackageStorage(d.resolve("reader").toFile(),{_,_->error("NO_IMAGE")}).importPackage(Files.newInputStream(reordered),"s"){it}
    }
    @Test fun orderIndependentAndNoScopeGuess() {
        val owner=VoiceReferenceDiagnosis.Owner("s",setOf("v"))
        val voices=listOf(VoiceReferenceDiagnosis.Voice("b","s","v"),VoiceReferenceDiagnosis.Voice("a","s",null))
        assertEquals(VoiceReferenceDiagnosis.classify(linkedMapOf("a" to owner,"b" to owner),voices),VoiceReferenceDiagnosis.classify(linkedMapOf("b" to owner,"a" to owner),voices.reversed()))
        assertFalse(VoiceReferenceDiagnosis.classify(emptyMap(),voices).containsKey("SCOPE_OMITTED_DEPENDENCY"))
    }
}
