package com.example.chatbar.desktop.backup

import java.nio.file.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Synthetic design probe only: does not register a persistent domain or invoke a scheduler. */
class CaOwnershipPreflightTest {
    private data class Candidate(val raw:Map<String,ByteArray>,val copies:Map<String,ByteArray>) {
        val requiredBytes:Long get()=raw.values.sumOf{it.size.toLong()}+copies.values.sumOf{it.size.toLong()}
    }
    private fun prepare(post:String,task:String,resources:Map<String,ByteArray>,capacity:Long):Candidate {
        val raw=mapOf("entities/moment_posts/p.json" to post.toByteArray(),"entities/moment_tasks/t.json" to task.toByteArray())
        val entities=raw.mapValues{LargeArchiveReadOnly.strict(it.value).jsonObject} + mapOf(
            "entities/character_cards/c.json" to buildJsonObject{put("id","c");put("characters",buildJsonArray{})},
            "entities/chat_sessions/s.json" to buildJsonObject{put("id","s");put("characterCardId","c")})
        val inventory=(raw+resources).mapValues{(path,bytes)->AppBackupFileRecord(path,bytes.size.toLong(),DesktopBackupPolicy.digest(bytes))}
        val manifest=InspectionManifest(appVersionCode=84,appVersionName="synthetic145",createdAt=1,sourceFilesRoot="synthetic",
            counts=emptyMap(),fileCount=inventory.size,totalBytes=inventory.values.sumOf{it.size},preferencesSha256="0".repeat(64))
        val graph=MomentArchiveReadOnlyValidator().validate(manifest,inventory,entities,"SYNTHETIC",false){}
        require(graph.state==MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE)
        val candidate=Candidate(raw,resources)
        require(capacity>=candidate.requiredBytes)
        return candidate
    }
    private val post="""{ "id":"p", "characterCardId":"c","sessionId":"s","senderName":"SYNTHETIC","text":"SYNTHETIC","senderAvatar":"chatbar-backup-file:images/avatar.png","imagePath":"chatbar-backup-file:images/body.png","generatedImageMetadata":{"imagePath":"chatbar-backup-file:images/meta.png","baseCaption":"synthetic","negativePrompt":"synthetic","sizePreset":"synthetic","width":1,"height":1},"scheduledAt":1,"generatedAt":1 }"""
    private val task="""{"id":"t","characterCardId":"c","sessionId":"s","scheduledAt":1,"postId":"p"}"""
    private val resources=mapOf("images/avatar.png" to byteArrayOf(1),"images/body.png" to byteArrayOf(2),"images/meta.png" to byteArrayOf(3))
    @Test fun `three independent image paths preserve exact JSON and physically separate copies`() {
        val candidate=prepare(post,task,resources,100000)
        val root=Files.createTempDirectory("lr2-ca-synthetic-")
        val live=Files.createDirectory(root.resolve("synthetic-live"));val copy=Files.createDirectory(root.resolve("synthetic-copy"))
        (candidate.raw+candidate.copies).forEach{(path,bytes)->
            val a=live.resolve(path);val b=copy.resolve(path);Files.createDirectories(a.parent);Files.createDirectories(b.parent)
            Files.write(a,bytes,StandardOpenOption.CREATE_NEW);Files.copy(a,b)
            assertFalse(Files.isSameFile(a,b));Files.delete(a);assertContentEquals(bytes,Files.readAllBytes(b))
        }
        assertContentEquals(post.toByteArray(),Files.readAllBytes(copy.resolve("entities/moment_posts/p.json")))
        assertEquals(3,candidate.copies.size)
        // Explicit file deletion, not a claim about production GC or Android pending-deletion.
    }
    @Test fun `capacity refusal does not invoke copy or delete`() {
        assertFails{prepare(post,task,resources,1)}
    }
    @Test fun `missing metadata resource and mismatched task relation reject entire candidate`() {
        assertFails{prepare(post,task,resources-"images/meta.png",100000)}
        assertFails{prepare(post,task.replace("\"p\"","\"absent\""),resources,100000)}
        assertFails{prepare(post.replace("chatbar-backup-file:images/avatar.png","C:/external.png"),task,resources,100000)}
    }
}

