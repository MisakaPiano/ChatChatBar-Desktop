package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.ChunkingEngine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Independent wire builder. It never calls the production parser or its wire serializers. */
internal class DesktopDocumentIndexTransactionSyntheticFixture : AutoCloseable {
    val root:Path=Files.createTempDirectory("ccb-g2-fixture-")
    val owner=acquire(root)
    val domain=root.resolve("entities/document_index_transactions")
    val json=Json{encodeDefaults=true}
    var cardTransform:(Boolean,ByteArray)->ByteArray={_,bytes->bytes}
    var embedding:List<Float> = listOf(1f,0f)
    private val rootClaim=buildJsonObject{put("lineageId","11111111-1111-1111-1111-111111111111");put("epoch",1)}
    private val writer=buildJsonObject{put("authorityId","22222222-2222-2222-2222-222222222222");put("epoch",1)}
    private val key="a".repeat(64)
    private val config=buildJsonObject {
        put("store","SINGLETON");put("id","default");put("generation","33333333-3333-3333-3333-333333333333");put("revision",1)
        put("publicFingerprint","b".repeat(64));put("credentialGeneration","44444444-4444-4444-4444-444444444444");put("dimensions",2);put("embeddingKey",key)
    }
    var card=CharacterCard.create("Synthetic").copy(id="card",createdAt=1,updatedAt=1,
        customDocuments=listOf("doc","doc2").map{DocumentInfo(it,"$it.txt","documents/$it.txt","txt",1)})
    private var currentHead:JsonObject?=null
    private var headBytes:ByteArray?=null
    private val bindings=linkedMapOf<String,JsonObject>()
    private val vectors=linkedMapOf<String,List<VectorChunk>>()
    private var revision=0
    init {
        for(doc in card.customDocuments)bindings[doc.id]=buildJsonObject{put("documentId",doc.id);put("generation",0);put("producerTxId",JsonNull);put("source",JsonNull);put("config",JsonNull);put("set",JsonNull)}
    }
    data class Transaction(val tx:String,val bundle:Path,val intent:JsonObject,val head:JsonObject,val newCard:CharacterCard,val chunks:List<VectorChunk>)

    fun createDomain() {
        listOf("active","history","heads").forEach{Files.createDirectories(domain.resolve(it))}
        put(domain.resolve("domain.json"),control("DOMAIN",buildJsonObject{put("root",rootClaim);put("writer",writer)}))
    }
    fun prepare(document:String="doc",body:String="Synthetic document body.",completed:Boolean=false):Transaction {
        if(!Files.exists(domain))createDomain()
        val tx=UUID.randomUUID().toString();val bundle=domain.resolve("active/$tx")
        listOf("old/vectors","stage/vectors").forEach{Files.createDirectories(bundle.resolve(it))}
        val source=body.toByteArray();val sourcePath="documents/$document.txt";write(root.resolve(sourcePath),source)
        if(card.customDocuments.none{it.id==document}) {
            card=card.copy(customDocuments=card.customDocuments+DocumentInfo(document,"$document.txt",sourcePath,"txt",1))
            bindings[document]=buildJsonObject{put("documentId",document);put("generation",0);put("producerTxId",JsonNull);put("source",JsonNull);put("config",JsonNull);put("set",JsonNull)}
        }
        val old=card
        val plan=ChunkingEngine().chunkDocument(body,document,"$document.txt")
        val chunks=plan.mapIndexed{index,(text,meta)->VectorChunk("${document}_${revision}_$index",ChunkSourceType.DOCUMENT,"card",content=text,
            embedding=embedding,metadata=meta+mapOf("originalDocId" to document,"fileName" to "$document.txt","contentHash" to hash(source),"embeddingKey" to key),createdAt=1)}
        val newer=card.copy(updatedAt=2,customDocuments=card.customDocuments.map{if(it.id!=document)it else it.copy(contentHash=hash(source),indexedHash=hash(source),
            ragStatus="INDEXED",ragChunkCount=chunks.size,ragIndexedAt=2,ragError=null)},ragIndexStatus="COMPLETE",ragIndexDone=chunks.size,ragIndexTotal=chunks.size)
        val oldRaw=cardTransform(false,json.encodeToString(old).toByteArray());val newRaw=cardTransform(true,json.encodeToString(newer).toByteArray())
        write(bundle.resolve("old/card.json"),oldRaw);write(bundle.resolve("stage/card.json"),newRaw);write(bundle.resolve("source.bin"),source)
        write(root.resolve("entities/character_cards/card.json"),oldRaw)
        val oldChunks=vectors[document].orEmpty()
        val oldRows=rows(oldChunks);val newRows=rows(chunks)
        val unrelated=rows(vectors.filterKeys{it!=document}.values.flatten())
        write(bundle.resolve("old-set.jsonl"),oldRows);write(bundle.resolve("new-set.jsonl"),newRows);write(bundle.resolve("unrelated.jsonl"),unrelated)
        oldChunks.forEach{write(bundle.resolve("old/vectors/${it.id}.json"),json.encodeToString(it).toByteArray())}
        chunks.forEach{write(bundle.resolve("stage/vectors/${it.id}.json"),json.encodeToString(it).toByteArray())}
        val sourceClaim=buildJsonObject{put("path",sourcePath);put("bytes",source.size);put("sha256",hash(source))}
        val previous=currentHead?.let{h->buildJsonObject {put("txId",h.getValue("txId"));put("revision",revision);put("file",file(headBytes!!))}}
        headBytes?.let{write(bundle.resolve("old/head.json"),it)}
        val oldGeneration=(bindings.getValue(document)["generation"] as JsonPrimitive).int
        bindings[document]=buildJsonObject {
            put("documentId",document);put("generation",oldGeneration+1);put("producerTxId",tx);put("source",sourceClaim);put("config",config)
            put("set",buildJsonObject{put("records",chunks.size);put("payloadBytes",payload(chunks));put("rowsSha256",hash(newRows))})
        }
        val state=buildJsonObject{put("cardId","card");put("revision",revision+1);put("card",file(newRaw));put("documents",JsonArray(bindings.toSortedMap().values.toList()))}
        val intent=control("INTENT",buildJsonObject {
            put("txId",tx);put("taskId","55555555-5555-5555-5555-555555555555");put("root",rootClaim);put("writer",writer);put("operation","INDEX_DOCUMENT")
            put("cardId","card");put("documentId",document);put("source",sourceClaim);put("config",config);put("expectedHead",previous?:JsonNull)
            put("oldCard",file(oldRaw));put("newCard",file(newRaw));put("oldSet",manifest(oldRows,oldChunks));put("newSet",manifest(newRows,chunks));put("unrelated",manifest(unrelated,vectors.filterKeys{it!=document}.values.flatten()));put("next",state)
        })
        put(bundle.resolve("intent.json"),intent)
        val seal=control("SEAL",buildJsonObject{
            put("txId",tx)
            for((field,path)in mapOf("intentFile" to "intent.json","oldManifestFile" to "old-set.jsonl","newManifestFile" to "new-set.jsonl","unrelatedManifestFile" to "unrelated.jsonl","oldCardFile" to "old/card.json","newCardFile" to "stage/card.json","sourceFile" to "source.bin"))put(field,file(Files.readAllBytes(bundle.resolve(path))))
            put("oldHeadFile",headBytes?.let(::file)?:JsonNull)
        });put(bundle.resolve("seal.json"),seal)
        val marker=control("COMMIT_MARKER",buildJsonObject{
            put("txId",tx);put("root",rootClaim);put("writer",writer);put("intentFile",file(Files.readAllBytes(bundle.resolve("intent.json"))));put("sealFile",file(Files.readAllBytes(bundle.resolve("seal.json"))));put("predecessor",previous?:JsonNull);put("next",state)
        })
        put(bundle.resolve("commit.json"),marker)
        val receipt=control("HISTORY_RECEIPT",buildJsonObject{
            put("txId",tx);put("root",rootClaim);put("writer",writer);put("intentFile",file(Files.readAllBytes(bundle.resolve("intent.json"))));put("sealFile",file(Files.readAllBytes(bundle.resolve("seal.json"))));put("commitFile",file(Files.readAllBytes(bundle.resolve("commit.json"))));put("predecessor",previous?:JsonNull);put("next",state)
        });put(bundle.resolve("receipt.json"),receipt)
        val head=control("HEAD",buildJsonObject{
            put("txId",tx);put("root",rootClaim);put("writer",writer)
            for((field,path)in mapOf("intentFile" to "intent.json","sealFile" to "seal.json","commitFile" to "commit.json","receiptFile" to "receipt.json"))put(field,file(Files.readAllBytes(bundle.resolve(path))))
            put("predecessor",previous?:JsonNull);put("state",state)
        })
        val t=Transaction(tx,bundle,intent,head,newer,chunks)
        if(completed)complete(t) else {Files.delete(bundle.resolve("commit.json"));Files.delete(bundle.resolve("receipt.json"))}
        return t
    }
    fun complete(t:Transaction) {
        val doc=(t.intent["documentId"] as JsonPrimitive).content
        vectors[doc].orEmpty().forEach{Files.deleteIfExists(root.resolve("entities/vector_chunks/${it.id}.json"))}
        t.chunks.forEach{write(root.resolve("entities/vector_chunks/${it.id}.json"),json.encodeToString(it).toByteArray())}
        write(root.resolve("entities/character_cards/card.json"),json.encodeToString(t.newCard).toByteArray())
        put(domain.resolve("heads/card.json"),t.head)
        Files.move(t.bundle,domain.resolve("history/${t.tx}"))
        currentHead=t.head;headBytes=Files.readAllBytes(domain.resolve("heads/card.json"));revision++;card=t.newCard;vectors[doc]=t.chunks
    }
    fun rewindSyntheticAuthority(t:Transaction) {
        currentHead=t.head;headBytes=t.head.toString().toByteArray();card=t.newCard
        val state=t.head.getValue("state") as JsonObject
        revision=(state.getValue("revision") as JsonPrimitive).int
        bindings.clear()
        for(e in state.getValue("documents") as JsonArray){val b=e as JsonObject;bindings[(b.getValue("documentId") as JsonPrimitive).content]=b}
        vectors.clear();vectors[(t.intent.getValue("documentId") as JsonPrimitive).content]=t.chunks
    }
    fun snapshot():Map<String,String> = Files.walk(root).use{stream->stream.toList().associate{p->
        val relative=root.relativize(p).toString().replace('\\','/')
        val directory=Files.isDirectory(p)
        val identity=hash(DesktopIndexStartupPreflight.readWindowsIdentity(p,directory).toString().toByteArray())
        relative to if(directory)"DIRECTORY:$identity" else if(p==owner.lockPath)"OWNERSHIP:$identity:${Files.size(p)}" else "$identity:${Files.size(p)}:${hash(Files.readAllBytes(p))}"
    }}
    override fun close(){owner.close()}
    private fun rows(chunks:List<VectorChunk>):ByteArray=chunks.sortedBy{it.id}.joinToString(""){v->
        val raw=json.encodeToString(v).toByteArray()
        buildJsonObject{put("storageId",v.id);put("bytes",raw.size);put("sha256",hash(raw))}.toString()+"\n"
    }.toByteArray()
    private fun payload(chunks:List<VectorChunk>)=chunks.sumOf{json.encodeToString(it).toByteArray().size.toLong()}
    private fun manifest(raw:ByteArray,chunks:List<VectorChunk>)=buildJsonObject{put("file",file(raw));put("records",chunks.size);put("payloadBytes",payload(chunks))}
    fun put(path:Path,o:JsonObject)=write(path,o.toString().toByteArray())
    fun write(path:Path,raw:ByteArray){Files.createDirectories(path.parent);Files.write(path,raw)}
    private fun control(kind:String,body:JsonObject)=buildJsonObject{put("format","CCB_DOCUMENT_INDEX_J");put("version",1);put("kind",kind);body.forEach{(k,v)->put(k,v)}}
    private fun file(raw:ByteArray)=buildJsonObject{put("bytes",raw.size);put("sha256",hash(raw))}
    companion object {
        fun hash(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        fun acquire(root:Path):DesktopDataRootOwnership {
            val result=DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.parent.resolve("synthetic-bootstrap.json")))
            check(result is DesktopDataRootOwnershipResult.Acquired){"Synthetic ownership acquisition refused"}
            return result.ownership
        }
        fun recordProcessInventory(before:Map<String,String>,after:Map<String,String>) {
            val directory=System.getenv("CCB_G2_INSPECTOR_EVIDENCE") ?: return
            val row=buildJsonObject{put("snapshotEqual",before==after);put("before",buildJsonObject{before.toSortedMap().forEach{(k,v)->put(k,v)}});put("after",buildJsonObject{after.toSortedMap().forEach{(k,v)->put(k,v)}})}
            Files.writeString(Path.of(directory).resolve("process-inventories.jsonl"),row.toString()+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
        }
    }
    fun addUnrelated(count:Int) {
        val records=(0 until count).map{VectorChunk("unrelated_%05d".format(it),ChunkSourceType.CHARACTER_SETTING,"other",content="Synthetic unrelated",embedding=listOf(1f,0f),createdAt=1)}
        vectors["unrelated"]=records
        records.forEach{write(root.resolve("entities/vector_chunks/${it.id}.json"),json.encodeToString(it).toByteArray())}
    }
}
