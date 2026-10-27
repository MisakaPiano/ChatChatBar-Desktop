package com.example.chatbar.desktop.backup

import java.util.Locale

/** Bounded, memory-only ZIP32 directory cross-check. ZIP64/multidisk are explicitly unsupported. */
internal object InspectionZipDirectory {
    data class Record(val size: Long, val crc: Long)
    fun read(bytes: ByteArray): Map<String, Record> {
        fun u16(p: Int): Int { require(p >= 0 && p <= bytes.size - 2); return (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8) }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        fun number(p: Int): Int = u32(p).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun text(p: Int, n: Int): String { require(p >= 0 && n >= 0 && p <= bytes.size - n)
            return bytes.inputStream(p, n).strictUtf8Reader().readText() }
        fun extra(p: Int, n: Int) { var cursor = p; val end = p + n; require(end <= bytes.size)
            while (cursor < end) { require(cursor + 4 <= end && u16(cursor) != 1); val length = u16(cursor + 2)
                cursor += 4 + length; require(cursor <= end) } }
        var end = -1
        for (p in bytes.size - 22 downTo maxOf(0, bytes.size - 65557)) {
            if (u32(p) == 0x06054b50L && p + 22 + u16(p + 20) == bytes.size) { end = p; break }
        }
        require(end >= 0 && u16(end + 4) == 0 && u16(end + 6) == 0)
        val count = u16(end + 10)
        require(count in 3..DesktopBackupPolicy.MAX_FILES + 3 && u16(end + 8) == count)
        val start = number(end + 16); val length = number(end + 12)
        require(start.toLong() + length == end.toLong())
        var cursor = start; var localEnd = 0
        val records = linkedMapOf<String, Record>(); val folded = hashSetOf<String>()
        repeat(count) {
            require(cursor <= end - 46 && u32(cursor) == 0x02014b50L)
            val flags = u16(cursor + 8); val method = u16(cursor + 10)
            require(flags and 1 == 0 && flags and 0xf7f0 == 0 && method in setOf(0, 8))
            val crc = u32(cursor + 16); val compressed = number(cursor + 20); val size = number(cursor + 24)
            val nameLength = u16(cursor + 28); val extraLength = u16(cursor + 30); val commentLength = u16(cursor + 32)
            require(u16(cursor + 34) == 0)
            val local = number(cursor + 42)
            require(local == localEnd && local <= start - 30 && u32(local) == 0x04034b50L)
            require(u16(local + 6) == flags && u16(local + 8) == method)
            val name = text(cursor + 46, nameLength)
            require(name.isNotEmpty() && !name.endsWith('/') && folded.add(name.lowercase(Locale.ROOT)))
            require(u16(local + 26) == nameLength && text(local + 30, nameLength) == name)
            val localExtra = u16(local + 28)
            extra(local + 30 + nameLength, localExtra); extra(cursor + 46 + nameLength, extraLength)
            val dataEnd = local.toLong() + 30 + nameLength + localExtra + compressed
            require(dataEnd <= start)
            localEnd = dataEnd.toInt()
            if (flags and 8 != 0) {
                if (u32(localEnd) == 0x08074b50L) localEnd += 4
                require(u32(localEnd) == crc && number(localEnd + 4) == compressed && number(localEnd + 8) == size)
                localEnd += 12
            } else require(u32(local + 14) == crc && number(local + 18) == compressed && number(local + 22) == size)
            require(localEnd <= start)
            records[name] = Record(size.toLong(), crc)
            cursor += 46 + nameLength + extraLength + commentLength
            require(cursor <= end)
        }
        require(cursor == end && localEnd == start)
        return records
    }
}
