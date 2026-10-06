package com.example.chatbar.domain.image

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiPromptTranslationTest {
    @Test
    fun `quote only editor drafts produce no translation segments`() {
        for (naturalLanguage in listOf(false, true)) {
            for (source in listOf("\"", " \" ", "\"\"", "“", "”", "“”", "Text: \"", "{\"}")) {
                assertEquals(emptyList<NovelAiPromptTranslationSegment>(),
                    NovelAiPromptTranslationParser.parse(source, naturalLanguage))
                assertNull(NovelAiPromptTranslationParser.activeSegment(source, source.length, naturalLanguage))
            }
        }
    }

    @Test
    fun `quoted text stays translatable through typing and deletion`() {
        for (naturalLanguage in listOf(false, true)) {
            for (source in listOf("\"Hello\"", "“Hello”")) {
                for (length in 0..source.length) {
                    NovelAiPromptTranslationParser.parse(source.take(length), naturalLanguage)
                }
                val segment = NovelAiPromptTranslationParser.parse(source, naturalLanguage).single()
                assertEquals("Hello", segment.lookupText)
                assertEquals(NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE, segment.kind)
                assertEquals(source, segment.source)
                assertEquals(0, segment.start)
                assertEquals(source.length, segment.end)
            }
        }
        val segments = NovelAiPromptTranslationParser.parse("red eyes, \"", false)
        assertEquals(listOf("red eyes"), segments.map { it.lookupText })
    }

    @Test
    fun `annotation uses one dictionary sense while completion keeps every sense`() = runTest {
        val dictionary = NovelAiPromptWordDictionary.fromTsv(
            "pressing\t紧迫的；迫切的\npenis\t阳物;阴茎\nnose\t鼻子\n".byteInputStream()
        )
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>) =
                mapOf("standing" to "词条译名；补充释义")
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("test", "", 1)
        }
        val service = NovelAiPromptTranslationService(dictionary, lookup)
        val result = service.resolve(NovelAiPromptTranslationParser.parse(
            "pressing penis on nose, penis, standing", false
        ))
        assertEquals(
            listOf("紧迫的阳物在鼻子", "阳物", "词条译名；补充释义"),
            result.annotations.map { it.translation }
        )
        assertEquals("紧迫的；迫切的", dictionary.localTranslation("pressing"))
        assertEquals("紧迫的；迫切的", dictionary.search("迫切的").single().translatedName)
    }

    @Test
    fun `translation waits for catalog and only fills missing tags from dictionary`() = runTest {
        val started = CompletableDeferred<Unit>()
        val catalogResult = CompletableDeferred<Map<String, String>>()
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> {
                started.complete(Unit)
                return catalogResult.await()
            }
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("test", "", 1)
        }
        val service = NovelAiPromptTranslationService(
            NovelAiPromptWordDictionary.fromTsv("quartz\t词典释义\n".byteInputStream()), lookup
        )
        val pending = async {
            service.resolve(NovelAiPromptTranslationParser.parse("quartz, standing", false))
        }
        started.await()
        assertFalse(pending.isCompleted)
        catalogResult.complete(mapOf("quartz" to "词条库译名"))
        val result = pending.await()
        assertEquals(listOf("词条库译名", "站立"), result.annotations.map { it.translation })
        assertNull(result.warning)
    }

    @Test
    fun `dictionary normalizes phrase separators and keeps app overrides`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv(
            "sample phrase\t示例短语\ngirl\t测试词义\n".byteInputStream()
        )
        assertEquals("示例短语", dictionary.localTranslation(" SAMPLE__PHRASE "))
        assertEquals("女孩", dictionary.localTranslation("GIRL"))
        assertEquals(listOf("sample phrase"), dictionary.search("示例短语").map { it.name })
    }

    @Test
    fun `interaction markers split attached tags without changing source offsets`() {
        val source = "SOURCE#red_eyesTarget#blue_hair, {source#standing}, target#"
        val segments = NovelAiPromptTranslationParser.parse(source, false)
        assertEquals(listOf("red_eyes", "blue_hair", "standing"), segments.map { it.lookupText })
        segments.forEach { assertEquals(it.source, source.substring(it.start, it.end)) }
        val insertion = NovelAiTagCompletion.insert("Source#红", "Source#红".length, "red_eyes")
        assertEquals("Source#red_eyes", insertion.text)
    }

    @Test
    fun `tag qualifiers retain balanced parentheses for exact lookup`() {
        val segments = NovelAiPromptTranslationParser.parse("{hero_(series)}, (artist_(name))", false)
        assertEquals(listOf("hero_(series)", "artist_(name)"), segments.map { it.lookupText })
    }

    @Test
    fun `dictionary completion finds all English and Chinese matches`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv(
            "quartz\t石英\nquartzite\t石英岩\n".byteInputStream()
        )
        assertEquals(listOf("quartz", "quartzite"), dictionary.search("QUARTZ").map { it.name })
        assertEquals(listOf("quartz", "quartzite"), dictionary.search("石英").map { it.name })
        assertEquals(true, dictionary.search("石英").all { it.fromDictionary })
    }

    @Test
    fun `long prompt translates every occurrence and looks up long tag names`() = runTest {
        val longTag = List(25) { "long" }.joinToString("_")
        val source = (List(1100) { "source#red_eyes" } + "target#$longTag" + "standing").joinToString(", ")
        val requested = mutableListOf<String>()
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> {
                requested.addAll(names)
                return mapOf("red_eyes" to "红眼", longTag to "长词条")
            }
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("test", "", 1)
        }
        val service = NovelAiPromptTranslationService(
            NovelAiPromptWordDictionary.fromTsv("".byteInputStream()), lookup
        )
        val result = service.resolve(NovelAiPromptTranslationParser.parse(source, false))
        assertEquals(1102, result.annotations.size)
        assertEquals(listOf("red_eyes", longTag, "standing"), requested)
        assertEquals("长词条", result.annotations[1100].translation)
        assertEquals("站立", result.annotations.last().translation)
    }

    @Test
    fun `tag parser keeps weights and text block commas out of lookup`() {
        val source = "1girl, {{1.2::red eyes::}}, {{Text: Hello, world!}}\n日本語"
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = false)

        assertEquals(listOf("1girl", "red eyes", "Hello, world!"), segments.map { it.lookupText })
        assertEquals(
            listOf(
                NovelAiPromptTranslationSegmentKind.TAG,
                NovelAiPromptTranslationSegmentKind.TAG,
                NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE
            ),
            segments.map { it.kind }
        )
    }

    @Test
    fun `tag parser treats Chinese and English commas as equivalent delimiters`() {
        val segments = NovelAiPromptTranslationParser.parse(
            "1girl，red eyes,green hair",
            naturalLanguage = false
        )

        assertEquals(
            listOf("1girl", "red eyes", "green hair"),
            segments.map { it.lookupText }
        )
    }

    @Test
    fun `quoted comma remains one tag and mixed language is skipped`() {
        val source = "poster reading \"Hello, world!\", \"A girl is standing in a library\", red eyes 红眼, blue_hair"
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = false)

        assertEquals(
            listOf("poster reading \"Hello, world!\"", "A girl is standing in a library", "blue_hair"),
            segments.map { it.lookupText }
        )
        assertEquals(
            NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE,
            segments[1].kind
        )
    }

    @Test
    fun `natural language parser translates complete lines`() {
        val source = "A girl, smiling at the camera.\n夜景\nSoft morning light."
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = true)

        assertEquals(
            listOf("A girl, smiling at the camera.", "Soft morning light."),
            segments.map { it.lookupText }
        )
    }

    @Test
    fun `small dictionary composes nonstandard tags word by word`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv("".byteInputStream())
        val source = "beautiful_girl standing in a dark library!"
        val translations = dictionary.tokens(source)
            .mapNotNull { token ->
                dictionary.localTranslation(token.normalized)
                    ?.let { token.normalized to it }
            }
            .toMap()

        assertEquals(
            "美丽女孩站立在一昏暗图书馆！",
            dictionary.compose(source, translations)
        )
    }

    @Test
    fun `unknown words remain visibly separated without remote fallback`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv("".byteInputStream())
        val source = "girl foobarbaz standing"
        val translations = dictionary.tokens(source)
            .mapNotNull { token ->
                dictionary.localTranslation(token.normalized)
                    ?.let { token.normalized to it }
            }
            .toMap()

        assertEquals("女孩 foobarbaz 站立", dictionary.compose(source, translations))
    }

    @Test
    fun `catalog query replaces every whitespace run with underscore`() {
        assertEquals("red_eyes_glowing", "  red  eyes\tglowing  ".normalizedTagQuery())
    }

    @Test
    fun `catalog translation requires exact normalized tag match`() {
        val outcome = NovelAiTagSearchOutcome(
            effectiveQuery = "red_eyes",
            candidates = listOf(
                NovelAiTagCandidate("red_eyes_glowing", "发光红眼", 20, NovelAiTagCategory.GENERAL),
                NovelAiTagCandidate("red_eyes", "红眼", 100, NovelAiTagCategory.GENERAL)
            )
        )

        assertEquals("红眼", outcome.exactChineseTranslation("red_eyes"))
        assertNull(outcome.exactChineseTranslation("red_eye"))
    }

    @Test
    fun `translation service batches exact catalog lookup and keeps dictionary fallback`() = runTest {
        val requested = mutableListOf<List<String>>()
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String): NovelAiTagSearchOutcome =
                NovelAiTagSearchOutcome(query, emptyList())

            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> {
                requested += names.toList()
                return mapOf("red_eyes" to "红眼")
            }

            override suspend fun catalogMetadata(): DanbooruCatalogMetadata = DanbooruCatalogMetadata(
                sourceSha = "version",
                sourceCommitTime = "",
                sourceSizeBytes = 1L
            )
        }
        val service = NovelAiPromptTranslationService(
            wordDictionary = NovelAiPromptWordDictionary.fromTsv("".byteInputStream()),
            tagLookup = lookup
        )
        val segments = NovelAiPromptTranslationParser.parse("red eyes, standing", naturalLanguage = false)

        val result = service.resolve(segments)

        assertEquals(listOf(listOf("red eyes", "standing")), requested)
        assertEquals("红眼", result.translations[segments[0].cacheKey])
        assertEquals("站立", result.translations[segments[1].cacheKey])
        assertNull(result.warning)
    }

    @Test
    fun `active segment follows cursor across comma boundaries`() {
        val source = "1girl, red eyes, green hair"

        assertEquals(
            "1girl",
            NovelAiPromptTranslationParser.activeSegment(source, 5, false)?.lookupText
        )
        assertEquals(
            "red eyes",
            NovelAiPromptTranslationParser.activeSegment(source, 7, false)?.lookupText
        )
        assertEquals(
            "green hair",
            NovelAiPromptTranslationParser.activeSegment(source, source.length, false)?.lookupText
        )
        assertNull(
            NovelAiPromptTranslationParser.activeSegment("1girl, ", 7, false)
        )
        assertNull(
            NovelAiPromptTranslationParser.activeSegment("1girl, , red eyes", 7, false)
        )
    }

    @Test
    fun `wrap policy protects only internal tag spaces and preserves comma boundaries`() {
        val source = "winter clothes, black coat,Text: Hello world\nlong hair"
        val plan = NovelAiPromptWrapPolicy.plan(source)

        assertEquals(
            listOf(
                source.indexOf(' '),
                source.indexOf("black coat") + "black".length,
                source.lastIndexOf(' ')
            ),
            plan.nonBreakingSpaceOffsets.toList()
        )
        assertEquals(
            listOf(source.indexOf(','), source.indexOf(',', source.indexOf(',') + 1)),
            plan.breakableCommaOffsets.toList()
        )
    }
}
