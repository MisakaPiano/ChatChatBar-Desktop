# 63 — Phase 7 Caret-Aware Tag Inspection / Completion

## Current state

Desktop already has:
- `NovelAiTagCompletion.activeFragment(text, cursor)`
- `NovelAiTagSuggestionService`
- Danbooru local catalog
- local dictionary
- Chinese annotation
- inline/fullscreen suggestion popup

Current behavior is primarily **typing completion**.

The current fragment ends at the caret. If caret is inside an existing complete Tag:

`silver_|hair`

the current query is approximately:

`silver_`

rather than the complete existing Tag `silver_hair`.

Therefore current behavior is not a full caret Tag inspector.

## Required modes

### A. Completion mode

User is typing a partial/current Tag.

Example:

`alternate_hair|`

Show ranked candidates.

Each candidate should expose available data:
- English tag
- Chinese name
- category
- post count

Catalog results before dictionary fallback.

Keyboard:
- Up/Down
- Enter/Tab accept
- Esc dismiss

Selection replaces the intended active fragment.

### B. Inspection mode

User moves caret into an existing complete Tag without necessarily editing text.

Example:

`alternate_|hairstyle`

Resolve the full syntactic Tag surrounding caret:

`alternate_hairstyle`

Show:
- exact match first
- Chinese
- category
- post count
- nearby alternatives after the exact match

Inspection itself must never modify Prompt text.

Only explicit candidate selection replaces the **whole Tag**, not just text left of the caret.

## Syntax boundary handling

Reuse/extend existing parser authority.

Correctly handle:
- comma / Chinese comma
- newline
- braces/brackets/parentheses
- `1.5::tag::`
- `source#tag`
- `target#tag`
- interaction markers
- whitespace
- caret at beginning/end/middle

Do not treat natural-language/Text blocks as normal Danbooru tags where current parser says otherwise.

## Caret trigger

A caret movement alone must be sufficient to change inspection query.

Do not require text mutation.

## IME safety

While `TextFieldValue.composition != null`:
- do not accept a suggestion;
- avoid replacing the composing segment;
- inspection may wait until stable composition.

## Data authority

Use existing:
- `NovelAiTagSuggestionService`
- `DesktopNovelAiTagCatalog`
- local dictionary

Do not create another tag database.

## Rendering

Compact popup anchored to active Prompt editor.

Suggested row:

`alternate_hairstyle   非常态发型   147221`
`General`

Dictionary-only result:
- no fake post count
- visibly distinguish fallback if useful.

## AI Design research

This caret feature is separate from AI Design's `NovelAiTagResearchService`.

AI Design research remains automatic and can expose a collapsed progress summary, but does not replace editor completion.
