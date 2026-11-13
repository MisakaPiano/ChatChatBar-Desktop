package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Encoded Vibe data stays opaque UTF-8 content, never a provider encoding request. */
internal object LanSyncVibePayloads {
    private val discard = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
    fun source(root: Path, value: String, scratch: LanSyncScratch): Path = checked {
        require(value.isNotBlank() && value.length <= LanSyncWorkspace.MAX_RECORD &&
            !value.startsWith("ccb-lan-v1/") && Charsets.UTF_8.newEncoder().canEncode(value))
        val store = NovelAiInlinePayloadStore(root.toFile())
        if (value.startsWith(NovelAiInlinePayloadStore.PREFIX)) {
            val hash = value.removePrefix(NovelAiInlinePayloadStore.PREFIX)
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val path = root.resolve("novelai-vibe-payloads").resolve(hash)
            lanSyncSafe(path); require(Files.size(path) > 0)
            store.copyResolved(value, discard)
            path
        } else {
            val path = scratch.directory.resolve("vibe-${UUID.randomUUID()}")
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                scratch.own(path)
                store.copyResolved(value, Channels.newOutputStream(channel))
                channel.force(true)
            }
            path
        }
    }
    fun validate(path: Path) = checked {
        lanSyncSafe(path)
        require(Files.size(path) > 0)
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
            NovelAiInlinePayloadStore(path.parent.toFile()).validateStream(it)
        }
    }
    fun retain(root: Path, source: Path, expectedHash: String): String = checked {
        validate(source)
        val value = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use {
            NovelAiInlinePayloadStore(root.toFile()).retainStream(it, beforePublishNew = { marker ->
                require(marker == NovelAiInlinePayloadStore.PREFIX + expectedHash)
            })
        }
        val actual = if (value.startsWith(NovelAiInlinePayloadStore.PREFIX)) value.removePrefix(NovelAiInlinePayloadStore.PREFIX)
            else lanSyncHash(value.toByteArray(Charsets.UTF_8))
        require(actual == expectedHash)
        value
    }
    private inline fun <T> checked(work: () -> T): T = try { work() }
    catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
    catch (error: Exception) { throw IllegalArgumentException("SYNC_VIBE_PAYLOAD_INVALID", error) }
}
