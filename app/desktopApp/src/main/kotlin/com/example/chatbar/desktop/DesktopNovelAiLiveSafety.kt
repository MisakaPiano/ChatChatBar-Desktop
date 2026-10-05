package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import kotlinx.serialization.json.*

/** Phase-specific automation admission. This is not a second image-generation policy. */
internal object DesktopNovelAiLivePolicy {
    fun requireFree(settings: NovelAiGenerationSettings, guidance: NovelAiPreparedImageGuidance, account: NovelAiAccountUsage) {
        require(settings.validationError(0) == null) { "生图参数无效" }
        val size = settings.imageSize()
        require(settings.model == NovelAiImageModel.V4_5_FULL && settings.count == 1 && settings.steps in 1..28 &&
            settings.sizeTier == NovelAiSizeTier.NORMAL && settings.aspectRatio == NovelAiAspectRatio.SQUARE &&
            !settings.usesCustomSize && size.width <= 1024 && size.height <= 1024 &&
            guidance == NovelAiPreparedImageGuidance.NONE) { "请求不符合 Phase 7 自动免费验证范围" }
        val cost = NovelAiImageCostEstimator.estimate(settings, account)
        require(account.isActiveOpus && cost.kind == NovelAiGenerationChargeKind.FREE && cost.anlas == 0) {
            "无法确认免费资格，未发送图片请求"
        }
    }
}

/** OS-wide lease and fail-closed durable counter, outside all app-data roots/backups. */
internal class DesktopNovelAiLiveFuse(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    fun acquire(): Lease {
        try {
            Files.createDirectories(directory)
            check(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            val lockPath = directory.resolve("phase7.lock")
            check(!Files.isSymbolicLink(lockPath))
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = channel.tryLock() ?: error("busy")
                return Lease(channel, lock)
            } catch (error: Throwable) { channel.close(); throw error }
        } catch (_: Exception) { error("真实生图安全记录不可用或已有请求运行；未发送请求") }
    }

    inner class Lease internal constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
        private val journal = directory.resolve("phase7-requests.json")
        private var timestamps: List<Long> = emptyList()
        private var reserved = false

        fun checkReady() {
            check(!reserved)
            try {
                if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    check(Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) && Files.size(journal) <= 4096)
                    val root = Json.parseToJsonElement(Files.readString(journal)).jsonObject
                    check(root.keys == setOf("version", "requests", "pending", "blocked"))
                    check(root.getValue("version").jsonPrimitive.int == 1)
                    check(!root.getValue("pending").jsonPrimitive.boolean && !root.getValue("blocked").jsonPrimitive.boolean)
                    timestamps = root.getValue("requests").jsonArray.map { it.jsonPrimitive.long }
                    check(timestamps.size <= 8 && timestamps.all { it > 0 } && timestamps == timestamps.sorted())
                }
            } catch (_: Exception) { error("真实生图记录异常或上次请求未安全完成；禁止继续自动验证") }
            val now = clock()
            check(timestamps.size < 8) { "Phase 7 已达到 8 次真实图片请求上限" }
            check(timestamps.lastOrNull()?.let { now >= it && now - it >= 30_000 } != false) { "两次生成至少间隔 30 秒" }
            check(timestamps.count { now - it < 300_000 } < 2) { "5 分钟最多允许 2 次生成" }
        }

        fun reserve() {
            checkReady()
            timestamps = timestamps + clock()
            write(pending = true, blocked = false)
            reserved = true // Persist before the request can be submitted; failures consume the slot.
        }

        fun halt() { write(pending = false, blocked = true) }

        fun finish(success: Boolean) {
            check(reserved)
            write(pending = false, blocked = !success)
            reserved = false
        }

        private fun write(pending: Boolean, blocked: Boolean) {
            val bytes = buildJsonObject {
                put("version", 1); put("requests", JsonArray(timestamps.map(::JsonPrimitive)))
                put("pending", pending); put("blocked", blocked)
            }.toString().toByteArray()
            val temporary = Files.createTempFile(directory, "phase7-", ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) output.write(buffer)
                    output.force(true)
                }
                Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) { error("无法持久保存真实请求保护记录；禁止继续请求") }
            finally { Files.deleteIfExists(temporary) }
        }

        override fun close() {
            try { lock.release() } finally { channel.close() }
            // A cancelled/crashed request deliberately leaves pending=true and cannot be replayed.
        }
    }
}
