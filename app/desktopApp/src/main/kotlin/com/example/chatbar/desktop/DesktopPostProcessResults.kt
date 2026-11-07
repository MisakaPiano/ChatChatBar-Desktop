package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.example.chatbar.domain.image.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
internal data class DesktopPostProcessResultRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceSha256: String,
    val operation: String,
    val scale: String,
    val strength: Float,
    val noise: Float,
    val imagePath: String,
    val imageSha256: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun validateForImport() {
        val sha = Regex("[a-f0-9]{64}")
        require(id.isNotBlank() && sourceSha256.matches(sha) && imageSha256.matches(sha) && createdAt >= 0)
        require(NovelAiPostProcessTab.entries.any { it.name == operation } && NovelAiEnhanceScale.entries.any { it.name == scale })
        require(strength.isFinite() && strength in 0f..1f && noise.isFinite() && noise in 0f..1f)
        val path = imagePath.removePrefix("chatbar-backup-file:")
        require(if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) path.removePrefix(OMITTED_SAVE_SLOT_IMAGE_PREFIX).matches(sha)
            else path.startsWith("images/") && path.count { it == '/' } == 1 && !path.contains('\\') && !path.substringAfter('/').let { it.isBlank() || it in setOf(".", "..") })
    }
    fun matches(tab: NovelAiPostProcessTab, options: NovelAiEnhanceOptions) = operation == tab.name &&
        (tab == NovelAiPostProcessTab.UPSCALE || scale == options.scale.name && strength == options.strength && noise == options.noise)
}

/** Immutable paid results have durable resource ownership independent of the composing Pane. */
internal class DesktopPostProcessResults(private val storage: JsonFileStorage,
    private val resources: DesktopCharacterResourceStore, private val gate: DesktopDataOperationCoordinator) {
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow<List<DesktopPostProcessResultRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()

    suspend fun load() = gate.withNormalOperation { mutex.withLock { withContext(Dispatchers.IO) {
        val result = storage.scanEntitiesStrict(KEY, DesktopPostProcessResultRecord.serializer()).map { row ->
            val record = (row.result as? EntityReadResult.Valid)?.value ?: error("后处理记录不可读，原记录保留")
            require(row.storageId == record.id); validate(record); record
        }.sortedWith(compareByDescending<DesktopPostProcessResultRecord> { it.createdAt }.thenByDescending { it.id })
        mutableRecords.value = result
    } } }

    suspend fun persist(input: ByteArray, output: ByteArray, tab: NovelAiPostProcessTab,
        options: NovelAiEnhanceOptions): DesktopPostProcessResultRecord = gate.withNormalOperation {
        mutex.withLock { withContext(NonCancellable + Dispatchers.IO) {
            DesktopPostProcessRaster.decode(output, tab, longestSide = 256).flush()
            val id = UUID.randomUUID().toString(); val time = System.currentTimeMillis()
            check(storage.readEntityStrict(KEY, id, DesktopPostProcessResultRecord.serializer()) == EntityReadResult.Missing)
            var path: String? = null
            try {
                path = resources.materializeImage(PackagedImage("processed.png", Base64.getEncoder().encodeToString(output)), time, "postprocess_$id")
                val record = DesktopPostProcessResultRecord(id, digest(input), tab.name, options.scale.name, options.strength, options.noise,
                    path, digest(output), time)
                validate(record)
                storage.saveEntity(KEY, id, record, DesktopPostProcessResultRecord.serializer())
                mutableRecords.value = listOf(record) + mutableRecords.value
                record
            } catch (failure: Throwable) {
                // Unknown publication retains the exact new file; never remove an earlier paid result.
                if (path != null && storage.readEntityStrict(KEY, id, DesktopPostProcessResultRecord.serializer()) == EntityReadResult.Missing)
                    resources.deleteOwned(path)
                throw failure
            }
        } }
    }

    suspend fun read(record: DesktopPostProcessResultRecord): ByteArray = gate.withNormalOperation { withContext(Dispatchers.IO) {
        check(storage.readEntityStrict(KEY, record.id, DesktopPostProcessResultRecord.serializer()) == EntityReadResult.Valid(record))
        validate(record)
        require(!record.imagePath.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) { "备份未包含此图片" }
        resources.readPostProcessImageBytes(record.imagePath).also {
            check(digest(it) == record.imageSha256)
            DesktopPostProcessRaster.decode(it, NovelAiPostProcessTab.valueOf(record.operation), longestSide = 256).flush()
        }
    } }

    private fun validate(record: DesktopPostProcessResultRecord) {
        record.validateForImport()
        if (!record.imagePath.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) resources.resolveOwnedReference(record.imagePath)
    }
    companion object {
        const val KEY = "desktop_image_post_process_results"
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
