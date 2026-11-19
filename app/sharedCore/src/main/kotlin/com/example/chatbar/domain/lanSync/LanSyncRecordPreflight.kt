package com.example.chatbar.domain.lanSync

import java.io.Reader
import kotlinx.serialization.decodeFromString

/** The typed decoder still checks field types, required fields and the full original JSON. */
internal class LanSyncRecordPreflight(private val input: Reader, private val budget: LanSyncMetadataBudget,
    private val recordPath: List<String>) {
    data class Usage(val records: Int, val largestRecordCost: Long, val indexCost: Long)

    private val buffer = CharArray(8192)
    private var offset = 0
    private var end = 0
    private var current = -1
    private var indexCost = 0L
    private var recordCost = 0L
    private var largest = 0L
    private var recordCount = 0
    private var inRecord = false
    private var foundRecords = false

    fun validate(): Usage {
        require(recordPath.isNotEmpty())
        advance()
        value(0, 0)
        whitespace()
        require(current == -1 && foundRecords) { "SYNC_METADATA_STRUCTURE" }
        return Usage(recordCount, largest, indexCost)
    }

    private fun advance() {
        if (offset == end) {
            end = input.read(buffer)
            offset = 0
        }
        current = if (end <= 0) -1 else buffer[offset++].code
    }

    private fun at(char: Char) = current == char.code
    private fun whitespace() { while (at(' ') || at('\t') || at('\r') || at('\n')) advance() }
    private fun expect(char: Char) {
        whitespace()
        require(at(char)) { "SYNC_METADATA_STRUCTURE" }
        advance()
    }

    private fun charge(amount: Long) {
        if (inRecord) {
            recordCost = lanSyncAddLength(recordCost, amount)
            require(recordCost <= budget.maximumDecodedCost) { "SYNC_METADATA_WORKING_BUDGET" }
        } else {
            indexCost = lanSyncAddLength(indexCost, amount)
            require(indexCost <= budget.maximumIndexCost) { "SYNC_METADATA_INDEX_BUDGET" }
        }
    }

    private fun open(depth: Int) {
        require(depth <= budget.maximumDepth) { "SYNC_METADATA_DEPTH" }
        charge(128)
    }

    private fun value(parentDepth: Int, pathIndex: Int) {
        whitespace()
        when {
            pathIndex == recordPath.size -> records(parentDepth + 1)
            at('{') -> fields(parentDepth + 1, pathIndex)
            at('[') -> array(parentDepth + 1)
            at('"') -> string()
            else -> {
                charge(64)
                var characters = 0L
                while (current >= 0 && !at(' ') && !at('\t') && !at('\r') && !at('\n') &&
                    !at(',') && !at('}') && !at(']') && !at('{') && !at('[') && !at(':') && !at('"')) {
                    charge(8)
                    characters++
                    advance()
                }
                require(characters > 0) { "SYNC_METADATA_STRUCTURE" }
            }
        }
    }

    private fun fields(depth: Int, pathIndex: Int) {
        open(depth)
        expect('{')
        val expected = recordPath.getOrNull(pathIndex)
        whitespace()
        if (!at('}')) {
            while (true) {
                val field = string(expected?.length ?: 0)
                expect(':')
                charge(64)
                value(depth, if (expected != null && field == expected) pathIndex + 1 else -1)
                whitespace()
                if (!at(',')) break
                advance()
                charge(64)
                whitespace()
            }
        }
        expect('}')
    }

    private fun record(depth: Int) {
        require(depth <= budget.maximumDepth) { "SYNC_METADATA_DEPTH" }
        expect('{')
        val fields = HashSet<String>(3)
        // The typed decoder drops these fixed wire keys and punctuation after each
        // record. Budget the retained handle and identity strings independently.
        while (true) {
            val field = requireNotNull(string(captureCharacters = 4, descriptor = true)) { "SYNC_METADATA_STRUCTURE" }
            require(fields.add(field)) { "SYNC_METADATA_STRUCTURE" }
            expect(':')
            when (field) {
                "type", "id" -> {
                    val identity = requireNotNull(string(captureCharacters = 256, descriptor = true)) { "SYNC_ID" }
                    require(identity.isNotBlank() && identity.length <= 256) { "SYNC_ID" }
                    charge(8L * identity.length)
                }
                "body" -> {
                    whitespace()
                    require(at('{')) { "SYNC_METADATA_STRUCTURE" }
                    inRecord = true
                    recordCost = 0
                    value(depth, -1)
                    largest = maxOf(largest, recordCost)
                    inRecord = false
                }
                else -> throw IllegalArgumentException("SYNC_METADATA_STRUCTURE")
            }
            whitespace()
            if (!at(',')) break
            advance()
        }
        expect('}')
        require(fields.size == 3) { "SYNC_METADATA_STRUCTURE" }
    }

    private fun array(depth: Int) {
        open(depth)
        expect('[')
        elements { value(depth, -1) }
    }

    private fun records(depth: Int) {
        require(!foundRecords) { "SYNC_METADATA_STRUCTURE" }
        foundRecords = true
        open(depth)
        expect('[')
        elements {
            require(at('{')) { "SYNC_METADATA_STRUCTURE" }
            require(recordCount < 100_000) { "SYNC_RECORD_LIMIT" }
            recordCount++
            charge(512) // Record locator and list/index entries.
            record(depth + 1)
        }
    }

    private fun elements(element: () -> Unit) {
        whitespace()
        if (!at(']')) {
            while (true) {
                element()
                whitespace()
                if (!at(',')) break
                advance()
                charge(64)
                whitespace()
            }
        }
        expect(']')
    }

    private fun string(captureCharacters: Int = 0, descriptor: Boolean = false): String? {
        expect('"')
        if (!descriptor) charge(128)
        val limit = captureCharacters * 6 + 1 // Worst-case escaped UTF-16 characters and closing quote.
        val token = if (captureCharacters > 0) StringBuilder().append('"') else null
        var escaped = false
        var characters = 0L
        while (current >= 0) {
            val char = current.toChar()
            advance()
            if (!descriptor) charge(8)
            characters++
            require(characters <= budget.maximumStringCharacters) { "SYNC_METADATA_STRING_BUDGET" }
            if (descriptor) require(characters <= limit) { "SYNC_METADATA_STRUCTURE" }
            if (token != null && characters <= limit) token.append(char)
            if (!escaped && char == '"') {
                return if (token != null && characters <= limit) lanSyncJson.decodeFromString<String>(token.toString()) else null
            }
            escaped = !escaped && char == '\\'
        }
        throw IllegalArgumentException("SYNC_METADATA_STRUCTURE")
    }
}
