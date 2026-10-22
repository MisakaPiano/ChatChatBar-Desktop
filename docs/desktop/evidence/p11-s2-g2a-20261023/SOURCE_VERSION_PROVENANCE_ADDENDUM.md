# Official version source path correction

Final receipt audit found a **documentation path error**, not a tested-source change. `SOURCE_AUDIT.md` names `app/build.gradle.kts` as code84/name1.4.5 authority, and `raw/official-source-identities.json` correctly hashes that outer build file. That file does **not** contain the application version declaration. Its hash alone cannot support that claim.

The actual pinned official declaration is [app/app/build.gradle.kts](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/build.gradle.kts). It supplies baseVersionCode84 and a default versionName of1.4.5, with Gradle-property/environment overrides in the official build. G2A fixtures target the pinned default code84/v1 wire contract, not all property-overridden binaries.

Exact canonical Git object identity:

- Commit: `1198a13cf3008de1358b8a69aa5e40b64797c8d9`
- Path: `app/app/build.gradle.kts`
- Blob: `dd96dce807d8af29450a62e548c32c481f8f1a8e`
- Length:7123 bytes
- SHA-256: `a0a31a8e693f156462bf0b90d51c6ac9e40cdc5a7b71a264b9d2cc59c7f81929`

This addendum supersedes only the incorrect version-authority row. The already frozen source commit, original raw source identity list and262-file index are preserved unchanged for auditability. No code, fixture, XML, hash index or test count is replaced. Final tested source remains `c44a241c6df1dc4461f5409b0fc14c2f7a2968b7`; no test rerun was needed for this documentation correction.
