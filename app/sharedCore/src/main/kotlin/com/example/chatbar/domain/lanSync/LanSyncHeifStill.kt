package com.example.chatbar.domain.lanSync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** File-backed admission for local HEVC image items. This never invokes a codec or rewrites metadata. */
object LanSyncHeifStill {
    data class Dimensions(val width: Int, val height: Int)

    fun inspect(path: Path): Dimensions? = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { file ->
        val size = file.size()
        if (size < 8) return null
        val signature = file.bytes(0, 8)
        val declared = signature.u(4)
        if (signature.type() != "ftyp") return null
        fun hasHeicMajorBrand(): Boolean {
            val majorStart = if (declared == 1L) 16L else 8L
            return (declared <= 1L || declared >= 12L) && size >= majorStart + 4 &&
                file.bytes(majorStart, 4).type() in heicBrands
        }
        if (size < 16) {
            if (hasHeicMajorBrand()) throw LanSyncInvalidImage()
            return null
        }
        val brand = try {
            file.box(0, size).also {
                requireSupported(it.length <= 1024)
                need(it.length >= 8 && (it.length - 8) % 4 == 0L)
            }
        } catch (failure: MalformedStructure) {
            if (hasHeicMajorBrand()) throw LanSyncInvalidImage(failure)
            throw failure
        }
        val brands = file.bytes(brand.start, brand.length.toInt())
        val major = brands.type()
        brands.u(4)
        if (major !in heicBrands && (0 until brands.remaining() / 4).none { brands.type() in heicBrands }) return null
        try {
            inspectStill(file, size, brand.end)
        } catch (failure: MalformedStructure) {
            throw LanSyncInvalidImage(failure)
        }
    }

    private fun inspectStill(file: FileChannel, size: Long, start: Long): Dimensions {
        val boxes = mutableListOf<Box>()
        var position = start
        while (position < size) {
            requireSupported(boxes.size < MAX_ITEMS)
            val box = file.box(position, size)
            boxes += box
            position = box.end
        }
        val meta = requireValue(boxes.singleOrNull { it.type == "meta" })
        requireSupported(meta.length <= MAX_METADATA)
        need(meta.length >= 4)
        val cursor = file.bytes(meta.start, meta.length.toInt())
        requireSupported(cursor.u(4) == 0L)
        val metadata = cursor.children()
        val primaryBox = metadata.only("pitm")
        val primary = primaryBox.content.run { id(fullVersion()) }
        val items = readItems(metadata.only("iinf").content)
        val locations = readLocations(metadata.only("iloc").content)
        val properties = metadata.only("iprp").content.children()
        val definitions = properties.only("ipco").content.children()
        val associations = readAssociations(properties.filter { it.type == "ipma" }, definitions.size)
        val media = boxes.filter { it.type == "mdat" }
        val inline = metadata.singleOrNull { it.type == "idat" }?.let {
            Box("idat", meta.start + it.start, meta.start + it.end)
        }
        fun extents(id: Long): List<Extent> {
            val location = requireValue(locations[id])
            return location.extents.map { extent ->
                val source = if (location.inline) listOf(requireValue(inline)) else media
                val offset = sum(location.base, extent.offset)
                val start = if (location.inline) sum(offset, requireValue(inline).start) else offset
                need(source.any { start >= it.start && start < it.end && extent.length <= it.end - start })
                Extent(start, extent.length)
            }
        }
        fun propertiesOf(id: Long): List<Child> = requireValue(associations[id]).map { definitions[it - 1] }
        fun dimensions(id: Long): Dimensions = propertiesOf(id).only("ispe").content.duplicate().run {
            requireSupported(u(4) == 0L)
            size(u(4), u(4))
        }
        fun coded(id: Long): Dimensions {
            requireSupported(requireValue(items[id]) == "hvc1")
            val codec = propertiesOf(id).only("hvcC").content.duplicate()
            need(codec.remaining() >= 23)
            requireSupported(codec.u(1) == 1L)
            extents(id)
            return dimensions(id)
        }
        val still = when (requireValue(items[primary])) {
            "hvc1" -> coded(primary)
            "grid" -> {
                val extent = requireValue(extents(primary).singleOrNull())
                need(extent.length == 8L || extent.length == 12L)
                val grid = file.bytes(extent.offset, extent.length.toInt())
                requireSupported(grid.u(1) == 0L)
                val flags = grid.u(1)
                requireSupported(flags in 0L..1L)
                need(extent.length == if (flags == 1L) 12L else 8L)
                val rows = grid.u(1) + 1
                val columns = grid.u(1) + 1
                val fieldSize = if (flags == 1L) 4 else 2
                val result = size(grid.u(fieldSize), grid.u(fieldSize))
                requireSupported(rows * columns <= MAX_ITEMS)
                need(result == dimensions(primary))
                val tiles = readGridReferences(metadata.only("iref").content, primary)
                need(tiles.size.toLong() == rows * columns && primary !in tiles)
                val tileSizes = tiles.distinct().map(::coded).toSet()
                val tile = requireValue(tileSizes.singleOrNull())
                need(result.width.toLong() in ((columns - 1) * tile.width + 1)..(columns * tile.width) &&
                    result.height.toLong() in ((rows - 1) * tile.height + 1)..(rows * tile.height))
                if (columns * tile.width * rows * tile.height > MAX_PIXELS) throw LanSyncLocalImageUnsupported()
                result
            }
            else -> throw IllegalArgumentException("SYNC_IMAGE_HEIF_INVALID")
        }
        if (still.width > 16_384 || still.height > 16_384 || still.width.toLong() * still.height > MAX_PIXELS)
            throw LanSyncLocalImageUnsupported()
        return still
    }

    private data class Box(val type: String, val start: Long, val end: Long) { val length get() = end - start }
    private data class Child(val type: String, val start: Int, val end: Int, val content: ByteBuffer)
    private data class Extent(val offset: Long, val length: Long)
    private data class Location(val inline: Boolean, val base: Long, val extents: List<Extent>)

    private fun readItems(buffer: ByteBuffer): Map<Long, String> {
        val version = buffer.fullVersion()
        val count = buffer.count(if (version == 0) 2 else 4)
        val items = buffer.children()
        need(items.size == count && items.all { it.type == "infe" })
        val result = linkedMapOf<Long, String>()
        for (item in items) {
            val content = item.content
            val itemVersion = content.fullVersion(3)
            requireSupported(itemVersion in 2..3)
            val id = content.id(itemVersion - 2)
            val protection = content.u(2)
            val type = content.type()
            need(result.put(id, if (protection == 0L) type else "protected") == null)
        }
        return result
    }

    private fun readLocations(buffer: ByteBuffer): Map<Long, Location> {
        val version = buffer.fullVersion(2)
        val lengths = buffer.u(1).toInt()
        val widths = buffer.u(1).toInt()
        val result = linkedMapOf<Long, Location>()
        var remainingExtents = 4 * MAX_ITEMS
        repeat(buffer.count(if (version == 2) 4 else 2)) {
            val id = buffer.id(if (version == 2) 1 else 0)
            val method = if (version == 0) 0L else buffer.u(2)
            val reference = buffer.u(2)
            val base = buffer.u(widths ushr 4)
            val count = buffer.count(2)
            requireSupported(count <= remainingExtents)
            need(count > 0)
            remainingExtents -= count
            val extents = List(count) {
                if (version != 0) buffer.u(widths and 15)
                Extent(buffer.u(lengths ushr 4), buffer.u(lengths and 15))
            }
            requireSupported(method in 0L..1L && reference == 0L)
            need(extents.all { it.length > 0 })
            need(result.put(id, Location(method == 1L, base, extents)) == null)
        }
        return result
    }

    private fun readAssociations(boxes: List<Child>, count: Int): Map<Long, List<Int>> {
        val result = linkedMapOf<Long, MutableList<Int>>()
        for (box in boxes) {
            val content = box.content
            val flags = content.u(4)
            val version = (flags ushr 24).toInt()
            requireSupported(version in 0..1 && (flags and 0x00ffffffL) in 0L..1L)
            val indexBytes = if (flags and 1L == 0L) 1 else 2
            val mask = if (indexBytes == 1) 0x7f else 0x7fff
            repeat(content.count(4)) {
                val id = content.id(version)
                val indices = result.getOrPut(id) { mutableListOf() }
                repeat(content.count(1)) {
                    val index = content.u(indexBytes).toInt() and mask
                    requireSupported(indices.size < MAX_ITEMS)
                    need(index in 0..count)
                    if (index != 0) indices += index
                }
            }
        }
        return result
    }

    private fun readGridReferences(buffer: ByteBuffer, primary: Long): List<Long> {
        val version = buffer.fullVersion()
        val references = buffer.children().filter { it.type == "dimg" }.mapNotNull { child ->
            val owner = child.content.id(version)
            if (owner != primary) null else List(child.content.count(2)) { child.content.id(version) }
        }
        return requireValue(references.singleOrNull())
    }

    private fun FileChannel.box(position: Long, boundary: Long): Box {
        need(boundary - position >= 8)
        val header = bytes(position, 8)
        val declared = header.u(4)
        val type = header.type()
        val headerLength = if (declared == 1L) 16 else 8
        need(boundary - position >= headerLength)
        val length = when (declared) { 0L -> boundary - position; 1L -> bytes(position + 8, 8).u(8); else -> declared }
        need(length >= headerLength && length <= boundary - position)
        return Box(type, position + headerLength, position + length)
    }

    private fun FileChannel.bytes(position: Long, count: Int): ByteBuffer = ByteBuffer.allocate(count).also { buffer ->
        // A short physical read here no longer matches the observed file size.
        while (buffer.hasRemaining()) require(read(buffer, position + buffer.position()) > 0) { "SYNC_IMAGE_HEIF_INVALID" }
        buffer.flip()
    }
    private fun ByteBuffer.children(): List<Child> = buildList {
        while (hasRemaining()) {
            requireSupported(size < MAX_ITEMS)
            need(remaining() >= 8)
            val offset = position()
            val declared = u(4)
            val type = type()
            val length = when (declared) { 0L -> limit().toLong() - offset; 1L -> u(8); else -> declared }
            val start = position()
            need(length >= start - offset && length <= limit() - offset)
            val end = offset + length.toInt()
            add(Child(type, start, end, duplicate().apply { limit(end) }.slice()))
            position(end)
        }
    }
    private fun ByteBuffer.u(count: Int): Long {
        requireSupported(count in 0..8)
        need(count <= remaining())
        var value = 0L
        repeat(count) {
            val next = get().toInt() and 255
            requireSupported(value <= (Long.MAX_VALUE - next) / 256)
            value = value * 256 + next
        }
        return value
    }
    private fun ByteBuffer.type(): String = u(4).let { value ->
        CharArray(4) { index -> ((value ushr (24 - 8 * index)) and 255).toInt().toChar() }.concatToString()
    }
    private fun ByteBuffer.fullVersion(maximum: Int = 1) = (u(4) ushr 24).toInt().also { requireSupported(it <= maximum) }
    private fun ByteBuffer.id(version: Int) = u(if (version == 0) 2 else 4).also { need(it > 0) }
    private fun ByteBuffer.count(bytes: Int) = u(bytes).also { requireSupported(it <= MAX_ITEMS) }.toInt()
    private fun size(width: Long, height: Long): Dimensions {
        requireSupported(width <= 262_144L && height <= 262_144L)
        need(width > 0 && height > 0)
        return Dimensions(width.toInt(), height.toInt())
    }
    private fun List<Child>.only(type: String) = requireValue(singleOrNull { it.type == type })
    private fun sum(a: Long, b: Long): Long { requireSupported(a <= Long.MAX_VALUE - b); return a + b }
    private fun <T : Any> requireValue(value: T?): T = value ?: throw MalformedStructure()
    private fun need(value: Boolean) { if (!value) throw MalformedStructure() }
    private fun requireSupported(value: Boolean) { require(value) { "SYNC_IMAGE_HEIF_INVALID" } }
    private class MalformedStructure : IllegalArgumentException("SYNC_IMAGE_HEIF_INVALID")
    private val heicBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")
    private const val MAX_METADATA = 4 * 1024 * 1024
    private const val MAX_ITEMS = 1024
    private const val MAX_PIXELS = 32_000_000L
}
