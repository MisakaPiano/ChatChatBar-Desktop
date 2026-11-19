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

    private fun fields(depth: Int, pathIndex: Int, record: Boolean = false) {
        open(depth)
        expect('{')
        var foundBody = false
        whitespace()
        if (!at('}')) {
            while (true) {
                val expected = if (record) "body" else recordPath.getOrNull(pathIndex)
                val matched = expected != null && string(expected) == expected
                if (expected == null) string()
                expect(':')
                charge(64)
                if (record && matched) {
                    require(!foundBody) { "SYNC_METADATA_STRUCTURE" }
                    foundBody = true
                    whitespace()
                    require(at('{')) { "SYNC_METADATA_STRUCTURE" }
                    inRecord = true
                    recordCost = 0
                    value(depth, -1)
                    largest = maxOf(largest, recordCost)
                    inRecord = false
                } else value(depth, if (matched) pathIndex + 1 else -1)
                whitespace()
                if (!at(',')) break
                advance()
                charge(64)
                whitespace()
            }
        }
        expect('}')
        require(!record || foundBody) { "SYNC_METADATA_STRUCTURE" }
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
            charge(512) // Record handles, list/index entries and bounded identifiers.
            fields(depth + 1, -1, record = true)
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

    private fun string(expected: String? = null): String? {
        expect('"')
        charge(128)
        val limit = expected?.let { it.length * 6 + 2 } ?: 0
        val token = expected?.let { StringBuilder().append('"') }
        var escaped = false
        var characters = 0L
        while (current >= 0) {
            val char = current.toChar()
            advance()
            charge(8)
            characters++
            require(characters <= budget.maximumStringCharacters) { "SYNC_METADATA_STRING_BUDGET" }
            if (token != null && token.length < limit) token.append(char)
            if (!escaped && char == '"') {
                return if (token != null && characters < limit) lanSyncJson.decodeFromString<String>(token.toString()) else null
            }
            escaped = !escaped && char == '\\'
        }
        throw IllegalArgumentException("SYNC_METADATA_STRUCTURE")
    }
}
