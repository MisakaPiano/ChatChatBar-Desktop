package com.example.chatbar.desktop.backup.preparation
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.backup.backupJson
import java.nio.file.*
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.*

class DormantPreservationPreparationTest {
    private val post="""{ "id":"p", "characterCardId":"c","sessionId":"s","senderName":"SYNTHETIC","text":"SYNTHETIC","senderAvatar":"chatbar-backup-file:images/avatar.png","imagePath":"chatbar-backup-file:images/body.png","generatedImageMetadata":{"imagePath":"chatbar-backup-file:images/meta.png","baseCaption":"synthetic","negativePrompt":"synthetic","sizePreset":"synthetic","width":1,"height":1},"scheduledAt":1,"generatedAt":1 }"""
    private val task="""{"id":"t","characterCardId":"c","sessionId":"s","scheduledAt":1,"postId":"p","status":"RUNNING"}"""
    private fun voice()=GeneratedVoiceMessage("v","s","m","historic",sourceOrder=1,sourceSegmentKind="DIALOGUE",sourceSpeakerName="Synthetic",sourceText="Synthetic",taggedText="Synthetic",characterId="c",characterName="Synthetic",voice=FishAudioVoiceBinding("ref","Synthetic"),fishModelId="synthetic",audioPath="chatbar-save-slot-audio:a",durationMs=1,byteLength=3,createdAt=1,updatedAt=1)
    private val context=DormantContext(setOf("c"),setOf("s"),emptySet(),mapOf(("s" to "m") to setOf("active")),sourceEvidence=PreparationEvidence.SYNTHETIC_VERIFIED)
    private fun inputs()=listOf(DormantInput(DormantKind.MOMENT_POST,post.toByteArray()),DormantInput(DormantKind.MOMENT_TASK,task.toByteArray()),DormantInput(DormantKind.HISTORIC_VOICE,backupJson.encodeToString(GeneratedVoiceMessage.serializer(),voice()).toByteArray()))
    private fun media()=linkedMapOf("chatbar-backup-file:images/avatar.png" to byteArrayOf(1),"chatbar-backup-file:images/body.png" to byteArrayOf(2),"chatbar-backup-file:images/meta.png" to byteArrayOf(3),"chatbar-save-slot-audio:a" to byteArrayOf(4,5,6))
    private fun prepare(i:List<DormantInput> = inputs(),m:Map<String,ByteArray> = media(),cap:Long=100000,active:()->Unit={})=DormantPreservationPreparation.prepare(i,m,context,cap,10,active)
    @Test fun exactRawHistoricIdentityAndThreeEdgesRemainInert(){
        val p=prepare();assertEquals(3,p.rawCopies().size);assertEquals(4,p.mediaCopies().size);assertContentEquals(post.toByteArray(),p.rawCopies()[0]);assertContentEquals(task.toByteArray(),p.rawCopies()[1]);assertEquals("historic",backupJson.decodeFromString(GeneratedVoiceMessage.serializer(),p.rawCopies()[2].decodeToString()).messageVersionId);assertFalse(p.runnable);assertFalse(p.published);assertFalse(p.productionParticipationVerified)
        val out=Files.createDirectories(Path.of("build/p11-lr4/synthetic"));(p.rawCopies()+p.mediaCopies().values).forEachIndexed{i,b->Files.write(out.resolve("dormant-synthetic-$i.bin"),b)}
    }
    @Test fun ownedPhysicalCopiesSurviveSyntheticSourceDeletion(){
        val p=prepare();val root=Files.createTempDirectory("lr4-owned-synthetic-");val source=Files.createDirectory(root.resolve("source"));val target=Files.createDirectory(root.resolve("inactive"))
        (p.rawCopies()+p.mediaCopies().values).forEachIndexed{i,b->val a=source.resolve("$i");val c=target.resolve("$i");Files.write(a,b,StandardOpenOption.CREATE_NEW);Files.copy(a,c);assertFalse(Files.isSameFile(a,c));Files.delete(a);assertContentEquals(b,Files.readAllBytes(c))};assertFalse(Files.exists(target.resolve("PUBLISHED")))
    }
    @Test fun callerAndReturnedBufferMutationCannotRewriteCandidate(){val m=media();val p=prepare(m=m);m.values.first()[0]=99;val out=p.mediaCopies();out.values.first()[0]=88;assertEquals(1,p.mediaCopies().values.first()[0].toInt());p.rawCopies()[0][0]=0;assertContentEquals(post.toByteArray(),p.rawCopies()[0])}
    @Test fun duplicateUnknownAndMalformedRawRefuse(){assertFails{prepare(inputs()+inputs().first())};assertFails{prepare(listOf(DormantInput(DormantKind.UNKNOWN,"{}".toByteArray())))};assertFails{prepare(listOf(DormantInput(DormantKind.MOMENT_POST,post.replace("\"id\":\"p\"","\"id\":\"p\",\"id\":\"q\"").toByteArray())))};assertFails{prepare(listOf(DormantInput(DormantKind.MOMENT_POST,post.dropLast(1).plus(",\"future\":1}").toByteArray())))}}
    @Test fun missingRelationAndExternalPathRefuse(){assertFails{prepare(inputs().dropLast(1),media()-"chatbar-backup-file:images/meta.png")};assertFails{prepare(listOf(inputs()[0],DormantInput(DormantKind.MOMENT_TASK,task.replace("\"p\"","\"missing\"").toByteArray()),inputs()[2]))};assertFails{prepare(listOf(DormantInput(DormantKind.MOMENT_POST,post.replace("chatbar-backup-file:images/body.png","C:/external.png").toByteArray())))}}
    @Test fun missingVoiceOwnerOrActiveVersionCannotMasqueradeAsDormant(){for(v in listOf(voice().copy(messageId="missing"),voice().copy(messageVersionId="active"))){assertFails{prepare(listOf(DormantInput(DormantKind.HISTORIC_VOICE,backupJson.encodeToString(GeneratedVoiceMessage.serializer(),v).toByteArray())),mapOf("chatbar-save-slot-audio:a" to byteArrayOf(1)))}}}
    @Test fun unknownSuppliedMediaNotDropped(){assertFails{prepare(m=media()+mapOf("chatbar-backup-file:images/unknown.png" to byteArrayOf(1)))}}
    @Test fun unknownSourceEvidenceCannotBecomePreservationProof(){assertFails{DormantPreservationPreparation.prepare(inputs(),media(),DormantContext(setOf("c"),setOf("s"),emptySet(),context.messageVersions),100000,10)}}
    @Test fun capacityOverflowAndLimitsBeforePublication(){assertFails{prepare(cap=1)};assertFails{DormantPreservationPreparation.requiredCapacity(Long.MAX_VALUE,1,0)};assertFails{DormantPreservationPreparation.requiredCapacity(1,1,-1)};assertFails{DormantInput(DormantKind.MOMENT_POST,ByteArray(1024*1024+1))}}
    @Test fun cancellationAndInjectedFailureReturnNoPartialResult(){val i=inputs();val before=i.map{it.bytes()};var calls=0;val e=assertFailsWith<DormantPreservationPreparation.Refused>{prepare(i,active={if(++calls==3) throw java.io.InterruptedIOException()})};assertEquals("DORMANT_PREPARATION_REFUSED",e.message);i.zip(before).forEach{assertContentEquals(it.second,it.first.bytes())};assertFails{prepare(active={throw IllegalStateException("PRIVATE_FAKE")})}}
    @Test fun processHardExitBeforePublicationRetainsOnlyInactiveTestStage(){
        val root=Files.createTempDirectory("lr4-interrupt-synthetic-");val log=root.resolve("child.log")
        val pb=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx128m","-Djava.awt.headless=true","-cp",System.getProperty("ccb.readonly.classpath"),DormantPrepublicationChild::class.java.name,root.toString())
        listOf("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS").forEach{pb.environment().remove(it)}
        val child=pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();error("SYNTHETIC_CHILD_TIMEOUT")};val evidence=Files.createDirectories(Path.of("build/p11-lr4/child"));Files.copy(log,evidence.resolve("${child.pid()}.log"));Files.writeString(evidence.resolve("${child.pid()}.exit"),child.exitValue().toString());assertEquals(71,child.exitValue());assertContentEquals(byteArrayOf(1,2,3),Files.readAllBytes(root.resolve("inactive-media")));assertFalse(Files.exists(root.resolve("PUBLISHED")))
    }
}
internal object DormantPrepublicationChild {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==1);val root=Path.of(args[0]);require(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
        val raw="""{"id":"p","characterCardId":"c","sessionId":"s","senderName":"Synthetic","text":"Synthetic","imagePath":"chatbar-backup-file:images/body.png","senderAvatar":"chatbar-backup-file:images/avatar.png","scheduledAt":1,"generatedAt":1}""".toByteArray()
        val context=DormantContext(setOf("c"),setOf("s"),emptySet(),emptyMap(),sourceEvidence=PreparationEvidence.SYNTHETIC_VERIFIED)
        val candidate=DormantPreservationPreparation.prepare(listOf(DormantInput(DormantKind.MOMENT_POST,raw)),linkedMapOf("chatbar-backup-file:images/body.png" to byteArrayOf(1,2,3),"chatbar-backup-file:images/avatar.png" to byteArrayOf(4,5,6)),context,100000,10)
        Files.write(root.resolve("inactive-raw"),candidate.rawCopies().single(),StandardOpenOption.CREATE_NEW)
        Files.write(root.resolve("inactive-media"),candidate.mediaCopies().values.first(),StandardOpenOption.CREATE_NEW)
        println("SYNTHETIC_EXPECTED_MEDIA=2");println("SYNTHETIC_COPIED_MEDIA=1");println("SYNTHETIC_PREPUBLICATION_EXIT")
        Runtime.getRuntime().halt(71)
    }
}
