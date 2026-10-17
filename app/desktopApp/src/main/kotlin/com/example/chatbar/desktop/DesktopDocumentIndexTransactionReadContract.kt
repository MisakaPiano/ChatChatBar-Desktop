package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.*

internal enum class DesktopIndexInspectionCode {
    NO_DOMAIN, OWNERSHIP_MISSING, UNSUPPORTED_VERSION, INCOMPLETE_EVIDENCE,
    SEALED_PENDING, MARKER_WITHOUT_HANDOFF, HEAD_HISTORY_INCONSISTENT,
    COMPLETED_STRUCTURE_ONLY, CORRUPT, UNSAFE_PATH, BUDGET_EXCEEDED, READ_ERROR,
    CHANGED_DURING_CHECK,
}
internal enum class DesktopIndexInspectionPhase { ROOT, HEADER, INVENTORY, PAYLOAD, GRAPH, LIVE, FINAL_RECHECK }
internal enum class DesktopIndexDomainPresence { ABSENT, PRESENT, UNDETERMINED }
internal enum class DesktopIndexBusinessAdmission { BLOCKED }
internal enum class DesktopIndexMissingProof {
    ROOT_LINEAGE_AUTHORITY, CONFIG_WRITER_AUTHORITY, WRITER_HANDOFF, READINESS_CACHE_EPOCH,
    DOMAIN_HEADER, FIXED_DIRECTORY, INTENT, OLD_PAYLOAD, STAGE_PAYLOAD, SEAL,
    PARENT_HEAD, COMMIT_MARKER, RECEIPT, CURRENT_HEAD, LEGACY_INDEX_ORIGIN,
    LIVE_APPLICABILITY, WINDOWS_LINK_IDENTITY,
}
internal enum class DesktopIndexObservedLive { NOT_CHECKED, EXPECTED_OLD, EXPECTED_NEW, KNOWN_MIXTURE, MISMATCH }
internal data class DesktopIndexInspectionCounters(
    val domainEntries: Long = 0, val locatorEntries: Long = 0, val liveEntries: Long = 0,
    val domainBytes: Long = 0, val liveBytes: Long = 0,
    val domainReadReservations: Long = 0, val liveReadReservations: Long = 0,
    val bytesRead: Long = 0, val metadataOperations: Long = 0,
    val jsonlRecords: Long = 0, val arenaReservedBytes: Long = 0, val openedDataHandles: Long = 0,
)
internal data class DesktopIndexInspectionResult(
    val code: DesktopIndexInspectionCode,
    val phase: DesktopIndexInspectionPhase,
    val domainPresence: DesktopIndexDomainPresence,
    val missingProofs: Set<DesktopIndexMissingProof>,
    val observedLive: DesktopIndexObservedLive,
    val counters: DesktopIndexInspectionCounters,
) {
    val businessAdmission: DesktopIndexBusinessAdmission = DesktopIndexBusinessAdmission.BLOCKED
}

/** Test seams may only reduce the reviewed profile. Counts include failed/repeated operations. */
internal data class DesktopIndexWireLimits(
    val domainEntries: Long = 16_384, val locatorEntries: Long = 4096,
    val liveEntries: Long = 100_256, val liveFiles: Long = 100_000,
    val totalPaths: Int = 120_000, val depth: Int = 8,
    val domainBytes: Long = 512L * 1024 * 1024, val liveBytes: Long = 4L * 1024 * 1024 * 1024,
    val domainReadBytes: Long = 1_073_774_592, val liveReadBytes: Long = 4L * 1024 * 1024 * 1024,
    val controlBytes: Int = 65_536, val entityBytes: Int = 8 * 1024 * 1024,
    val sourceBytes: Int = 1024 * 1024, val jsonlLineBytes: Int = 256,
    val manifestRecords: Long = 262_144, val metadataOperations: Long = 1_000_000,
    val arenaBytes: Int = 64 * 1024 * 1024,
    val domainNameUnits: Long = 1_048_576, val domainPathUnits: Long = 6_291_456,
    val locatorNameUnits: Long = 1_048_576, val locatorPathUnits: Long = 1_572_864,
) {
    init {
        require(domainEntries in 1..16_384 && locatorEntries in 1..4096 && liveEntries in 1..100_256 && liveFiles in 1..100_000)
        require(totalPaths in 1..120_000 && depth in 0..8)
        require(domainBytes in 1..536_870_912 && liveBytes in 1..4_294_967_296)
        require(domainReadBytes in 1..1_073_774_592 && liveReadBytes in 1..4_294_967_296)
        require(controlBytes in 1..65_536 && entityBytes in 1..8_388_608 && sourceBytes in 1..1_048_576)
        require(jsonlLineBytes in 1..256 && manifestRecords in 1..262_144 && metadataOperations in 1..1_000_000)
        require(arenaBytes in 1..67_108_864 && domainNameUnits in 1..1_048_576 && domainPathUnits in 1..6_291_456)
        require(locatorNameUnits in 1..1_048_576 && locatorPathUnits in 1..1_572_864)
    }
}
internal class JReadRefusal(val code: DesktopIndexInspectionCode) : Exception("Read-only transaction inspection refused")
internal fun jRefuse(code: DesktopIndexInspectionCode): Nothing = throw JReadRefusal(code)
internal fun jRequire(ok: Boolean, code: DesktopIndexInspectionCode = DesktopIndexInspectionCode.CORRUPT) {
    if (!ok) jRefuse(code)
}
internal fun jAdd(a: Long, b: Long, max: Long): Long {
    if (b < 0 || a < 0) jRefuse(DesktopIndexInspectionCode.BUDGET_EXCEEDED)
    val result = try { Math.addExact(a, b) } catch (_: ArithmeticException) { jRefuse(DesktopIndexInspectionCode.BUDGET_EXCEEDED) }
    jRequire(result <= max, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
    return result
}
internal fun jHex(bytes:ByteArray):String {
    val digits="0123456789abcdef";val result=CharArray(bytes.size*2)
    for(i in bytes.indices){val n=bytes[i].toInt() and 255;result[i*2]=digits[n ushr 4];result[i*2+1]=digits[n and 15]}
    return String(result)
}
internal fun jSha(bytes: ByteArray): String = jHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal data class JFileEvidence(val bytes: Long, val sha256: String)
internal data class JRoot(val lineageId: String, val epoch: Long)
internal data class JWriter(val authorityId: String, val epoch: Long)
internal data class JConfig(val store: String, val id: String, val generation: String, val revision: Long,
    val publicFingerprint: String, val credentialGeneration: String, val dimensions: Int, val embeddingKey: String)
internal data class JSource(val path: String, val bytes: Long, val sha256: String) {
    val file get() = JFileEvidence(bytes, sha256)
}
internal data class JLink(val txId: String, val revision: Long, val file: JFileEvidence)
internal data class JSet(val records: Long, val payloadBytes: Long, val rowsSha256: String)
internal data class JManifest(val file: JFileEvidence, val records: Long, val payloadBytes: Long)
internal data class JBinding(val documentId: String, val generation: Long, val producerTxId: String?,
    val source: JSource?, val config: JConfig?, val set: JSet?)
internal data class JFrame(val offset: Int, val length: Int)
internal data class JState(val cardId: String, val revision: Long, val card: JFileEvidence, val canonical: JFrame)
internal data class JIntent(val txId: String, val taskId: String, val root: JRoot, val writer: JWriter,
    val cardId: String, val documentId: String, val source: JSource, val config: JConfig,
    val expectedHead: JLink?, val oldCard: JFileEvidence, val newCard: JFileEvidence,
    val oldSet: JManifest, val newSet: JManifest, val unrelated: JManifest, val next: JState)
internal data class JSeal(val txId: String, val intent: JFileEvidence, val oldManifest: JFileEvidence,
    val newManifest: JFileEvidence, val unrelatedManifest: JFileEvidence, val oldCard: JFileEvidence,
    val newCard: JFileEvidence, val source: JFileEvidence, val oldHead: JFileEvidence?)
internal data class JMarker(val txId: String, val root: JRoot, val writer: JWriter,
    val intent: JFileEvidence, val seal: JFileEvidence, val predecessor: JLink?, val next: JState)
internal data class JReceipt(val txId: String, val root: JRoot, val writer: JWriter,
    val intent: JFileEvidence, val seal: JFileEvidence, val commit: JFileEvidence,
    val predecessor: JLink?, val next: JState)
internal data class JHead(val txId: String, val root: JRoot, val writer: JWriter, val intent: JFileEvidence,
    val seal: JFileEvidence, val commit: JFileEvidence, val receipt: JFileEvidence,
    val predecessor: JLink?, val state: JState)
internal data class JManifestRow(val storageId: String, val file: JFileEvidence)

/** Packed bytes, not a collection of retained JSON/Entity trees. Padding and capacity are charged. */
internal class JReadArena(private val maximum: Int) {
    private val blocks = ArrayList<ByteArray>()
    private val blockSize = 1024 * 1024
    var reserved: Long = 0; private set
    private var position = 0
    fun charge(bytes: Long) { reserved = jAdd(reserved, bytes, maximum.toLong()) }
    fun put(bytes: ByteArray): JFrame {
        if (blocks.isEmpty() || blocks.last().size - position < bytes.size) {
            val available = maximum.toLong() - reserved - 32
            val size = minOf(blockSize.toLong(), available).toInt()
            jRequire(bytes.size <= size, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
            charge(size.toLong()+32); blocks += ByteArray(size); position = 0
        }
        val offset = (blocks.size - 1) * blockSize + position
        bytes.copyInto(blocks.last(), position); position += bytes.size
        return JFrame(offset, bytes.size)
    }
    fun bytes(frame: JFrame): ByteArray = blocks[frame.offset / blockSize]
        .copyOfRange(frame.offset % blockSize, frame.offset % blockSize + frame.length)
    fun equal(a: JFrame, b: JFrame): Boolean {
        if (a.length != b.length) return false
        val aa = blocks[a.offset / blockSize]; val bb = blocks[b.offset / blockSize]
        for (i in 0 until a.length) if (aa[a.offset % blockSize + i] != bb[b.offset % blockSize + i]) return false
        return true
    }
    fun replace(frame: JFrame, bytes: ByteArray) {
        jRequire(bytes.size == frame.length)
        bytes.copyInto(blocks[frame.offset / blockSize], frame.offset % blockSize)
    }
}

/** Lexical pass detects duplicate keys BEFORE kotlinx JsonObject could discard them. */
internal class JStrictJson(private val text: String, private val entity: Boolean) {
    private var at = 0
    private val objectMaximum = if (entity) 256 else 32
    private val arrayMaximum = if (entity) 65_536 else 4096
    private val stringMaximum = if (entity) 8_388_608 else 2048
    fun validate() {
        jRequire(!text.startsWith('\uFEFF')); space(); value(0); space(); jRequire(at == text.length)
    }
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
    private fun expect(c: Char) { jRequire(take(c)) }
    private fun value(depth: Int) {
        jRequire(depth <= 64, DesktopIndexInspectionCode.BUDGET_EXCEEDED); space(); jRequire(at < text.length)
        when (text[at]) {
            '{' -> {
                at++; val keys = HashSet<String>(); val folded = HashSet<String>()
                if (!take('}')) do {
                    space(); jRequire(at < text.length && text[at] == '"')
                    val key = string(true)!!
                    jRequire(keys.size < objectMaximum, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
                    val asciiFold=buildString(key.length){for(c in key)append(if(c in 'A'..'Z')c.lowercaseChar() else c)}
                    jRequire(keys.add(key) && folded.add(asciiFold))
                    expect(':'); value(depth + 1)
                    if (take('}')) return
                    expect(',')
                } while (true)
            }
            '[' -> {
                at++; var count = 0
                if (!take(']')) do {
                    jRequire(++count <= arrayMaximum, DesktopIndexInspectionCode.BUDGET_EXCEEDED)
                    value(depth + 1)
                    if (take(']')) return
                    expect(',')
                } while (true)
            }
            '"' -> string(false)
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }
    private fun literal(s: String) { jRequire(text.startsWith(s, at)); at += s.length }
    private fun number() {
        val start = at
        if (at < text.length && text[at] == '-') at++
        jRequire(at < text.length)
        if (text[at] == '0') at++ else { jRequire(text[at] in '1'..'9'); while (at < text.length && text[at] in '0'..'9') at++ }
        if (at < text.length && text[at] == '.') { at++; digits() }
        if (at < text.length && text[at] in "eE") { at++; if (at < text.length && text[at] in "+-") at++; digits() }
        jRequire(at > start)
    }
    private fun digits() { val start = at; while (at < text.length && text[at] in '0'..'9') at++; jRequire(at > start) }
    private fun hex(): Char {
        jRequire(at + 4 <= text.length)
        val digits=text.substring(at,at+4)
        jRequire(digits.all{it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'})
        val value = digits.toIntOrNull(16); jRequire(value != null)
        at += 4; return value!!.toChar()
    }
    private fun string(key: Boolean): String? {
        jRequire(text[at++] == '"'); val result = if (key) StringBuilder() else null; var bytes = 0L
        while (at < text.length) {
            var c = text[at++]
            if (c == '"') return result?.toString()
            jRequire(c.code >= 32)
            if (c == '\\') {
                jRequire(at < text.length)
                c = when (val escape = text[at++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> hex()
                    else -> jRefuse(DesktopIndexInspectionCode.CORRUPT)
                }
                if (c.isHighSurrogate()) {
                    jRequire(at + 2 <= text.length && text[at++] == '\\' && text[at++] == 'u')
                    val low = hex(); jRequire(low.isLowSurrogate()); result?.append(c)?.append(low); bytes += 4
                } else { jRequire(!c.isLowSurrogate()); result?.append(c); bytes += utf8(c) }
            } else if (c.isHighSurrogate()) {
                jRequire(at < text.length && text[at].isLowSurrogate()); result?.append(c)?.append(text[at++]); bytes += 4
            } else { jRequire(!c.isLowSurrogate()); result?.append(c); bytes += utf8(c) }
            jRequire(bytes <= (if (key) 2048 else stringMaximum), DesktopIndexInspectionCode.BUDGET_EXCEEDED)
        }
        jRefuse(DesktopIndexInspectionCode.CORRUPT)
    }
    private fun utf8(c: Char): Int = if (c.code < 128) 1 else if (c.code < 2048) 2 else 3
}

internal object JWire {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val id = Regex("[A-Za-z0-9_-]{1,80}")
    private val hash = Regex("[0-9a-f]{64}")
    fun parse(bytes: ByteArray, entity: Boolean = false): JsonObject {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        JStrictJson(text, entity).validate()
        return json.parseToJsonElement(text) as? JsonObject ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
    }
    fun fields(o: JsonObject, vararg names: String) { jRequire(o.keys == names.toSet()) }
    fun obj(e: JsonElement): JsonObject = e as? JsonObject ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
    fun text(e: JsonElement): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
    fun long(e: JsonElement, max: Long = Long.MAX_VALUE, min: Long = 0): Long {
        val p = e as? JsonPrimitive ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
        jRequire(!p.isString && p.content.matches(Regex("0|[1-9][0-9]*")))
        val n = p.content.toLongOrNull() ?: jRefuse(DesktopIndexInspectionCode.BUDGET_EXCEEDED)
        jRequire(n in min..max); return n
    }
    fun get(o: JsonObject, key: String): JsonElement = o[key] ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
    fun uuid(e: JsonElement): String = text(e).also { jRequire(uuid.matches(it) && it != "00000000-0000-0000-0000-000000000000") }
    fun id(e: JsonElement): String = text(e).also { validId(it) }
    fun validId(value: String) { jRequire(id.matches(value) && DesktopIndexStartupPreflight.safeWindowsName(value + ".json")) }
    fun sha(e: JsonElement): String = text(e).also { jRequire(hash.matches(it)) }
    fun evidence(e: JsonElement, maximum: Long = Long.MAX_VALUE): JFileEvidence {
        val o = obj(e); fields(o, "bytes", "sha256")
        return JFileEvidence(long(get(o, "bytes"), maximum), sha(get(o, "sha256")))
    }
    fun root(e: JsonElement): JRoot { val o = obj(e); fields(o, "lineageId", "epoch"); return JRoot(uuid(get(o,"lineageId")), long(get(o,"epoch"), min=1)) }
    fun writer(e: JsonElement): JWriter { val o=obj(e); fields(o,"authorityId","epoch"); return JWriter(uuid(get(o,"authorityId")),long(get(o,"epoch"),min=1)) }
    fun source(e: JsonElement): JSource {
        val o=obj(e); fields(o,"path","bytes","sha256"); val path=text(get(o,"path")); val parts=path.split('/')
        jRequire(parts.size==2 && parts[0]=="documents" && path.length<=384 && parts[1].length<=255 && DesktopIndexStartupPreflight.safeWindowsName(parts[1]))
        return JSource(path,long(get(o,"bytes"),1_048_576),sha(get(o,"sha256")))
    }
    fun config(e: JsonElement): JConfig {
        val o=obj(e); fields(o,"store","id","generation","revision","publicFingerprint","credentialGeneration","dimensions","embeddingKey")
        val store=text(get(o,"store")); jRequire(store in setOf("SINGLETON","LEGACY")); val i=id(get(o,"id")); jRequire(store!="SINGLETON" || i=="default")
        return JConfig(store,i,uuid(get(o,"generation")),long(get(o,"revision"),min=1),sha(get(o,"publicFingerprint")),
            uuid(get(o,"credentialGeneration")),long(get(o,"dimensions"),65_536,1).toInt(),sha(get(o,"embeddingKey")))
    }
    fun link(e: JsonElement): JLink? {
        if(e==JsonNull)return null
        val o=obj(e); fields(o,"txId","revision","file")
        return JLink(uuid(get(o,"txId")),long(get(o,"revision"),min=1),evidence(get(o,"file"),65_536))
    }
    fun manifest(e: JsonElement, target: Boolean): JManifest {
        val o=obj(e); fields(o,"file","records","payloadBytes")
        return JManifest(evidence(get(o,"file"),if(target)524_288 else 25_600_000),long(get(o,"records"),if(target)2048 else 100_000),
            long(get(o,"payloadBytes"),if(target)67_108_864 else 4_294_967_296))
    }
    fun binding(e: JsonElement): JBinding {
        val o=obj(e); fields(o,"documentId","generation","producerTxId","source","config","set")
        val generation=long(get(o,"generation")); val names=listOf("producerTxId","source","config","set")
        if(generation==0L) { jRequire(names.all { get(o,it)==JsonNull }); return JBinding(id(get(o,"documentId")),0,null,null,null,null) }
        jRequire(names.none { get(o,it)==JsonNull }); val set=obj(get(o,"set")); fields(set,"records","payloadBytes","rowsSha256")
        return JBinding(id(get(o,"documentId")),generation,uuid(get(o,"producerTxId")),source(get(o,"source")),config(get(o,"config")),
            JSet(long(get(set,"records"),2048,1),long(get(set,"payloadBytes"),67_108_864),sha(get(set,"rowsSha256"))))
    }
    fun bindings(o: JsonObject): List<JBinding> {
        val array=get(o,"documents") as? JsonArray ?: jRefuse(DesktopIndexInspectionCode.CORRUPT)
        jRequire(array.size<=64); val result=array.map(::binding); val ids=result.map { it.documentId }
        jRequire(ids==ids.sorted() && ids.map { it.lowercase(Locale.ROOT) }.distinct().size==ids.size); return result
    }
    fun state(e: JsonElement, arena: JReadArena): JState {
        val o=obj(e); fields(o,"cardId","revision","card","documents"); bindings(o)
        return JState(id(get(o,"cardId")),long(get(o,"revision"),min=1),evidence(get(o,"card"),8_388_608),arena.put(canonical(o)))
    }
    /** Only for field equality in memory. NEVER used as a file identity/hash. */
    private fun canonical(e: JsonElement): ByteArray {
        fun sort(v: JsonElement): JsonElement = when(v) {
            is JsonObject -> JsonObject(v.toSortedMap().mapValues { sort(it.value) })
            is JsonArray -> JsonArray(v.map(::sort))
            else -> v
        }
        return sort(e).toString().toByteArray(Charsets.UTF_8)
    }
    fun header(o: JsonObject, kind: String) {
        if(text(get(o,"format"))!="CCB_DOCUMENT_INDEX_J")jRefuse(DesktopIndexInspectionCode.UNSUPPORTED_VERSION)
        val version=long(get(o,"version"), min=1)
        if(version!=1L)jRefuse(DesktopIndexInspectionCode.UNSUPPORTED_VERSION)
        jRequire(text(get(o,"kind"))==kind)
    }
    fun controlFields(o: JsonObject, kind: String, vararg names: String) { header(o,kind); fields(o,"format","version","kind",*names) }
    fun intent(o: JsonObject, arena: JReadArena): JIntent {
        controlFields(o,"INTENT","txId","taskId","root","writer","operation","cardId","documentId","source","config","expectedHead","oldCard","newCard","oldSet","newSet","unrelated","next")
        jRequire(text(get(o,"operation"))=="INDEX_DOCUMENT")
        val result=JIntent(uuid(get(o,"txId")),uuid(get(o,"taskId")),root(get(o,"root")),writer(get(o,"writer")),id(get(o,"cardId")),id(get(o,"documentId")),
            source(get(o,"source")),config(get(o,"config")),link(get(o,"expectedHead")),evidence(get(o,"oldCard"),8_388_608),evidence(get(o,"newCard"),8_388_608),
            manifest(get(o,"oldSet"),true),manifest(get(o,"newSet"),true),manifest(get(o,"unrelated"),false),state(get(o,"next"),arena))
        jRequire(result.next.cardId==result.cardId && result.next.card==result.newCard && result.newSet.records>0)
        return result
    }
    fun seal(o: JsonObject): JSeal {
        controlFields(o,"SEAL","txId","intentFile","oldManifestFile","newManifestFile","unrelatedManifestFile","oldCardFile","newCardFile","sourceFile","oldHeadFile")
        return JSeal(uuid(get(o,"txId")),evidence(get(o,"intentFile"),65_536),evidence(get(o,"oldManifestFile"),524_288),evidence(get(o,"newManifestFile"),524_288),
            evidence(get(o,"unrelatedManifestFile"),25_600_000),evidence(get(o,"oldCardFile"),8_388_608),evidence(get(o,"newCardFile"),8_388_608),
            evidence(get(o,"sourceFile"),1_048_576),get(o,"oldHeadFile").takeUnless { it==JsonNull }?.let { evidence(it,65_536) })
    }
    fun marker(o: JsonObject, arena: JReadArena): JMarker {
        controlFields(o,"COMMIT_MARKER","txId","root","writer","intentFile","sealFile","predecessor","next")
        return JMarker(uuid(get(o,"txId")),root(get(o,"root")),writer(get(o,"writer")),evidence(get(o,"intentFile"),65_536),evidence(get(o,"sealFile"),65_536),link(get(o,"predecessor")),state(get(o,"next"),arena))
    }
    fun receipt(o: JsonObject, arena: JReadArena): JReceipt {
        controlFields(o,"HISTORY_RECEIPT","txId","root","writer","intentFile","sealFile","commitFile","predecessor","next")
        return JReceipt(uuid(get(o,"txId")),root(get(o,"root")),writer(get(o,"writer")),evidence(get(o,"intentFile"),65_536),evidence(get(o,"sealFile"),65_536),
            evidence(get(o,"commitFile"),65_536),link(get(o,"predecessor")),state(get(o,"next"),arena))
    }
    fun head(o: JsonObject, arena: JReadArena): JHead {
        controlFields(o,"HEAD","txId","root","writer","intentFile","sealFile","commitFile","receiptFile","predecessor","state")
        return JHead(uuid(get(o,"txId")),root(get(o,"root")),writer(get(o,"writer")),evidence(get(o,"intentFile"),65_536),evidence(get(o,"sealFile"),65_536),
            evidence(get(o,"commitFile"),65_536),evidence(get(o,"receiptFile"),65_536),link(get(o,"predecessor")),state(get(o,"state"),arena))
    }
    fun row(o: JsonObject): JManifestRow { fields(o,"storageId","bytes","sha256"); return JManifestRow(id(get(o,"storageId")),JFileEvidence(long(get(o,"bytes"),8_388_608,1),sha(get(o,"sha256")))) }
}
