package com.example.chatbar.desktop.backup

import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Test-classpath opt-in ZIP32 reader. No seek, decrypted file or whole archive allocation.
 * Local entries are tentative until central records, EOCD and authenticated EOF all match.
 * Stored entries with descriptors, ZIP64, split disks and uncommon flags refuse admission.
 */
internal object StrictStreamingZip {
    data class Limits(val expanded: Long = 4L shl 30, val entry: Long = 256L shl 20,
        val compressed: Long = 4L shl 30, val entries: Int = 40003,
        val directory: Long = 8L shl 20, val ratio: Long = 1000)
    private data class Record(val name: String, val flags: Int, val method: Int, val crc: Long,
        val compressed: Long, val size: Long, val offset: Long)

    fun walk(source: InputStream, limits: Limits = Limits(), active: () -> Unit = {},
        consume: (String, InputStream) -> Unit): Int {
        val input = Cursor(source, limits.compressed, active)
        val records = ArrayList<Record>(); val names = HashSet<String>()
        var expanded = 0L
        var signature = input.u32()
        while (signature == 0x04034b50L) {
            active(); require(records.size < limits.entries)
            val offset = input.position - 4
            require(input.u16() <= 20)
            val flags = input.u16(); val method = input.u16()
            require(flags and 0xf7f7 == 0 && method in setOf(0, 8))
            input.bytes(4)
            val headerCrc = input.u32(); val headerCompressed = input.u32(); val headerSize = input.u32()
            require(headerCompressed != 0xffffffffL && headerSize != 0xffffffffL)
            val nameSize = input.u16(); val extraSize = input.u16()
            val name = input.text(nameSize)
            require(name.isNotEmpty() && name.length <= 1024 && !name.endsWith('/'))
            require(!name.startsWith('/') && '\\' !in name && name.none { it.code < 32 || it in ":<>\"|?*" })
            require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') })
            require(names.add(name.lowercase(Locale.ROOT)))
            extra(input.bytes(extraSize))
            require(method != 0 || flags and 8 == 0)
            val start = input.position
            val inflater = if (method == 8) Inflater(true) else null
            val compressedBuffer = ByteArray(32768)
            var supplied = 0; var size = 0L; val crc = CRC32()
            val content = object : InputStream() {
                override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
                override fun close() {} // Entry lifetime belongs to the scanner, not the consumer.
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    active(); if (len == 0) return 0
                    val n: Int
                    if (inflater == null) {
                        if (size == headerCompressed) return -1
                        n = input.read(b, off, minOf(len.toLong(), headerCompressed - size).toInt())
                        require(n > 0)
                    } else {
                        while (true) {
                            if (inflater.finished()) return -1
                            require(!inflater.needsDictionary())
                            if (inflater.needsInput()) {
                                supplied = input.read(compressedBuffer, 0, compressedBuffer.size)
                                require(supplied > 0); inflater.setInput(compressedBuffer, 0, supplied)
                            }
                            val count = inflater.inflate(b, off, len)
                            if (count > 0) { n = count; break }
                            require(inflater.finished() || inflater.needsInput())
                        }
                    }
                    size += n; expanded += n
                    require(size <= limits.entry && expanded <= limits.expanded)
                    val used = inflater?.bytesRead ?: size
                    require(size <= maxOf(1024L * 1024, used * limits.ratio))
                    crc.update(b, off, n); return n
                }
            }
            try {
                consume(name, content)
                val drain = ByteArray(32768)
                try { while (content.read(drain) >= 0) active() } finally { drain.fill(0) }
                if (inflater != null && inflater.remaining > 0)
                    input.unread(compressedBuffer, supplied - inflater.remaining, inflater.remaining)
            } finally { inflater?.end(); compressedBuffer.fill(0) }
            val compressed = input.position - start
            if (flags and 8 != 0) {
                var descriptorCrc = input.u32()
                if (descriptorCrc == 0x08074b50L) descriptorCrc = input.u32()
                require(descriptorCrc == crc.value && input.u32() == compressed && input.u32() == size)
                require(headerCrc in listOf(0L, crc.value) && headerCompressed in listOf(0L, compressed) && headerSize in listOf(0L, size))
            } else require(headerCrc == crc.value && headerCompressed == compressed && headerSize == size)
            records += Record(name, flags, method, crc.value, compressed, size, offset)
            signature = input.u32()
        }
        val centralStart = input.position - 4
        for (record in records) {
            active(); require(signature == 0x02014b50L)
            val madeBy = input.u16(); require(input.u16() <= 20)
            require(input.u16() == record.flags && input.u16() == record.method)
            input.bytes(4)
            require(input.u32() == record.crc && input.u32() == record.compressed && input.u32() == record.size)
            val nameSize = input.u16(); val extraSize = input.u16(); val commentSize = input.u16()
            require(input.u16() == 0); input.u16()
            val attributes = input.u32()
            require(attributes and 0x10L == 0L)
            if (madeBy shr 8 == 3) require((attributes shr 16).toInt() and 0xf000 in setOf(0, 0x8000))
            require(input.u32() == record.offset && input.text(nameSize) == record.name)
            extra(input.bytes(extraSize)); input.bytes(commentSize)
            require(input.position - centralStart <= limits.directory)
            signature = input.u32()
        }
        val centralLength = input.position - 4 - centralStart
        require(signature == 0x06054b50L)
        require(input.u16() == 0 && input.u16() == 0)
        require(input.u16() == records.size && input.u16() == records.size)
        require(input.u32() == centralLength && input.u32() == centralStart)
        input.bytes(input.u16())
        require(input.read() == -1) // Consumes actual AEAD EOF; no result before authentication.
        active(); return records.size
    }

    private fun extra(bytes: ByteArray) {
        fun u16(p: Int) = (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
        var p = 0
        while (p < bytes.size) { require(p + 4 <= bytes.size && u16(p) != 1); p += 4 + u16(p + 2); require(p <= bytes.size) }
    }
    private class Cursor(source: InputStream, val maximum: Long, val active: () -> Unit) : InputStream() {
        val raw = PushbackInputStream(source, 32768); var position = 0L
        override fun read(): Int { active(); val b = raw.read(); if (b >= 0) { position++; require(position <= maximum) }; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            active(); val n = raw.read(b, off, len); if (n > 0) { position += n; require(position <= maximum) }; return n
        }
        fun unread(b: ByteArray, off: Int, len: Int) { raw.unread(b, off, len); position -= len }
        fun bytes(n: Int): ByteArray { require(n in 0..65535); return ByteArray(n).also { b ->
            var p = 0; while (p < n) { val count = read(b, p, n - p); require(count > 0); p += count }
        } }
        fun u16(): Int { val a = read(); val b = read(); require(a >= 0 && b >= 0); return a or (b shl 8) }
        fun u32() = u16().toLong() or (u16().toLong() shl 16)
        fun text(n: Int) = bytes(n).inputStream().strictUtf8Reader().use { it.readText() }
    }
}
