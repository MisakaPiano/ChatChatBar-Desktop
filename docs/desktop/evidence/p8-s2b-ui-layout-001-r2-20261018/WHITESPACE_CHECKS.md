# Whitespace verification scope

The actual source checkpoint passed `git diff --cached --check` before commit.

The first staged evidence set had314 files. Default `git diff --cached --check` returned2 because raw Windows CRLF was retained under `* -text`; its complete output remains local with a SHA-256 in validation/whitespace-checks.json. Command-scoped `git -c core.whitespace=cr-at-eol diff --cached --check` returned2 for preserved Gradle log trailing spaces, shutdown log blank EOF and unified-patch context lines. The small complete CRLF-aware output is preserved in validation/. No raw log, XML, source snapshot or patch was trimmed to manufacture PASS.

`git -c core.whitespace=cr-at-eol diff --cached --check -- <this evidence directory> ':(exclude,glob)**/*.log' ':(exclude,glob)**/*.patch'` passed for metadata and source snapshots and is rerun after final staging. The CRLF allowance is per command; no repository/global configuration was changed. Full raw evidence is NOT represented as an unqualified whitespace PASS.
