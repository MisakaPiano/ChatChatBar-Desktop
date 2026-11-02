package com.example.chatbar.desktop

import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DesktopPresetResource(val name: String, val sha256: String, val bytes: Long)
@Serializable
internal data class DesktopPresetManifest(val preset: NovelAiGenerationPreset, val resources: List<DesktopPresetResource>)

/** Owns only newly staged resources; it never points cleanup at a source archive or effective data file. */
internal class DesktopPreparedGenerationPreset internal constructor(
    val preset: NovelAiGenerationPreset,
    internal val proofs: List<DesktopPresetResource>,
    internal val files: Map<String, Path>,
    private val home: Path,
    private val custody: DesktopPresetCustody,
    private val receipt: DesktopPresetCustodyRecord,
) : AutoCloseable {
    private var applied = false
    fun markApplied() { applied = true; custody.save(receipt.copy(phase = "APPLIED")) }
    override fun close() {
        try {
            if (Files.exists(home, java.nio.file.LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(home, true)
            files.values.forEach {
                if (Files.exists(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    DesktopArchiveFiles.safe(it, false); Files.delete(it)
                }
            }
            Files.deleteIfExists(home)
            custody.remove(receipt)
        } catch (failure: Exception) {
            try { custody.save(receipt.copy(phase = if (applied) "APPLIED_CLEANUP_PENDING" else "DISCARD_CLEANUP_PENDING")) }
            catch (recording: Exception) { failure.addSuppressed(recording) }
            throw failure
        }
    }
}

/** Explicit portable generation exchange. It does not use history's intentionally incomplete recipe. */
internal class DesktopGenerationPresetArchive(private val root: Path,
    private val resources: DesktopCharacterResourceStore, private val gate: DesktopDataOperationCoordinator) {
    private val payloads = NovelAiInlinePayloadStore(root.toFile())
    private val custody = DesktopPresetCustody(root)
    private val json = Json { encodeDefaults = true }
    private data class Source(val proof: DesktopPresetResource, val copy: (OutputStream) -> Unit)

    suspend fun export(draft: NovelAiStudioDraft, target: Path) = withContext(Dispatchers.IO) { gate.withNormalOperation {
        val context = currentCoroutineContext(); val check = { context.ensureActive() }
        DesktopArchiveFiles.safe(target.parent, true)
        require(!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "预设目标已存在，原文件保留" }
        val sources = linkedMapOf<String, Source>()
        val preset = NovelAiGenerationPreset.capture(draft, { asset ->
            val bytes = imageBytes(asset); val hash = digest(bytes)
            require(hash == asset.sha256) { "预设参考图校验失败" }
            val name = "resources/$hash.image"
            sources.putIfAbsent(name, Source(DesktopPresetResource(name, hash, bytes.size.toLong())) { out ->
                check(); out.write(imageBytes(asset))
            })
            asset.copy(id = hash, path = name)
        }, { encoded ->
            val measure = MeasuredOutput(OutputStream.nullOutputStream(), Long.MAX_VALUE)
            payloads.copyResolved(encoded, measure, check)
            val hash = measure.hash(); val name = "resources/$hash.vibe"
            sources.putIfAbsent(name, Source(DesktopPresetResource(name, hash, measure.bytes)) { out ->
                payloads.copyResolved(encoded, out, check)
            })
            name
        })
        val manifest = DesktopPresetManifest(preset, sources.values.map { it.proof })
        validateManifest(manifest)
        val document = json.encodeToString(DesktopPresetManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        require(document.size <= MAX_MANIFEST)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        var created = false
        try {
            ZipOutputStream(Files.newOutputStream(temporary, CREATE_NEW)).use { zip ->
                created = true
                zip.putNextEntry(ZipEntry("preset.json")); zip.write(document); zip.closeEntry()
                for (source in sources.values) {
                    check(); zip.putNextEntry(ZipEntry(source.proof.name))
                    val output = MeasuredOutput(zip, source.proof.bytes)
                    source.copy(output)
                    require(output.bytes == source.proof.bytes && output.hash() == source.proof.sha256) { "预设资源在导出时发生变化" }
                    zip.closeEntry()
                }
            }
            check()
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, target) // No REPLACE_EXISTING; a concurrent existing target is retained.
        } finally { if (created) Files.deleteIfExists(temporary) }
    } }

    suspend fun inspect(source: Path): DesktopPreparedGenerationPreset = withContext(Dispatchers.IO) { gate.withNormalOperation {
        DesktopArchiveFiles.safe(source, false)
        val context = currentCoroutineContext(); val check = { context.ensureActive() }
        var home: Path? = null
        val staged = linkedMapOf<String, Path>()
        try {
            ZipInputStream(Files.newInputStream(source)).use { zip ->
                val main = requireNotNull(zip.nextEntry)
                require(main.name == "preset.json" && !main.isDirectory)
                val bytes = zip.readNBytes(MAX_MANIFEST + 1)
                require(bytes.size <= MAX_MANIFEST)
                zip.closeEntry()
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                val manifest = json.decodeFromString(DesktopPresetManifest.serializer(), text)
                val total = validateManifest(manifest)
                require(total <= Files.getFileStore(root).usableSpace) { "所选数据目录空间不足以准备预设资源" }
                val proofs = manifest.resources.associateBy { it.name }
                val base = root.resolve("backups/.studio-preset-import")
                DesktopArchiveFiles.safe(root, true)
                for (directory in listOf(root.resolve("backups"), base)) {
                    if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
                    DesktopArchiveFiles.safe(directory, true)
                }
                val directory = Files.createDirectory(base.resolve(UUID.randomUUID().toString())); home = directory
                while (true) {
                    check()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && staged.size < MAX_RESOURCES && entry.name !in staged)
                    val proof = requireNotNull(proofs[entry.name]) { "预设包含未声明的资源" }
                    require(entry.size == -1L || entry.size == proof.bytes)
                    val target = directory.resolve(proof.name.substringAfter('/'))
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        staged[proof.name] = target
                        val measured = MeasuredOutput(output, proof.bytes)
                        copy(zip, measured, check)
                        require(measured.bytes == proof.bytes && measured.hash() == proof.sha256) { "预设资源校验失败" }
                    }
                    zip.closeEntry()
                    if (proof.name.endsWith(".vibe")) Files.newInputStream(target).use { payloads.validateStream(it, check) }
                }
                require(staged.keys == proofs.keys) { "预设缺少所声明的资源" }
                for (asset in manifest.preset.imageGuidance.presetAssets()) {
                    val raster = checkedImage(requireNotNull(staged[asset.path]), asset)
                    require(raster.isNotEmpty())
                }
                val receipt = custody.stage(directory, staged.values)
                DesktopPreparedGenerationPreset(manifest.preset, manifest.resources, staged.toMap(), directory, custody, receipt)
            }
        } catch (failure: Throwable) {
            for (file in staged.values) try { Files.deleteIfExists(file) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            home?.let { try { Files.deleteIfExists(it) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) } }
            throw failure
        }
    } }

    suspend fun materialize(prepared: DesktopPreparedGenerationPreset, register: (String) -> Unit,
        registerPayload: (String) -> Unit = {}): NovelAiImageGuidanceDraft =
        withContext(Dispatchers.IO) { gate.withNormalOperation {
            val context = currentCoroutineContext(); val check = { context.ensureActive() }
            val proofs = prepared.proofs.associateBy { it.name }
            prepared.preset.validate()
            val images = mutableMapOf<String, String>()
            val encodings = mutableMapOf<String, String>()
            prepared.preset.imageGuidance.mapPresetResources({ asset ->
                val path = images.getOrPut(asset.path) {
                    check()
                    val bytes = checkedImage(requireNotNull(prepared.files[asset.path]), asset)
                    resources.materializeImageTracked(PackagedImage("preset.${desktopImageResourceExtension(bytes)}", Base64.getEncoder().encodeToString(bytes)),
                        System.currentTimeMillis(), "p7preset${UUID.randomUUID()}", register)
                }
                asset.copy(id = UUID.randomUUID().toString(), path = path)
            }, { reference ->
                encodings.getOrPut(reference) {
                    check(); val file = requireNotNull(prepared.files[reference]); DesktopArchiveFiles.safe(file, false)
                    val proof = proofs.getValue(reference)
                    require(Files.size(file) == proof.bytes)
                    val digest = MessageDigest.getInstance("SHA-256")
                    java.security.DigestInputStream(LimitedInput(Files.newInputStream(file), proof.bytes), digest).use { input ->
                        var claim: DesktopPresetCustodyRecord? = null
                        val value = payloads.retainStream(input, beforePublishNew = { reference ->
                            claim = custody.beforePayload(reference, proof.bytes).also { registerPayload(it.id) }
                        }, onPublished = { _, created -> claim?.let { custody.published(it, created) } }, checkActive = check)
                        require(Files.size(file) == proof.bytes && digest.digest().hex() == proof.sha256) { "预设编码在导入时发生变化" }
                        value
                    }
                }
            }).let { it.copy(vibes = it.vibes.map { vibe -> vibe.copy(id = UUID.randomUUID().toString()) }) }
        } }

    suspend fun finishPayloads(ids: Set<String>, cleanup: suspend (List<String>) -> Unit, committed: Boolean): Boolean =
        custody.finishPayloads(cleanup, ids, committed)

    fun hasPendingCustody(): Boolean = custody.records().any { it.phase != "RETAINED" }

    private fun imageBytes(asset: NovelAiStudioAssetRef): ByteArray =
        resources.readScreenshotImageBytes(asset.path, DesktopImageEditing.MAX_BYTES).also { bytes -> validateImage(bytes, asset) }
    private fun checkedImage(path: Path, asset: NovelAiStudioAssetRef): ByteArray {
        DesktopArchiveFiles.safe(path, false); require(Files.size(path) <= DesktopImageEditing.MAX_BYTES)
        return Files.newInputStream(path).use { it.readNBytes(DesktopImageEditing.MAX_BYTES + 1) }.also { validateImage(it, asset) }
    }
    private fun validateImage(bytes: ByteArray, asset: NovelAiStudioAssetRef) {
        require(bytes.size <= DesktopImageEditing.MAX_BYTES && digest(bytes) == asset.sha256)
        desktopImageResourceExtension(bytes)
        DesktopImageEditing.requireStatic(bytes)
        val image = DesktopImageEditing.decode(bytes)
        require(image.width == asset.width && image.height == asset.height)
    }
    private fun validateManifest(manifest: DesktopPresetManifest): Long {
        manifest.preset.validate(); require(manifest.resources.size <= MAX_RESOURCES)
        require(manifest.resources.map { it.name }.distinct().size == manifest.resources.size)
        val required = manifest.preset.imageGuidance.presetAssets().map { it.path }.toSet() +
            manifest.preset.imageGuidance.vibes.mapNotNull { it.encodedVibe }.toSet()
        require(required == manifest.resources.map { it.name }.toSet())
        var total = 0L
        for (proof in manifest.resources) {
            require(proof.sha256.matches(Regex("[a-f0-9]{64}")))
            require(proof.name == "resources/${proof.sha256}.image" || proof.name == "resources/${proof.sha256}.vibe")
            val maximum = if (proof.name.endsWith(".image")) DesktopImageEditing.MAX_BYTES.toLong() else Long.MAX_VALUE
            require(proof.bytes in 1L..maximum)
            total = Math.addExact(total, proof.bytes)
        }
        return total
    }
    private class MeasuredOutput(private val output: OutputStream, private val maximum: Long) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L; private set
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(length.toLong() <= maximum - bytes) { "预设资源超过所声明大小" }
            output.write(buffer, offset, length); digest.update(buffer, offset, length); bytes += length
        }
        override fun flush() = output.flush()
        fun hash(): String = digest.digest().hex()
    }
    private class LimitedInput(input: InputStream, private val maximum: Long) : java.io.FilterInputStream(input) {
        private var bytes = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { bytes++; require(bytes <= maximum) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
            if (it > 0) { bytes = Math.addExact(bytes, it.toLong()); require(bytes <= maximum) }
        }
    }
    companion object {
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private const val MAX_RESOURCES = 35
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun copy(input: InputStream, output: OutputStream, check: () -> Unit) {
            val buffer = ByteArray(65536)
            while (true) { check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        }
    }
}
