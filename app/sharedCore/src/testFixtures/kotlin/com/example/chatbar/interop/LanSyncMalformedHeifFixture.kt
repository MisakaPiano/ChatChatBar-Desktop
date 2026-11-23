package com.example.chatbar.interop

import java.nio.ByteBuffer

object LanSyncMalformedHeifFixture {
    fun withoutMeta(): ByteArray = ByteBuffer.allocate(20)
        .putInt(20).put("ftypheic".toByteArray(Charsets.US_ASCII)).putInt(0)
        .put("mif1".toByteArray(Charsets.US_ASCII)).array()

    fun withoutPrimaryItem(): ByteArray = meta(12)
    fun truncatedMetadata(): ByteArray = meta(64)
    fun unsupportedMetaVersion(): ByteArray = withoutPrimaryItem().also { it[it.size - 4] = 1 }
    fun truncatedFileType(): ByteArray = withoutMeta().also { ByteBuffer.wrap(it).putInt(64) }
    fun truncatedFileTypeWithHeicCompatibility(): ByteArray = ByteBuffer.allocate(20)
        .putInt(64).put("ftypmif1".toByteArray(Charsets.US_ASCII)).putInt(0)
        .put("heic".toByteArray(Charsets.US_ASCII)).array()

    private fun meta(declaredLength: Int): ByteArray = withoutMeta() + ByteBuffer.allocate(12)
        .putInt(declaredLength).put("meta".toByteArray(Charsets.US_ASCII)).putInt(0).array()
}
