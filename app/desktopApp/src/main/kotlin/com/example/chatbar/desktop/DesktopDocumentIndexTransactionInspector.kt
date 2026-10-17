package com.example.chatbar.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.domain.rag.ChunkingEngine

/** Standalone, non-admitting inspector. Not called by Main or any business service. */
internal class DesktopDocumentIndexTransactionInspector(
    private val limits: DesktopIndexWireLimits = DesktopIndexWireLimits(),
    private val onStep: suspend (Step) -> Unit = {},
    private val onReadStep: suspend (DesktopDocumentIndexReadEvidence.Step) -> Unit = {},
) {
    internal enum class Step { INVENTORIED, BEFORE_LIVE, BEFORE_FINAL_RECHECK }
    suspend fun inspect(root: Path): DesktopIndexInspectionResult = withContext(Dispatchers.IO) { Run(root).inspect() }

    private inner class Run(input: Path) {
        val root = input.toAbsolutePath().normalize()
        val rawRoot = input.toAbsolutePath()
        val arena = JReadArena(limits.arenaBytes)
        val catalog by lazy { JCatalog(arena, limits.totalPaths) }
        val missing = linkedSetOf<DesktopIndexMissingProof>()
        var phase = DesktopIndexInspectionPhase.ROOT
        var presence = DesktopIndexDomainPresence.UNDETERMINED
        var live = DesktopIndexObservedLive.NOT_CHECKED
        var domainEntries = 0L; var locatorEntries = 0L; var liveEntries = 0L
        var liveFileCount=0L
        var domainBytes = 0L; var liveBytes = 0L; var domainRead = 0L; var liveRead = 0L
        var readBytes = 0L; var operations = 0L; var rows = 0L; var handles = 0L
        var domainNames = 0L; var domainPaths = 0L; var locatorNames = 0L; var locatorPaths = 0L
        val absent = ArrayList<Path>()
        val reader = DesktopDocumentIndexReadEvidence(::operation, onReadStep)
        val domain = root.resolve("entities/document_index_transactions")
        var domainRoot: JRoot? = null; var domainWriter: JWriter? = null
        val bundles = linkedMapOf<String, Bundle>()
        val heads = linkedMapOf<String, Pair<Int, JHead>>()

        suspend fun inspect(): DesktopIndexInspectionResult = try {
            owner()
            jRequire(rawRoot==root && inputNormal(root), DesktopIndexInspectionCode.UNSAFE_PATH)
            jRequire(root.toString().length <= 512, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            var cursor: Path? = root
            val ancestors = ArrayList<Path>()
            while (cursor != null) { jRequire(ancestors.size < 64, DesktopIndexInspectionCode.BUDGET_EXCEEDED); ancestors.add(cursor); cursor = cursor.parent }
            for (ancestor in ancestors.asReversed()) observe(ancestor, true)
            val entities = child(root, "entities")
            val d = entities?.let { observe(it, true); child(it, "document_index_transactions") }
            if (d == null) {
                presence = DesktopIndexDomainPresence.ABSENT
                finish(); result(DesktopIndexInspectionCode.NO_DOMAIN)
            } else {
                presence = DesktopIndexDomainPresence.PRESENT
                phase = DesktopIndexInspectionPhase.INVENTORY
                scan(d, 0)
                onStep(Step.INVENTORIED)
                parseDomain()
                finish()
                result(classify())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (refused: JReadRefusal) { result(refused.code) }
        catch (_: ArithmeticException) { result(DesktopIndexInspectionCode.BUDGET_EXCEEDED) }
        catch (_: Exception) { result(DesktopIndexInspectionCode.READ_ERROR) }

        private fun inputNormal(path: Path): Boolean = path.root != null && path.none {
            !DesktopIndexStartupPreflight.safeWindowsName(it.toString()) || it.toString().length > 255
        }
        private fun owner() { jRequire(DesktopDataRootOwnership.isHeld(root), DesktopIndexInspectionCode.OWNERSHIP_MISSING) }
        private fun operation() { owner(); operations = jAdd(operations, 1, limits.metadataOperations) }
        private fun key(path: Path): String = if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/') else "!" + path
        private fun path(index: Int): Path = catalog.node(index).key.let { if (it.startsWith('!')) Path.of(it.substring(1)) else root.resolve(it) }
        private suspend fun observe(path: Path, directory: Boolean? = null): Int {
            currentCoroutineContext().ensureActive(); owner()
            jRequire(path.toString().length <= 1024, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            val k = key(path)
            if (!k.startsWith('!')) jRequire(k.length <= 384, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            val stamp = reader.observe(path, directory)
            val old = catalog.find(k)
            if (old >= 0) {
                recheckStamp(old, stamp); return old
            }
            return catalog.add(k, stamp)
        }
        private fun recheckStamp(index: Int, stamp: DesktopDocumentIndexReadEvidence.Stamp) {
            val old = catalog.node(index)
            val match = if (old.key.startsWith('!')) old.stamp.copy(modified=stamp.modified, changed=stamp.changed, size=stamp.size) == stamp else old.stamp == stamp
            jRequire(match, DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
        }
        private suspend fun enumerate(parent: Path, domainScan: Boolean, visit: suspend (Path) -> Unit) {
            observe(parent, true)
            operation()
            Files.newDirectoryStream(parent).use { stream ->
                for (child in stream) {
                    currentCoroutineContext().ensureActive(); operation()
                    val name = child.fileName.toString()
                    jRequire(name.length <= 255 && child.parent == parent && DesktopIndexStartupPreflight.safeWindowsName(name), DesktopIndexInspectionCode.UNSAFE_PATH)
                    if (!domainScan && parent!=root.resolve("entities/vector_chunks") && parent!=root.resolve("entities/character_cards")) {
                        locatorEntries=jAdd(locatorEntries,1,limits.locatorEntries)
                        locatorNames=jAdd(locatorNames,name.length.toLong(),limits.locatorNameUnits)
                        locatorPaths=jAdd(locatorPaths,child.toString().length.toLong(),limits.locatorPathUnits)
                    }
                    visit(child)
                }
            }
        }
        private suspend fun child(parent: Path, name: String): Path? {
            var found: Path? = null
            enumerate(parent, false) { child -> if (child.fileName.toString().equals(name,true)) {
                jRequire(child.fileName.toString()==name && found==null, DesktopIndexInspectionCode.UNSAFE_PATH); found=child
            } }
            if (found==null) { arena.charge(2304); absent.add(parent.resolve(name)) }
            return found
        }
        private suspend fun scan(parent: Path, depth: Int) {
            jRequire(depth<=limits.depth,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            val index=observe(parent)
            domainEntries=jAdd(domainEntries,1,limits.domainEntries)
            domainNames=jAdd(domainNames,parent.fileName.toString().length.toLong(),limits.domainNameUnits)
            domainPaths=jAdd(domainPaths,domain.relativize(parent).toString().length.toLong(),limits.domainPathUnits)
            val node=catalog.node(index)
            if (node.stamp.directory) enumerate(parent,true) { scan(it,depth+1) }
            else domainBytes=jAdd(domainBytes,node.stamp.size,limits.domainBytes)
        }
        private fun find(relative: String): Int = catalog.find(key(domain.resolve(relative)))
        private fun requireFile(relative: String, proof: DesktopIndexMissingProof): Int? {
            val n=find(relative)
            if(n<0){ missing+=proof; return null }
            jRequire(!catalog.node(n).stamp.directory); catalog.used(n); return n
        }
        private fun requireDirectory(relative: String): Boolean {
            val n=find(relative)
            if(n<0){ missing+=DesktopIndexMissingProof.FIXED_DIRECTORY; return false }
            jRequire(catalog.node(n).stamp.directory); catalog.used(n); return true
        }
        private suspend fun bytes(index: Int, max: Int, liveFile: Boolean = false): ByteArray {
            val node=catalog.node(index)
            jRequire(!node.stamp.directory && node.stamp.size<=max,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            reserveRead(index,liveFile)
            val out=ByteArrayOutputStream(node.stamp.size.toInt())
            handles++
            val read=reader.read(path(index),node.stamp) { buffer,count ->
                readBytes=jAdd(readBytes,count.toLong(),Long.MAX_VALUE); out.write(buffer,0,count)
            }
            catalog.read(index,read.file)
            return out.toByteArray()
        }
        private fun reserveRead(index: Int, liveFile: Boolean) {
            val node=catalog.node(index)
            jRequire(node.reads<2,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            val reservation=jAdd(node.stamp.size,1,Long.MAX_VALUE)
            if(liveFile)liveRead=jAdd(liveRead,reservation,limits.liveReadBytes) else domainRead=jAdd(domainRead,reservation,limits.domainReadBytes)
            catalog.attempt(index)
        }
        private suspend fun control(index: Int): JsonObject = parse(bytes(index,limits.controlBytes))
        private fun parse(bytes: ByteArray, entity: Boolean = false): JsonObject = try { JWire.parse(bytes,entity) }
            catch(e:JReadRefusal){throw e} catch(_:Exception){jRefuse(DesktopIndexInspectionCode.CORRUPT)}
        private fun evidence(index:Int):JFileEvidence = catalog.node(index).file ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
        private fun same(actual:JFileEvidence, expected:JFileEvidence) { jRequire(actual==expected) }
        private fun stateSame(a:JState,b:JState, code:DesktopIndexInspectionCode=DesktopIndexInspectionCode.CORRUPT) { jRequire(arena.equal(a.canonical,b.canonical),code) }
        private fun bindings(state:JState)=JWire.bindings(parse(arena.bytes(state.canonical)))
        private fun claims(root:JRoot,writer:JWriter,code:DesktopIndexInspectionCode=DesktopIndexInspectionCode.CORRUPT){jRequire(root==domainRoot && writer==domainWriter,code)}

        private suspend fun parseDomain() {
            phase=DesktopIndexInspectionPhase.HEADER
            catalog.used(find(""))
            val header=requireFile("domain.json",DesktopIndexMissingProof.DOMAIN_HEADER)
            if(header==null) { validateIncompletePaths(); return }
            val o=control(header)
            JWire.controlFields(o,"DOMAIN","root","writer")
            domainRoot=JWire.root(JWire.get(o,"root")); domainWriter=JWire.writer(JWire.get(o,"writer"))
            missing+=setOf(DesktopIndexMissingProof.ROOT_LINEAGE_AUTHORITY,DesktopIndexMissingProof.CONFIG_WRITER_AUTHORITY,
                DesktopIndexMissingProof.WRITER_HANDOFF,DesktopIndexMissingProof.READINESS_CACHE_EPOCH)
            for(dir in listOf("active","history","heads"))requireDirectory(dir)
            phase=DesktopIndexInspectionPhase.PAYLOAD
            val roots=directChildren("active")+directChildren("history")
            jRequire(directChildren("active").size<=1 && directChildren("history").size<=128,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            for(n in roots) {
                val node=catalog.node(n); jRequire(node.stamp.directory)
                val tx=node.key.substringAfterLast('/'); JWire.uuid(JsonPrimitive(tx))
                jRequire(!bundles.containsKey(tx),DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                // Conservative reservation for the small typed control graph, in addition to packed state bytes.
                arena.charge(65_536)
                val relative=domain.relativize(path(n)).toString().replace('\\','/')
                bundles[tx]=loadBundle(relative,tx,relative.startsWith("history/"))
            }
            for(n in directChildren("heads")) {
                jRequire(heads.size<128,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
                val name=path(n).fileName.toString(); jRequire(name.endsWith(".json")); val id=name.removeSuffix(".json"); JWire.validId(id)
                arena.charge(16_384)
                jRequire(!catalog.node(n).stamp.directory); catalog.used(n)
                val head=JWire.head(control(n),arena); jRequire(head.state.cardId==id)
                claims(head.root,head.writer,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT); heads[id]=n to head
            }
            validateAllUsed()
            phase=DesktopIndexInspectionPhase.GRAPH
            graph()
            phase=DesktopIndexInspectionPhase.LIVE
            onStep(Step.BEFORE_LIVE)
            liveCheck()
        }
        private fun directChildren(relative:String):List<Int> {
            val prefix=key(domain.resolve(relative)).trimEnd('/')+"/"
            return (0 until catalog.size).filter { val s=catalog.node(it).key; s.startsWith(prefix) && !s.substring(prefix.length).contains('/') }
        }
        private fun validateIncompletePaths() {
            for(i in 0 until catalog.size) if(path(i).startsWith(domain)) {
                val r=domain.relativize(path(i)).toString().replace('\\','/')
                jRequire(closedShape(r,catalog.node(i).stamp.directory))
            }
        }
        private fun closedShape(relative:String,directory:Boolean):Boolean {
            if(relative.isEmpty())return directory
            val p=relative.split('/')
            if(p.size==1)return if(p[0]=="domain.json")!directory else p[0] in setOf("active","history","heads") && directory
            if(p[0]=="heads") {
                if(p.size!=2 || directory || !p[1].endsWith(".json"))return false
                JWire.validId(p[1].removeSuffix(".json"));return true
            }
            if(p[0] !in setOf("active","history"))return false
            JWire.uuid(JsonPrimitive(p[1]))
            return when(p.size) {
                2->directory
                3->if(p[2] in setOf("old","stage"))directory else !directory && p[2] in setOf("intent.json","old-set.jsonl","new-set.jsonl","unrelated.jsonl","source.bin","seal.json","commit.json","receipt.json")
                4->p[2] in setOf("old","stage") && if(p[3]=="vectors")directory else !directory && (p[3]=="card.json" || p[2]=="old" && p[3]=="head.json")
                5->{if(p[2] !in setOf("old","stage") || p[3]!="vectors" || directory || !p[4].endsWith(".json"))false else {JWire.validId(p[4].removeSuffix(".json"));true}}
                else->false
            }
        }
        private fun validateAllUsed() {
            for(i in 0 until catalog.size)if(path(i).startsWith(domain) && !catalog.node(i).used) {
                val r=domain.relativize(path(i)).toString().replace('\\','/')
                val b=bundles.values.singleOrNull{r.startsWith(it.prefix+"/")}
                val vector=r.substringAfter("${b?.prefix}/")
                val unsupportedProof=b!=null && (b.intent==null || vector.startsWith("old/vectors/") && b.files["old-set.jsonl"]==null || vector.startsWith("stage/vectors/") && b.files["new-set.jsonl"]==null)
                jRequire(unsupportedProof && closedShape(r,catalog.node(i).stamp.directory))
            }
        }
        private inner class Bundle(val prefix:String,val tx:String,val history:Boolean) {
            var intent:JIntent?=null; var seal:JSeal?=null; var marker:JMarker?=null; var receipt:JReceipt?=null; var parent:JHead?=null
            val files=HashMap<String,Int>()
            var oldRows=emptyList<JManifestRow>(); var newRows=emptyList<JManifestRow>()
            var unrelated=emptyList<JManifestRow>()
            var oldDoc:DocumentInfo?=null;var newDoc:DocumentInfo?=null
            var oldShape:String?=null;var newShape:String?=null
            var plan:MutableMap<Pair<String,Map<String,String>>,Int>?=null
            fun file(name:String)=files[name]?.let(::evidence)
        }
        private suspend fun loadBundle(prefix:String,tx:String,history:Boolean):Bundle {
            val b=Bundle(prefix,tx,history)
            for(dir in listOf("","old","old/vectors","stage","stage/vectors"))requireDirectory(if(dir.isEmpty())prefix else "$prefix/$dir")
            val roles=mapOf("intent.json" to DesktopIndexMissingProof.INTENT,"old-set.jsonl" to DesktopIndexMissingProof.OLD_PAYLOAD,
                "new-set.jsonl" to DesktopIndexMissingProof.STAGE_PAYLOAD,"unrelated.jsonl" to DesktopIndexMissingProof.OLD_PAYLOAD,
                "source.bin" to DesktopIndexMissingProof.STAGE_PAYLOAD,"old/card.json" to DesktopIndexMissingProof.OLD_PAYLOAD,
                "stage/card.json" to DesktopIndexMissingProof.STAGE_PAYLOAD,"seal.json" to DesktopIndexMissingProof.SEAL)
            for((name,proof) in roles)requireFile("$prefix/$name",proof)?.let{b.files[name]=it}
            for(name in listOf("commit.json","receipt.json","old/head.json")) {
                val n=find("$prefix/$name"); if(n>=0){jRequire(!catalog.node(n).stamp.directory);catalog.used(n);b.files[name]=n}
            }
            if(history && b.files["receipt.json"]==null)missing+=DesktopIndexMissingProof.RECEIPT
            b.files["intent.json"]?.let { b.intent=JWire.intent(control(it),arena) }
            val intent=b.intent ?: return b
            jRequire(intent.txId==tx); claims(intent.root,intent.writer)
            for((name,max) in mapOf("old/card.json" to limits.entityBytes,"stage/card.json" to limits.entityBytes,"source.bin" to limits.sourceBytes)) {
                b.files[name]?.let { n -> val raw=bytes(n,max); when(name) {
                    "old/card.json" -> { same(evidence(n),intent.oldCard); val c=card(raw,intent.cardId); b.oldDoc=docSummary(c,intent,false);b.oldShape=cardShape(raw,intent.documentId,false) }
                    "stage/card.json" -> { same(evidence(n),intent.newCard); val c=card(raw,intent.cardId); b.newDoc=docSummary(c,intent,true);b.newShape=cardShape(raw,intent.documentId,true)
                        jRequire(bindings(intent.next).map{it.documentId}==c.customDocuments.map{it.id}.sorted())
                        if(intent.expectedHead==null && c.customDocuments.any{it.id!=intent.documentId && it.ragStatus=="INDEXED"})missing+=DesktopIndexMissingProof.LEGACY_INDEX_ORIGIN
                    }
                    else -> {
                        same(evidence(n),intent.source.file)
                        val content=try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString() }catch(_:Exception){jRefuse(DesktopIndexInspectionCode.CORRUPT)}
                        val doc=b.newDoc
                        if(doc!=null) {
                            try { DesktopDocumentRagSource(root).checkPlanBudget(content,doc.fileName) }
                            catch(_:DesktopDocumentRagSource.Rejected){jRefuse(DesktopIndexInspectionCode.BUDGET_EXCEEDED)}
                            val plan=ChunkingEngine().chunkDocument(content,doc.id,doc.fileName)
                            jRequire(plan.size.toLong()==intent.newSet.records)
                            b.plan=plan.groupingBy{it}.eachCount().toMutableMap()
                        }
                    }
                } }
            }
            b.oldRows=manifest(b,"old-set.jsonl",intent.oldSet,true)
            b.newRows=manifest(b,"new-set.jsonl",intent.newSet,true)
            val unrelated=manifest(b,"unrelated.jsonl",intent.unrelated,false); b.unrelated=unrelated
            for(row in b.oldRows)jRequire(!containsFolded(b.newRows,row.storageId) && !containsFolded(unrelated,row.storageId))
            for(row in b.newRows)jRequire(!containsFolded(unrelated,row.storageId))
            payloadVectors(b,b.oldRows,"old/vectors",false)
            payloadVectors(b,b.newRows,"stage/vectors",true)
            jRequire(b.plan==null || b.plan!!.isEmpty());b.plan=null
            if(intent.expectedHead==null)jRequire(b.files["old/head.json"]==null) else {
                b.files["old/head.json"]?.let{n->b.parent=JWire.head(control(n),arena);claims(b.parent!!.root,b.parent!!.writer,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT);same(evidence(n),intent.expectedHead.file)} ?: run{missing+=DesktopIndexMissingProof.PARENT_HEAD}
            }
            b.files["seal.json"]?.let { b.seal=JWire.seal(control(it)); checkSeal(b) }
            b.files["commit.json"]?.let { b.marker=JWire.marker(control(it),arena); checkMarker(b) }
            b.files["receipt.json"]?.let { b.receipt=JWire.receipt(control(it),arena); checkReceipt(b) }
            if(b.oldShape!=null && b.newShape!=null)checkCards(b)
            b.oldDoc=null;b.newDoc=null
            return b
        }

        private suspend fun manifest(b:Bundle,name:String,expected:JManifest,target:Boolean):List<JManifestRow> {
            val n=b.files[name] ?: return emptyList()
            val node=catalog.node(n); val max=if(target)524288 else 25600000
            jRequire(node.stamp.size<=max,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            reserveRead(n,false); handles++
            val list=JRows(arena,expected.records.toInt()); val line=ByteArray(limits.jsonlLineBytes-1); var used=0; var sum=0L; var previous=""
            val read=reader.read(path(n),node.stamp) { buffer,count ->
                readBytes=jAdd(readBytes,count.toLong(),Long.MAX_VALUE)
                for(i in 0 until count) {
                    val byte=buffer[i]
                    if(byte==10.toByte()) {
                        jRequire(used>0); rows=jAdd(rows,1,limits.manifestRecords)
                        val row=JWire.row(parse(line.copyOf(used))); used=0
                        jRequire(previous<row.storageId); previous=row.storageId
                        jRequire(list.size<(if(target)2048 else 100000),DesktopIndexInspectionCode.BUDGET_EXCEEDED)
                        list.append(row); sum=jAdd(sum,row.file.bytes,if(target)67108864 else 4294967296)
                    } else { jRequire(byte!=13.toByte());jRequire(used<line.size,DesktopIndexInspectionCode.BUDGET_EXCEEDED);line[used++]=byte }
                }
            }
            jRequire(used==0);catalog.read(n,read.file);same(read.file,expected.file)
            jRequire(list.size.toLong()==expected.records && sum==expected.payloadBytes)
            return list
        }
        private fun containsFolded(rows:List<JManifestRow>,id:String)=if(rows is JRows)rows.containsFolded(id) else rows.any{it.storageId.equals(id,true)}
        private fun row(rows:List<JManifestRow>,id:String):JManifestRow?=if(rows is JRows)rows.lookup(id) else rows.singleOrNull{it.storageId==id}
        private fun card(raw:ByteArray,id:String):CharacterCard {
            val o=parse(raw,true)
            jRequire(JWire.id(JWire.get(o,"id"))==id && o.containsKey("customDocuments"))
            val documentArray=JWire.get(o,"customDocuments") as? JsonArray ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
            jRequire(documentArray.size<=64,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            val c=try{JWire.json.decodeFromJsonElement(CharacterCard.serializer(),o)}catch(_:Exception){jRefuse(DesktopIndexInspectionCode.CORRUPT)}
            jRequire(c.customDocuments.size<=64 && c.ragIndexStatus in setOf("NOT_INDEXED","INDEXING","COMPLETE","FAILED") && c.ragIndexDone>=0 && c.ragIndexTotal>=0)
            val ids=c.customDocuments.map{it.id};ids.forEach(JWire::validId)
            jRequire(ids.map{it.lowercase(Locale.ROOT)}.distinct().size==ids.size)
            return c
        }
        private fun docSummary(card:CharacterCard,intent:JIntent,stage:Boolean):DocumentInfo {
            val doc=card.customDocuments.singleOrNull{it.id==intent.documentId} ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
            jRequire(doc.fileName.length<=256,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            jRequire(doc.filePath==intent.source.path)
            doc.contentHash?.let{JWire.sha(JsonPrimitive(it))};doc.indexedHash?.let{JWire.sha(JsonPrimitive(it))}
            jRequire(doc.ragStatus in setOf("PENDING","INDEXING","INDEXED","FAILED"))
            if(stage)jRequire(doc.ragError==null)
            // Only a bounded index summary survives this Entity's transient decoding workspace.
            // fileType and error are excluded here because their immutable/value checks use raw fields.
            return doc.copy(fileType="",ragError=null)
        }
        private suspend fun payloadVectors(b:Bundle,records:List<JManifestRow>,directory:String,new:Boolean) {
            val intent=b.intent!!
            for(row in records) {
                val n=requireFile("${b.prefix}/$directory/${row.storageId}.json",if(new)DesktopIndexMissingProof.STAGE_PAYLOAD else DesktopIndexMissingProof.OLD_PAYLOAD) ?: continue
                val raw=bytes(n,limits.entityBytes);same(evidence(n),row.file)
                val o=parse(raw,true)
                val v=try{JWire.json.decodeFromJsonElement(VectorChunk.serializer(),o)}catch(_:Exception){jRefuse(DesktopIndexInspectionCode.CORRUPT)}
                jRequire(v.id==row.storageId && v.sourceType==ChunkSourceType.DOCUMENT && v.sourceId==intent.cardId && v.messageId==null)
                jRequire(v.metadata.keys==setOf("documentId","sourceLabel","fileName","originalDocId","contentHash","embeddingKey") &&
                    v.metadata["documentId"]==intent.documentId && v.metadata["originalDocId"]==intent.documentId)
                jRequire(DesktopDocumentRagSource(root).validVector(v.embedding))
                JWire.sha(JsonPrimitive(v.metadata.getValue("embeddingKey")));JWire.sha(JsonPrimitive(v.metadata.getValue("contentHash")))
                val doc=if(new)b.newDoc else b.oldDoc
                if(doc!=null)jRequire(v.metadata["fileName"]==doc.fileName)
                if(new) {
                    jRequire(v.embedding.size==intent.config.dimensions && v.metadata["embeddingKey"]==intent.config.embeddingKey && v.metadata["contentHash"]==intent.source.sha256)
                    b.plan?.let { plan ->
                        val piece=v.content to mapOf("documentId" to v.metadata.getValue("documentId"),"sourceLabel" to v.metadata.getValue("sourceLabel"))
                        val count=plan[piece] ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
                        if(count==1)plan.remove(piece) else plan[piece]=count-1
                    }
                } else if(doc!=null)jRequire(v.metadata["contentHash"]==doc.indexedHash)
            }
        }
        private fun checkSeal(b:Bundle) {
            val i=b.intent!!;val s=b.seal!!;jRequire(s.txId==b.tx)
            b.file("intent.json")?.let{same(it,s.intent)}
            for((name,expected)in mapOf("old-set.jsonl" to s.oldManifest,"new-set.jsonl" to s.newManifest,"unrelated.jsonl" to s.unrelatedManifest,
                "old/card.json" to s.oldCard,"stage/card.json" to s.newCard,"source.bin" to s.source))b.file(name)?.let{same(it,expected)}
            jRequire(s.oldCard==i.oldCard && s.newCard==i.newCard && s.source==i.source.file && s.oldHead==i.expectedHead?.file)
        }
        private fun checkMarker(b:Bundle) {
            val m=b.marker!!;val i=b.intent!!;claims(m.root,m.writer);jRequire(m.txId==b.tx && m.predecessor==i.expectedHead);stateSame(m.next,i.next)
            b.file("intent.json")?.let{same(it,m.intent)};b.file("seal.json")?.let{same(it,m.seal)}
        }
        private fun checkReceipt(b:Bundle) {
            val r=b.receipt!!;val i=b.intent!!;claims(r.root,r.writer);jRequire(r.txId==b.tx && r.predecessor==i.expectedHead);stateSame(r.next,i.next)
            b.file("intent.json")?.let{same(it,r.intent)};b.file("seal.json")?.let{same(it,r.seal)};b.file("commit.json")?.let{same(it,r.commit)}
        }
        private fun checkCards(b:Bundle) {
            val i=b.intent!!;jRequire(b.oldShape==b.newShape)
            val targetOld=b.oldDoc ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
            val target=b.newDoc ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
            jRequire(target.copy(contentHash=targetOld.contentHash,indexedHash=targetOld.indexedHash,ragStatus=targetOld.ragStatus,ragChunkCount=targetOld.ragChunkCount,
                ragIndexedAt=targetOld.ragIndexedAt,ragError=targetOld.ragError)==targetOld)
            jRequire(target.ragStatus=="INDEXED" && target.indexedHash==i.source.sha256 && target.contentHash==i.source.sha256 &&
                target.ragChunkCount.toLong()==i.newSet.records && target.ragError==null && (target.ragIndexedAt?:0)>0 && target.filePath==i.source.path)
            val binding=bindings(i.next).single{it.documentId==i.documentId}
            jRequire(binding.producerTxId==i.txId && binding.source==i.source && binding.config==i.config &&
                binding.set==JSet(i.newSet.records,i.newSet.payloadBytes,i.newSet.file.sha256))
        }
        /** Logical equality fingerprint only; raw file evidence is separately checked above.
         * Removing ONLY the explicitly editable fields also preserves missing/default field existence.
         * Each Entity tree is discarded before the next payload is decoded.
         */
        private fun cardShape(raw:ByteArray,document:String,stage:Boolean):String {
            val o=parse(raw,true)
            val documents=JWire.get(o,"customDocuments") as? JsonArray ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
            val mutableDoc=setOf("contentHash","indexedHash","ragStatus","ragChunkCount","ragIndexedAt","ragError")
            var found=false
            val filtered=JsonObject(o.filterKeys{it !in setOf("updatedAt","ragIndexStatus","ragIndexDone","ragIndexTotal","ragIndexMessage","ragIndexedAt")}.mapValues{(k,v)->
                if(k!="customDocuments")v else JsonArray(documents.map { element ->
                    val doc=JWire.obj(element)
                    for(field in listOf("id","fileName","filePath","fileType","addedAt"))JWire.get(doc,field)
                    if(JWire.id(JWire.get(doc,"id"))!=document)doc else {
                        jRequire(!found);found=true
                        if(stage) {
                            mutableDoc.forEach{JWire.get(doc,it)}
                            JWire.long(JWire.get(doc,"ragIndexedAt"),min=1)
                        }
                        JsonObject(doc.filterKeys{it !in mutableDoc})
                    }
                })
            })
            jRequire(found)
            if(stage)JWire.long(JWire.get(o,"updatedAt"))
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            fun feed(e:JsonElement){when(e){
                is JsonObject->{digest.update('{'.code.toByte());for((k,v)in e.toSortedMap()){digest.update(JsonPrimitive(k).toString().toByteArray());digest.update(':'.code.toByte());feed(v);digest.update(','.code.toByte())};digest.update('}'.code.toByte())}
                is JsonArray->{digest.update('['.code.toByte());for(v in e){feed(v);digest.update(','.code.toByte())};digest.update(']'.code.toByte())}
                else->digest.update(e.toString().toByteArray())
            }}
            feed(filtered);return jHex(digest.digest())
        }
        private fun graph() {
            val graphError=DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT
            val reached=HashSet<String>()
            for((card,pair) in heads) {
                val h=pair.second;val b=bundles[h.txId] ?: jRefuse(graphError)
                jRequire(b.intent?.cardId==card && b.receipt!=null && b.marker!=null && b.seal!=null,graphError)
                claims(h.root,h.writer);stateSame(h.state,b.intent!!.next,graphError)
                jRequire(h.predecessor==b.intent!!.expectedHead,graphError)
                same(b.file("intent.json")!!,h.intent);same(b.file("seal.json")!!,h.seal);same(b.file("commit.json")!!,h.commit);same(b.file("receipt.json")!!,h.receipt)
                var current:Bundle?=b
                while(current!=null) {
                    val c=current;val i=c.intent ?: jRefuse(graphError)
                    jRequire(reached.add(c.tx),graphError)
                    val link=i.expectedHead
                    if(link==null){jRequire(i.next.revision==1L,graphError);if(i.oldSet.records>0)missing+=DesktopIndexMissingProof.LEGACY_INDEX_ORIGIN;current=null}
                    else {
                        val p=bundles[link.txId] ?: jRefuse(graphError)
                        jRequire(p.history && p.intent?.cardId==card && link.revision<Long.MAX_VALUE && i.next.revision==link.revision+1,graphError)
                        val parent=c.parent
                        if(parent==null){if(c.history)jRefuse(graphError)} else {
                            jRequire(parent.txId==p.tx && parent.state.revision==link.revision && parent.state.card==i.oldCard,graphError)
                            stateSame(parent.state,p.intent!!.next,graphError)
                            jRequire(parent.predecessor==p.intent!!.expectedHead && parent.intent==p.file("intent.json") && parent.seal==p.file("seal.json") &&
                                parent.commit==p.file("commit.json") && parent.receipt==p.file("receipt.json"),graphError)
                        }
                        current=p
                    }
                }
            }
            for(b in bundles.values) {
                if(b.history)jRequire(b.tx in reached,graphError)
                val i=b.intent ?: continue
                val parent=b.parent
                if(i.expectedHead!=null && parent==null && b.history)jRefuse(graphError)
                if(i.expectedHead!=null && parent!=null) {
                    jRequire(parent.txId==i.expectedHead.txId && parent.state.cardId==i.cardId && parent.state.revision==i.expectedHead.revision,graphError)
                    val before=bindings(parent.state).associateBy{it.documentId};val after=bindings(i.next).associateBy{it.documentId}
                    for((id,bind)in after)if(id!=i.documentId)jRequire(before[id]==bind,graphError)
                    val old=before[i.documentId];jRequire(after[i.documentId]!!.generation==jAdd(old?.generation?:0,1,Long.MAX_VALUE),graphError)
                    if(old!=null && old.generation>0) {
                        val producer=bundles[old.producerTxId] ?: jRefuse(graphError)
                        jRequire(b.oldRows==producer.newRows,graphError)
                    } else jRequire(b.oldRows.isEmpty(),graphError)
                } else if(i.expectedHead==null) {
                    jRequire(i.next.revision==1L && bindings(i.next).single{it.documentId==i.documentId}.generation==1L)
                    if(i.oldSet.records>0)missing+=DesktopIndexMissingProof.LEGACY_INDEX_ORIGIN
                }
                for(binding in bindings(i.next))if(binding.generation>0) {
                    val p=bundles[binding.producerTxId] ?: jRefuse(graphError)
                    val pi=p.intent ?: jRefuse(graphError)
                    jRequire(pi.cardId==i.cardId && pi.documentId==binding.documentId &&
                        bindings(pi.next).single{it.documentId==binding.documentId}==binding,graphError)
                }
            }
        }
        private suspend fun liveCheck() {
            if(bundles.isEmpty() || bundles.values.any{it.intent==null})return
            val active=bundles.values.singleOrNull{!it.history}
            val entities=child(root,"entities") ?: jRefuse(DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
            observe(entities,true)
            val cards=child(entities,"character_cards")
            val vectors=child(entities,"vector_chunks")
            val current=LinkedHashMap<String,Bundle>()
            heads.forEach { (card,h) -> current[card]=bundles.getValue(h.second.txId) }
            if(active?.intent!=null)current[active.intent!!.cardId]=active
            if(cards!=null) {
                liveDirectory(cards)
                enumerate(cards,false) { file ->
                    val name=file.fileName.toString();jRequire(name.endsWith(".json"));JWire.validId(name.removeSuffix(".json"))
                    liveObserve(file)
                }
            }
            var oldSeen=false;var newSeen=false
            for((card,b)in current) {
                val i=b.intent!!
                val n=cards?.let{catalog.find(key(it.resolve("$card.json")))} ?: -1
                jRequire(n>=0,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                val raw=bytes(n,limits.entityBytes,true);card(raw,card)
                val e=evidence(n)
                if(b===active){jRequire(e==i.oldCard || e==i.newCard,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT);oldSeen=oldSeen || e==i.oldCard;newSeen=newSeen || e==i.newCard}
                else jRequire(e==i.newCard,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                val bound=if(b===active && e==i.oldCard)b.parent?.state?.let(::bindings).orEmpty() else bindings(i.next)
                val sources=bound.filter{it.generation>0}.mapNotNull{it.source}.toMutableList()
                if(b===active)sources+=i.source
                for(source in sources.distinct()) {
                    val directory=child(root,"documents") ?: jRefuse(DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                    liveDirectory(directory)
                    val p=child(directory,source.path.substringAfter('/')) ?: jRefuse(DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                    val index=liveObserve(p)
                    if(catalog.node(index).reads==0)bytes(index,limits.sourceBytes,true)
                    jRequire(evidence(index)==source.file,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                }
            }
            if(cards!=null)for(n in 0 until catalog.size)if(path(n).parent==cards && catalog.node(n).reads==0) {
                card(bytes(n,limits.entityBytes,true),path(n).fileName.toString().removeSuffix(".json"))
            }
            if(vectors==null) {
                jRequire(active!=null,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
            } else {
                liveDirectory(vectors)
                // Reserve aggregate double-read before decoding any live Entity.
                var unique=0L;var files=0L
                enumerate(vectors,false) { p ->
                    val name=p.fileName.toString();jRequire(name.endsWith(".json"));JWire.validId(name.removeSuffix(".json"))
                    val n=liveObserve(p);jRequire(!catalog.node(n).stamp.directory,DesktopIndexInspectionCode.UNSAFE_PATH)
                    unique=jAdd(unique,catalog.node(n).stamp.size,limits.liveBytes);files=jAdd(files,1,limits.liveFiles)
                }
                jAdd(Math.multiplyExact(unique,2),Math.multiplyExact(files,2),limits.liveReadBytes)
                for(n in 0 until catalog.size)if(path(n).parent==vectors) {
                    val raw=bytes(n,limits.entityBytes,true);val o=parse(raw,true)
                    val v=try{JWire.json.decodeFromJsonElement(VectorChunk.serializer(),o)}catch(_:Exception){jRefuse(DesktopIndexInspectionCode.CORRUPT)}
                    jRequire(v.id==path(n).fileName.toString().removeSuffix(".json") && DesktopDocumentRagSource(root).validVector(v.embedding))
                    val owner=current[v.sourceId]
                    if(owner!=null && v.sourceType==ChunkSourceType.DOCUMENT) {
                        val matches=ArrayList<JManifestRow>()
                        for(binding in bindings(owner.intent!!.next))if(binding.generation>0) {
                            val producer=bundles[binding.producerTxId] ?: jRefuse(DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                            row(producer.newRows,v.id)?.let(matches::add)
                        }
                        if(owner===active)row(owner.oldRows,v.id)?.let(matches::add)
                        jRequire(matches.any{it.file==evidence(n)},DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                        if(owner===active) {
                            oldSeen=oldSeen || row(owner.oldRows,v.id)?.file==evidence(n)
                            newSeen=newSeen || row(owner.newRows,v.id)?.file==evidence(n)
                        }
                    } else {
                        val known=current.values.mapNotNull{row(it.unrelated,v.id)}
                        jRequire(known.any{it.file==evidence(n)},DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                    }
                }
                for(b in current.values)if(b!==active)for(binding in bindings(b.intent!!.next))if(binding.generation>0) {
                    for(row in bundles.getValue(binding.producerTxId!!).newRows) {
                        val n=catalog.find(key(vectors.resolve("${row.storageId}.json")))
                        jRequire(n>=0 && catalog.node(n).file==row.file,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                    }
                }
                for(b in current.values)for(record in b.unrelated) {
                    // Historical unrelated bytes may be superseded ONLY by a graph-explained later target.
                    val superseded=bundles.values.any{it.history && row(it.oldRows,record.storageId)?.file==record.file}
                    if(!superseded) {
                        val n=catalog.find(key(vectors.resolve("${record.storageId}.json")))
                        jRequire(n>=0 && catalog.node(n).file==record.file,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT)
                    }
                }
            }
            live=if(active==null)DesktopIndexObservedLive.EXPECTED_NEW else when {oldSeen && newSeen->DesktopIndexObservedLive.KNOWN_MIXTURE;newSeen->DesktopIndexObservedLive.EXPECTED_NEW;else->DesktopIndexObservedLive.EXPECTED_OLD}
        }
        private suspend fun liveObserve(path:Path):Int {
            val previous=catalog.find(key(path));val index=observe(path,false)
            if(previous<0){
                liveEntries=jAdd(liveEntries,1,limits.liveEntries);liveFileCount=jAdd(liveFileCount,1,limits.liveFiles)
                liveBytes=jAdd(liveBytes,catalog.node(index).stamp.size,limits.liveBytes)
                jAdd(Math.multiplyExact(liveBytes,2),Math.multiplyExact(liveFileCount,2),limits.liveReadBytes)
            }
            return index
        }
        private suspend fun liveDirectory(path:Path) {
            val previous=catalog.find(key(path));observe(path,true)
            if(previous<0)liveEntries=jAdd(liveEntries,1,limits.liveEntries)
        }
        private suspend fun finish() {
            phase=DesktopIndexInspectionPhase.FINAL_RECHECK;onStep(Step.BEFORE_FINAL_RECHECK)
            val count=catalog.size
            for(i in 0 until count) {
                val node=catalog.node(i)
                recheckStamp(i,reader.observe(path(i)))
                if(node.reads>0) {
                    reserveRead(i,!path(i).startsWith(domain));handles++
                    val actual=reader.read(path(i),node.stamp){_,n->readBytes=jAdd(readBytes,n.toLong(),Long.MAX_VALUE)}
                    jRequire(actual.file==node.file,DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
                    catalog.read(i,actual.file)
                }
            }
            for(path in absent) {operation();jRequire(Files.notExists(path,NOFOLLOW_LINKS),DesktopIndexInspectionCode.CHANGED_DURING_CHECK)}
            owner()
        }
        private fun classify():DesktopIndexInspectionCode {
            if(domainRoot==null || missing.any{it in setOf(DesktopIndexMissingProof.DOMAIN_HEADER,DesktopIndexMissingProof.FIXED_DIRECTORY,
                    DesktopIndexMissingProof.INTENT,DesktopIndexMissingProof.OLD_PAYLOAD,DesktopIndexMissingProof.STAGE_PAYLOAD,DesktopIndexMissingProof.SEAL,DesktopIndexMissingProof.PARENT_HEAD,DesktopIndexMissingProof.LEGACY_INDEX_ORIGIN)})return DesktopIndexInspectionCode.INCOMPLETE_EVIDENCE
            val active=bundles.values.singleOrNull{!it.history}
            if(active!=null)return if(active.marker==null)DesktopIndexInspectionCode.SEALED_PENDING else DesktopIndexInspectionCode.MARKER_WITHOUT_HANDOFF
            if(bundles.isEmpty() || heads.isEmpty())return DesktopIndexInspectionCode.INCOMPLETE_EVIDENCE
            return DesktopIndexInspectionCode.COMPLETED_STRUCTURE_ONLY
        }
        private fun result(code:DesktopIndexInspectionCode):DesktopIndexInspectionResult {
            if(phase==DesktopIndexInspectionPhase.LIVE) {
                missing+=DesktopIndexMissingProof.LIVE_APPLICABILITY
                if(code in setOf(DesktopIndexInspectionCode.CORRUPT,DesktopIndexInspectionCode.HEAD_HISTORY_INCONSISTENT,DesktopIndexInspectionCode.CHANGED_DURING_CHECK))live=DesktopIndexObservedLive.MISMATCH
            }
            return DesktopIndexInspectionResult(code,phase,presence,missing.toSet(),live,
                DesktopIndexInspectionCounters(domainEntries,locatorEntries,liveEntries,domainBytes,liveBytes,domainRead,liveRead,readBytes,operations,rows,arena.reserved,handles))
        }
    }
}

/** Fixed primitive indexes + packed path/stamp/hash records. No retained per-file Path/Entity/JSON map. */
private class JCatalog(private val arena:JReadArena,maximum:Int) {
    data class Node(val key:String,val stamp:DesktopDocumentIndexReadEvidence.Stamp,val reads:Int,val file:JFileEvidence?,val used:Boolean)
    private val offsets:IntArray;private val lengths:IntArray;private val paths:IntArray;private val identities:IntArray
    var size=0;private set
    init {
        var capacity=1;while(capacity<maximum*2)capacity=Math.multiplyExact(capacity,2)
        arena.charge(4096+maximum.toLong()*8+capacity.toLong()*8)
        offsets=IntArray(maximum);lengths=IntArray(maximum);paths=IntArray(capacity){-1};identities=IntArray(capacity){-1}
    }
    fun node(index:Int):Node {
        jRequire(index in 0 until size)
        val b=ByteBuffer.wrap(arena.bytes(JFrame(offsets[index],lengths[index])))
        val length=b.int;val name=ByteArray(length);b.get(name)
        val volume=b.long;val id=ByteArray(16);b.get(id)
        val created=b.long;val modified=b.long;val changed=b.long;val bytes=b.long;val links=b.long
        val directory=b.get().toInt()!=0;val reads=b.get().toInt();val used=b.get().toInt()!=0;val complete=b.get().toInt()!=0
        val hash=ByteArray(32);b.get(hash)
        return Node(String(name,Charsets.UTF_8),DesktopDocumentIndexReadEvidence.Stamp(volume,jHex(id),created,modified,changed,bytes,links,directory),
            reads,if(complete)JFileEvidence(bytes,jHex(hash)) else null,used)
    }
    fun find(key:String):Int {
        var slot=key.lowercase(Locale.ROOT).hashCode() and (paths.size-1)
        while(paths[slot]>=0) {
            val index=paths[slot];val stored=node(index).key
            if(stored.equals(key,true)){jRequire(stored==key,DesktopIndexInspectionCode.UNSAFE_PATH);return index}
            slot=(slot+1) and (paths.size-1)
        }
        return -1
    }
    fun add(key:String,stamp:DesktopDocumentIndexReadEvidence.Stamp):Int {
        jRequire(size<offsets.size,DesktopIndexInspectionCode.BUDGET_EXCEEDED)
        var identity=(stamp.volume.hashCode()*31+stamp.id.hashCode()) and (identities.size-1)
        while(identities[identity]>=0) {
            val old=node(identities[identity]);jRequire(old.stamp.volume!=stamp.volume || old.stamp.id!=stamp.id,DesktopIndexInspectionCode.UNSAFE_PATH)
            identity=(identity+1) and (identities.size-1)
        }
        val index=size++;val bytes=encode(Node(key,stamp,0,null,false));val frame=arena.put(bytes);offsets[index]=frame.offset;lengths[index]=frame.length
        var slot=key.lowercase(Locale.ROOT).hashCode() and (paths.size-1);while(paths[slot]>=0)slot=(slot+1) and (paths.size-1)
        paths[slot]=index;identities[identity]=index;return index
    }
    fun attempt(index:Int)=replace(index,node(index).let{it.copy(reads=it.reads+1)})
    fun read(index:Int,file:JFileEvidence)=replace(index,node(index).copy(file=file))
    fun used(index:Int){if(index>=0)replace(index,node(index).copy(used=true))}
    private fun replace(index:Int,node:Node)=arena.replace(JFrame(offsets[index],lengths[index]),encode(node))
    private fun encode(node:Node):ByteArray {
        val key=node.key.toByteArray(Charsets.UTF_8);val b=ByteBuffer.allocate(4+key.size+8+16+8*5+4+32)
        b.putInt(key.size).put(key).putLong(node.stamp.volume).put(hex(node.stamp.id))
        b.putLong(node.stamp.created).putLong(node.stamp.modified).putLong(node.stamp.changed).putLong(node.stamp.size).putLong(node.stamp.links)
        b.put(if(node.stamp.directory)1 else 0).put(node.reads.toByte()).put(if(node.used)1 else 0).put(if(node.file!=null)1 else 0)
        b.put(node.file?.sha256?.let(::hex)?:ByteArray(32));return b.array()
    }
    private fun hex(text:String)=ByteArray(text.length/2){text.substring(it*2,it*2+2).toInt(16).toByte()}
}

/** JSONL rows remain packed; the only per-row retained indexes are primitive integers. */
private class JRows(private val arena:JReadArena,maximum:Int):AbstractList<JManifestRow>() {
    private val offsets:IntArray;private val lengths:IntArray;private val folded:IntArray
    override var size=0;private set
    init {
        var capacity=1;while(capacity<maximum*2)capacity=Math.multiplyExact(capacity,2)
        arena.charge(128+maximum.toLong()*8+capacity.toLong()*4)
        offsets=IntArray(maximum);lengths=IntArray(maximum);folded=IntArray(capacity){-1}
    }
    override fun get(index:Int):JManifestRow {
        val b=ByteBuffer.wrap(arena.bytes(JFrame(offsets[index],lengths[index])))
        val length=b.int;val id=ByteArray(length);b.get(id);val bytes=b.long;val hash=ByteArray(32);b.get(hash)
        return JManifestRow(String(id,Charsets.US_ASCII),JFileEvidence(bytes,jHex(hash)))
    }
    fun containsFolded(id:String):Boolean {
        var slot=id.lowercase(Locale.ROOT).hashCode() and (folded.size-1)
        while(folded[slot]>=0){if(get(folded[slot]).storageId.equals(id,true))return true;slot=(slot+1) and (folded.size-1)}
        return false
    }
    fun lookup(id:String):JManifestRow? {
        var low=0;var high=size-1
        while(low<=high){val mid=(low+high) ushr 1;val value=get(mid);val order=value.storageId.compareTo(id)
            if(order==0)return value
            if(order<0)low=mid+1 else high=mid-1
        }
        return null
    }
    fun append(row:JManifestRow) {
        jRequire(size<offsets.size && !containsFolded(row.storageId))
        val id=row.storageId.toByteArray(Charsets.US_ASCII);val buffer=ByteBuffer.allocate(4+id.size+8+32)
        buffer.putInt(id.size).put(id).putLong(row.file.bytes).put(ByteArray(32){row.file.sha256.substring(it*2,it*2+2).toInt(16).toByte()})
        val frame=arena.put(buffer.array());offsets[size]=frame.offset;lengths[size]=frame.length
        var slot=row.storageId.lowercase(Locale.ROOT).hashCode() and (folded.size-1);while(folded[slot]>=0)slot=(slot+1) and (folded.size-1)
        folded[slot]=size++
    }
}
