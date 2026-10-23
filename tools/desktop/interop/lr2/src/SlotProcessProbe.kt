package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import java.nio.file.*
import java.util.zip.ZipFile
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Test-classpath child JVM; private witness files are task evidence, never application schema. */
internal object SlotProcessProbe {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val target = "isolated-target"
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        var exit = 0
        try {
            require(args.size == 3 && args[0] in setOf("restore", "restore-halt", "reopen"))
            val source = Path.of(args[1]).toAbsolutePath().normalize()
            val parent = Path.of(args[2]).toAbsolutePath().normalize()
            DesktopArchiveFiles.safe(source, false); DesktopArchiveFiles.safe(parent, true)
            // Parent must be a freshly created explicit test directory; no discovery of application roots.
            if (args[0] != "reopen") {
                check(Files.list(parent).use { it.count() } == 0L)
                val result = SlotIsolatedRestoreProbe.run(source, parent)
                check(result["ISOLATED_RESTORE"] == "COMMITTED")
                val root = Files.list(parent).use { it.filter(Files::isDirectory).toList().single() }
                Files.writeString(parent.resolve("root-name.txt"), root.fileName.toString(), StandardOpenOption.CREATE_NEW)
                val witness = inventory(root)
                Files.writeString(parent.resolve("private-witness.json"), json.encodeToString(JsonObject.serializer(), witness), StandardOpenOption.CREATE_NEW)
                Files.writeString(parent.resolve("private-source.sha"), DesktopArchiveFiles.hash(source,DesktopSaveSlotArchive.MAX_ARCHIVE),StandardOpenOption.CREATE_NEW)
                println("LR2_PROCESS_RESTORE=COMMITTED"); println("LR2_PID=${ProcessHandle.current().pid()}")
                if (args[0] == "restore-halt") Runtime.getRuntime().halt(71)
            } else {
                val name = Files.readString(parent.resolve("root-name.txt"))
                require(name.matches(Regex("isolated-slot-[A-Za-z0-9-]+")))
                val root = parent.resolve(name); DesktopArchiveFiles.safe(root,true)
                val old = json.parseToJsonElement(Files.readString(parent.resolve("private-witness.json"))).jsonObject
                println("LR2_STEP=BEFORE_REOPEN_WITNESS")
                check(inventory(root) == old)
                val resolution = DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,parent.resolve("not-installed-bootstrap"))
                val owner = (DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired).ownership
                try {
                    println("LR2_STEP=JOURNAL_RECOVERY")
                    DesktopSessionCommitJournal(root).recover()
                    check(!DesktopSessionCommitJournal(root).hasPending())
                    FileChannel.open(source, StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
                        ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE).use {
                        val before = DesktopArchiveFiles.hash(source,DesktopSaveSlotArchive.MAX_ARCHIVE)
                        check(before == Files.readString(parent.resolve("private-source.sha")))
                        println("LR2_STEP=SEMANTIC_COMPARISON")
                        verify(source,root).forEach { (key,value)-> println("LR2_$key=$value") }
                        check(DesktopArchiveFiles.hash(source,DesktopSaveSlotArchive.MAX_ARCHIVE) == before)
                    }
                    check(inventory(root) == old)
                    println("LR2_CROSS_PROCESS_BYTES=UNCHANGED"); println("LR2_PID=${ProcessHandle.current().pid()}")
                } finally { owner.close() }
            }
        } catch (_: Throwable) { println("LR2_SLOT_PROCESS=REFUSED");exit=2 }
        if(exit!=0) kotlin.system.exitProcess(exit)
    }
    private suspend fun inventory(root: Path): JsonObject {
        val files = Files.walk(root).use { it.filter { p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) }.toList() }
        require(files.size <= 50000)
        return JsonObject(files.filter { it.fileName.toString() != ".ccb-desktop.lock" }.associate { path ->
            DesktopArchiveFiles.safe(path,false)
            root.relativize(path).joinToString("/") to JsonPrimitive(DesktopArchiveFiles.hash(path,DesktopSaveSlotArchive.MAX_ARCHIVE))
        })
    }
    private suspend fun verify(source: Path,root: Path): Map<String,String> {
        val storage=JsonFileStorage(root);val chats=ChatRepository(storage);val voice=VoiceMessageRepository(storage,true)
        val actual=chats.getMessages(target);val session=requireNotNull(chats.getSession(target))
        val result=linkedMapOf<String,String>()
        ZipFile(source.toFile()).use { zip ->
            fun value(name:String)=zip.getInputStream(requireNotNull(zip.getEntry(name))).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val slot=json.decodeFromString(SaveSlot.serializer(),value("manifest.json"))
            val messages=value("messages.jsonl").lineSequence().filter(String::isNotBlank).map { json.decodeFromString(ChatMessage.serializer(),it) }.toList()
            check(messages.size==actual.size)
            println("LR2_STEP=MESSAGES")
            messages.zip(actual).forEach { (original,restored)->
                val normalized=MessageAlternativeVersionPolicy.normalize(original)
                // Compare all persisted message fields except the three documented relocation fields.
                val expected=normalized.copy(sessionId=target,images=restored.images,generatedImageMetadata=normalized.generatedImageMetadata.mapIndexed { index,meta ->
                    meta.copy(imagePath=restored.generatedImageMetadata[index].imagePath)
                })
                check(expected==restored)
                check(original.images.size==restored.images.size)
            }
            result["MESSAGE_ALL_FIELDS_EXCEPT_RESOURCE_RELOCATION"]="VERIFIED"
            // ChatRepository.replaceMessagesForSessionStreaming enforces next-order monotonicity.
            val projected=SaveSlotSessionPolicy.restore(session,slot,session.chatBackground).copy(
                nextSourceTurnOrder=maxOf(slot.nextSourceTurnOrder,(messages.mapNotNull{it.sourceTurnOrder}.maxOrNull()?:0L)+1),
                nextTimelineTurn=maxOf(slot.nextTimelineTurn,(messages.mapNotNull{it.timelineTurn}.maxOrNull()?:0L)+1))
            println("LR2_STEP=SESSION")
            val expectedSession=json.encodeToJsonElement(ChatSession.serializer(),projected).jsonObject
            val actualSession=json.encodeToJsonElement(ChatSession.serializer(),session).jsonObject
            // These two caches are recomputed by the existing Memory owner, checked separately below.
            val derived=setOf("longTermMemory","longTermMemoryUpdatedThroughMessageId")
            val differing = expectedSession.keys.filter { it !in derived && expectedSession[it] != actualSession[it] }
            if(differing.isNotEmpty()) println("LR2_SESSION_DIFFERENT_FIELDS="+differing.sorted().joinToString(","))
            check(expectedSession.filterKeys { it !in derived }==actualSession.filterKeys { it !in derived })
            result["SESSION_ARCHIVE_FIELDS"]="VERIFIED_EXCEPT_DERIVED_MEMORY_CACHE"
            val sourceRag=value("rag.jsonl").lineSequence().filter(String::isNotBlank).map { json.decodeFromString(VectorChunk.serializer(),it) }.toList()
            println("LR2_STEP=RAG")
            val restoredRag=storage.loadAll("vector_chunks",VectorChunk.serializer()).filter { it.sourceId==target }
            fun ragKey(c:VectorChunk)=json.encodeToString(VectorChunk.serializer(),c.copy(id="normalized",sourceId=target,
                metadata=c.metadata.mapValues { (key,text)->if(key=="sessionId") target else text }))
            check(sourceRag.map(::ragKey).sorted()==restoredRag.map(::ragKey).sorted())
            result["RAG_ALL_FIELDS_EXCEPT_IDENTITIES"]="VERIFIED";result["RAG_COUNT"]=sourceRag.size.toString()
            val memory=MemoryRepository(storage,true).snapshot(target)
            println("LR2_STEP=MEMORY")
            result["MEMORY"]=if(slot.memorySnapshot?.state==null) "LEGACY_RECONSTRUCTION_NOT_EQUIVALENCE_PROVEN"
                else if(memory!=null && canonicalMemory(slot.memorySnapshot!!)==canonicalMemory(memory)) "CURRENT_SNAPSHOT_FIELDS_AND_GRAPH_VERIFIED" else "SNAPSHOT_DIFFERENCE_BLOCKED"
            println("LR2_STEP=MEDIA")
            // Recheck owned file bytes and every source->destination media position in a different JVM.
            fun hash(input:java.io.InputStream)=input.use{stream->val md=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(32768)
                try {while(true){val n=stream.read(b);if(n<0)break;md.update(b,0,n)};md.digest().toList()}finally{b.fill(0)}}
            fun sourceImage(ref:String):List<Byte> {
                check(ref.startsWith("chatbar-save-slot-image:"));val key=ref.removePrefix("chatbar-save-slot-image:")
                val entry=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("media/images/")&&java.io.File(it.name).nameWithoutExtension==key}.single()
                return hash(zip.getInputStream(entry))
            }
            messages.zip(actual).forEach{(a,b)->a.images.zip(b.images).forEach{(old,next)->
                if(old.startsWith("chatbar-save-slot-omitted-image:")) check(old==next)
                else check(sourceImage(old)==hash(Files.newInputStream(root.resolve(next))))
            }}
            messages.zip(actual).forEach{(a,b)->a.generatedImageMetadata.zip(b.generatedImageMetadata).forEach{(old,next)->
                if(old.imagePath.startsWith("chatbar-save-slot-omitted-image:")) check(old.imagePath==next.imagePath)
                else check(sourceImage(old.imagePath)==hash(Files.newInputStream(root.resolve(next.imagePath))))
            }}
            slot.chatBackground?.let{old->if(!old.startsWith("chatbar-save-slot-omitted-image:"))
                check(sourceImage(old)==hash(Files.newInputStream(root.resolve(requireNotNull(session.chatBackground)))))}
            val sourceAudio=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("media/audio/")}.map{hash(zip.getInputStream(it)).toString()}.sorted().toList()
            val restoredAudio=voice.listForSession(target).map{hash(Files.newInputStream(Path.of(it.audioPath))).toString()}.sorted()
            check(sourceAudio==restoredAudio)
            val inputVoices=zip.getEntry("voices.jsonl")?.let{value("voices.jsonl").lineSequence().filter(String::isNotBlank)
                .map{json.decodeFromString(GeneratedVoiceMessage.serializer(),it)}.toList()}.orEmpty()
            val restoredVoices=voice.listForSession(target)
            fun canonicalVoice(v:GeneratedVoiceMessage,version:String?)=json.encodeToString(GeneratedVoiceMessage.serializer(),v.copy(
                id="normalized",sessionId=target,anchorId=null,audioPath="relocated",durationMs=0,updatedAt=0,messageVersionId=version))
            val expectedVoices=inputVoices.map{v->val message=actual.single{it.id==v.messageId}
                canonicalVoice(v,v.messageVersionId?:com.example.chatbar.domain.voice.VoiceMessageVersionPolicy.inferLegacyVersionId(message,v,null))}.sorted()
            check(expectedVoices==restoredVoices.map{canonicalVoice(it,it.messageVersionId)}.sorted())
            result["VOICE_FIELDS_EXCEPT_REBUILT_ID_ANCHOR_DURATION_TIME"]="VERIFIED"
            result["MEDIA_BYTES"]="VERIFIED";result["MESSAGES"]=actual.size.toString();result["AUDIO"]=restoredAudio.size.toString()
        }
        return result
    }
    private fun canonicalMemory(snapshot:MemorySnapshot):JsonElement {
        val nodes=snapshot.nodes.sortedWith(compareBy({it.createdAt},{it.tier.name},{it.body}))
        val keys=nodes.map{Triple(it.createdAt,it.tier,it.body)};require(keys.distinct().size==keys.size)
        val map=nodes.mapIndexed{i,n->n.id to "node-$i"}.toMap()
        fun rewrite(v:JsonElement,key:String=""):JsonElement=when(v){
            is JsonObject->JsonObject(v.filterKeys{it !in setOf("sourceHash","coverageHash")}.mapKeys{map[it.key]?:it.key}.mapValues{(k,x)->rewrite(x,k)})
            is JsonArray->JsonArray(v.map{rewrite(it,key)})
            is JsonPrimitive->if(v.isString) JsonPrimitive(if(key=="sessionId") target else if(key=="status" && v.content=="RUNNING") "PAUSED" else map[v.content]?:v.content) else v
            else->v
        }
        return rewrite(json.encodeToJsonElement(MemorySnapshot.serializer(),snapshot.copy(nodes=nodes)))
    }
}
