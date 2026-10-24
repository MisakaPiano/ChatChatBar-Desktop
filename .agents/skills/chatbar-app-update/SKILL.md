---
name: chatbar-app-update
description: Maintain ChatBar update discovery, GitHub Release Notes retrieval and display, APK selection, in-app download state, APK validation, installer handoff, and release publishing. Use when update logs are missing in the app, changing update checks or metadata fallbacks, download/install behavior, release asset naming, Android install permissions, or .github/workflows/release.yml.
---

# ChatBar App Update

## Entry Points

- Release lookup and asset selection: `domain/update/AppUpdateChecker.kt`.
- Download, APK validation, and install handoff: `domain/update/AppUpdateManager.kt`.
- Dialog states: `ui/components/AppUpdateDialog.kt`.
- Manual application/catalog update center: `domain/update/DanbooruCatalogUpdate.kt` and `ui/components/UpdateCenterDialog.kt`.
- Startup and manual-check callers: `MainActivity.kt` and `ui/manage/GlobalSettingsScreen.kt` (更新与诊断).
- Android access: `AndroidManifest.xml` and `res/xml/file_paths.xml`.
- Publishing: `.github/workflows/release.yml`.
- Selection/version tests: `AppUpdateCheckerTest.kt`.

All Kotlin paths are relative to `app/app/src/main/java/com/example/chatbar/` unless stated otherwise.

## Invariants

- Keep normal update flow inside ChatBar: select stable Release APK, download it, then open system installer. Open Release page only when no usable APK exists.
- Prefer `browser_download_url` from GitHub asset metadata. Keep deterministic fallback name aligned with workflow output `ChatBar-<version>.apk`.
- Fetch release metadata from GitHub Releases API first. If `api.github.com` is unavailable, use `github.com/<owner>/<repo>/releases.atom` so update notes remain available; use the web redirect only as last-resort tag discovery.
- Treat Release body, API response, Atom feed, `AppUpdateInfo.releaseNotes`, and dialog rendering as separate gates. Success at one layer does not prove the next layer works.
- The web redirect reveals only the tag and URL; it is never a valid update-note source.
- Store packages under app-private `files/updates/`; expose only that directory through existing FileProvider.
- Before install, reject incomplete files, invalid APKs, foreign package names, mismatched version names, and non-increasing `versionCode`.
- Keep one active download. Scope UI state to asset URL so stale state cannot control another release.
- Treat unknown-source approval and installer confirmation as required Android user actions. Never uninstall, clear data, or bypass signature checks.
- Preserve failure detail in dialog. Do not silently redirect to browser after download or validation failure.
- Manual check runs application and Danbooru catalog discovery independently. One failure must not suppress the other result. Startup remains application-only.
- In Compose callers, read the collected download state when deriving `stateFor` (for example, as a `remember` key alongside update info). Collecting an unread delegated state does not subscribe rendering to progress, failures, or completion.
- Catalog updates download the complete upstream SQLite file, verify expected size, Git blob SHA, SQLite header, `quick_check`, required columns, row count, and supported categories, then prepare the matching `RankedTagIndex` before atomically replacing the app-private catalog. Completion observes the new source version, cancels stale queries, and retains SQLite references for active source readers and safely releases retired index readers. Any validation/preparation/install failure keeps the last-known-good catalog.
- When both updates exist, `全部更新` starts independent downloads concurrently. Catalog applies automatically after validation; APK installation always remains a separate user/system-confirmed action.

## Change Workflow

1. Read all entry points above before changing contracts.
2. Keep `AppUpdateInfo`, serialized GitHub models, downloader state, and both dialog callers synchronized.
3. When changing asset naming or release metadata, update checker fallback, release workflow, and tests together.
4. For missing notes, verify the exact tag with `python .agents/skills/chatbar-release-publish/scripts/verify_release.py`, then trace API → Atom → model → dialog. Do not “fix” only the Release body when the installed client is using a body-less fallback.
5. When changing download storage, update FileProvider paths and verify URI grants remain narrow.
6. Represent idle, downloading, ready, failed, permission-required, and installer-launch behavior explicitly.
7. Test API failure explicitly and prove Atom parsing supplies a non-empty body; also test prerelease exclusion.
8. Verify with update unit tests, debug Kotlin compile, and `ci.ps1 -SkipAssemble`.
9. If a release-signed device is connected, use data-preserving release deployment. Debug signing cannot validate production self-update compatibility.

## Diagnosis Rules

- If GitHub body is empty, repair published metadata first.
- If GitHub body is present but Atom is empty, release publication is incomplete for fallback clients.
- If API and Atom contain notes but app shows empty text, inspect installed-version code and runtime logs; do not claim server-side repair solved it.
- An installed APK cannot gain new retrieval logic remotely. Say which new app version must be published and installed before the fix can work.

## Publishing

Use `.github/workflows/release.yml` through `workflow_dispatch` after all intended commits reach its target branch. Supply:

- `versionName`: next unused version without relying on local build overrides.
- `releaseNotes`: short user-facing changes since previous Release; exclude implementation details.

Workflow increments Android `versionCode`, commits version metadata, tags `v<version>`, builds signed APK, and creates GitHub Release. Require the release-publish verification script to pass before reporting completion.

## Desktop Preview discovery (D-041)

- `app/desktopApp/.../DesktopUpdateChecker.kt` owns the bounded, unauthenticated manual Releases API check. Only `MisakaPiano/ChatChatBar-Desktop` `desktop-v<semver>` tags and their exact HTTPS release pages are eligible; inherited Android tags do not identify a Desktop version.
- `DesktopUpdatePanel` in Data shows version/commit, channel, date, notes and browser handoff. It performs no automatic startup request, download, install or restart. Missing notes remain visibly missing; HTTP/metadata errors remain errors.
- Desktop build identity is generated into `desktop-build.properties`; `desktopVersion` is independent of Android. MSI packaging requires explicit `desktopInstallerVersion` and a fixed Desktop upgrade UUID; supply `desktopPreviousInstallerVersion` to check an actual upgrade sequence. The `synthetic` profile has its own name/UUID. Application images may omit assignment but are not installer evidence. See `tools/desktop/windows/README.md`; keep dotted PowerShell arguments quoted. Engineering packaging is not publication.
- Windows packaging currently emits MSI, with `msi_safety.py` applied by each Msi JPackage task before delivery/signing. It removes JDK17 recursive folder cleanup, places old-product removal inside the rollback transaction and blocks downgrade; CAB bytes must remain identical. Failure leaves the unsigned original in owned build reports and no canonical MSI. EXE bootstrap output is excluded until its embedded MSI can receive the same correction. Program defaults use `LOCALAPPDATA/Programs/<package>`; data/bootstrap and credentials remain separate. Ordinary profile has shortcuts/menu, synthetic profile avoids personal shortcuts. The native probe uses only its fixed synthetic product and task directories, assigns a Windows Job before launch, and closes only its owned window. Its private MSI logs and DPAPI witnesses are not public evidence.
- `DesktopUpdateCheckerTest` uses loopback only. Actual network attempts are limited per check, including HTTP 503/408 follow-ups; credentials are never injected. Native installer upgrade/data-retention acceptance remains a separate Preview Gate.
- Desktop release metadata rejects malformed UTF-8 and coerced JSON field types; channel flags must be booleans and identity/notes fields strings (missing/null notes stay visibly absent).
- `DesktopAppContainer.close` closes `DesktopTaskRuntime` admission before draining feature controllers. Admission closure alone does not cancel accepted jobs; the existing task drain retains its cancellation/persistence semantics. `DesktopTaskRuntimeTest` holds a synthetic picker during actual container close to cover late admission.
