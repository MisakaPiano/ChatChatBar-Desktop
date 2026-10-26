---
name: chatbar-release-publish
description: "Publish or repair a complete ChatBar GitHub update. Use whenever the user says 更新, 发布更新, 发新版本, 打包发布, reports missing Release Notes, or otherwise asks to ship or correct a ChatBar Release by committing every workspace change, pushing the current branch, publishing the APK, and verifying user-visible and app-consumable release notes."
---

# ChatBar Release Publish

For Android APK releases, treat “更新” as authorization to publish every current ChatBar workspace change directly; do not open a PR or leave unrelated dirty files behind.

## Desktop entrypoints

Desktop uses `desktop-v<semver>` metadata and Windows MSI; `.github/workflows/release.yml` and the APK steps below are Android-specific. Desktop packaging/identity and native acceptance live in `tools/desktop/windows/README.md`. `audit_app_image.py` reads an explicit image and expected build identity, checks file/JAR integrity and known private entries, and emits an exclusive report outside the image; compare it with the frozen build manifest. Its class count is ZIP entries, not unique JVM classes. This audit does not establish universal secret absence or installer acceptance.

MSI safety correction requires input, workspace and output under `app/desktopApp/build`. Keep MSI builds in a fresh owned child there; an externally relocated app-image build directory does not satisfy that MSI path contract.

Current `msi_safety.verify` requires safety1, directory2 and component1: per-component stable/unique HKCU x64 KeyPaths, plus a separate versioned product/directory owner. Component GUIDs and file payloads stay unchanged; each edited MSI gets a fresh PackageCode. `directory_guard.c` is an MSVC x64 immediate CA: restore before costing and revalidate before mutation. It checks cached component layout and only permits current-user unmanaged component clients matching the installed self or selected old product; unknown/client-read failures refuse. This is not all-user closure. `Invoke-DirectoryGuardChecks.ps1` takes explicit `-LegacyFixture` and `-Directory2Fixture`, runs PS5.1 no-installer checks with fake clients and real synthetic metadata databases, and supports hash-checked DLL reuse. See Windows README for untested live adapters. Rollback target traces never prove physical restoration.

Win10 clean-guest evidence showed `MsiEnumClientsExW` returning2 at index0. The guard's candidate adapter accepts that case only for non-maintenance installs after a complete bounded `MsiEnumComponentsExW` snapshot independently proves that specific component absent in the same current-user/machine scope. The snapshot is invocation-local and reused, never persistent. Snapshot errors/limits/present components, late enumeration errors and foreign client contexts refuse; upgrade still requires old-product ownership. Logs retain stage/native status/ordinal and an explicit verified-absence count. This code/test change alone does not establish DL1 installation or upgrade PASS.

`native_package_probe.py` retains `repair-registration` as a diagnosis-only refusal for incomplete/unknown registration; it does not invoke REINSTALL or implement re-publication. Exact source/program guards and the strong outcome oracle remain. Focused synthetic coverage is `test_native_registration_diagnosis.py`; native rollback/owner failures stay separate.

For bounded real-MSI fixtures, `build_directory_contract_fixture.py` reuses frozen runtime classes with an explicit identity-resource overlay and distinct provenance, then calls the production transform. Its preflight/resource gates do not authorize a heavy slot or native execution. `msi_safety` can reuse a production guard only with its explicit hash and matching source/identity. Pair review must resolve component GUIDs to actual KeyPaths; do not treat directory embedding/CAB equality as component compatibility or upgrade PASS.

The synthetic factory has an explicit `--accept-memory-pressure` option for user-approved paging/slowness; actual memory and the normal gate result stay in its budget receipt. It waives no disk/source/component check and leaves default memory admission intact. Focused pure tests are `test_package_resource_admission.py`.

`Invoke-Comp1FreshGuest.ps1` is a PS5.1 fixed-pair1102/1103 guest entry: pinned hashes/static verifier, fresh-registration/output refusal, one1102 install and local logs/registration/identity evidence. It never upgrades, launches, repairs or cleans. Its VM-model check cannot prove host VM identity; the user selects the independent guest. `Test-Comp1FreshGuest.ps1` uses fake installer boundaries plus tiny JAR fixtures, not native install evidence.

## Workflow

1. Read `chatbar-app-update` and `.github/workflows/release.yml`.
2. Before version lookup, tests, staging, commit, push, or release, audit every completed task and pending/unpushed change for project-skill relevance. Read the affected `.agents/skills` files, update stale skills, and create a compact skill only when a stable new area lacks one. Finish and validate skill changes before continuing; never postpone this audit until commit review.
3. Inspect all tracked, staged, and untracked changes. Check for accidental secrets or generated junk; stop only for real data or credential risk.
4. Resolve the immediately previous stable Release tag from remote GitHub Releases, then determine the next unused patch version. Never reuse a tag.
5. Run `.\gradlew.bat test` and `powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble` from `app/`. Fix in-scope failures before publishing.
6. Stage everything with `git add -A`. Review the staged diff, then create one concise Chinese commit covering the update.
7. Build a Release coverage checklist from the complete range `<previous-stable-tag>..HEAD`:
   - List every non-release commit in chronological order and inspect its diff; do not infer scope from only the latest commit, current dirty workspace, or commit subjects.
   - Map every commit to one or more user-facing Release Notes bullets, or record an explicit developer-only omission reason.
   - Every user-visible behavior change, fix, and new capability in the range must be covered. Multiple related commits may share one bullet, but no user-visible commit may be silently omitted.
   - For repair of an existing Release, audit the exact final range `<previous-stable-tag>..<current-tag>` and exclude only the automated version-bump commit.
8. Write short release notes entirely from the completed coverage checklist and user perspective:
   - Start with the exact heading `## 更新内容`, followed by at least one Markdown bullet.
   - Give each distinct user-facing feature or fix one single-sentence bullet. Combine related commits when they produce the same experience; the notes do not need one bullet per commit.
   - Prefer 3-6 bullets for a normal release; exceed that only when fewer bullets would hide a distinct user-visible change.
   - State only what users can now do or what feels better.
   - Avoid filenames, class names, prompts, RAG, model parameters, test details, refactors, and all other implementation terminology.
   - Translate internal changes into outcomes. Example: write “现在 AI 遵循格式的能力大幅增强”, not “格式卡提示词后置”.
   - Omit developer-only changes unless they fix a user-visible failure.
   - Save multiline notes in a temporary UTF-8 Markdown file; do not rely on a complex inline shell argument.
9. Push the current branch to `origin`.
10. Dispatch `.github/workflows/release.yml` with:
   - `versionName`: next unused version without `v`.
   - `releaseNotes`: load the exact Markdown file from step 8.
11. Monitor workflow through completion. On failure, inspect the failed job, fix the cause, commit and push the fix, then dispatch an unused version if a tag was already created.
12. Recheck the final published tag range against the coverage checklist, then run `python .agents/skills/chatbar-release-publish/scripts/verify_release.py --repo SaltyFishOTL/ChatChatBar --version <version>`. Completion requires all gates:
    - workflow succeeded and stable tag exists;
    - every user-visible commit in the final adjacent-tag range is represented in the Release Notes;
    - published Release body is non-empty, starts with `## 更新内容`, and contains user-facing bullets;
    - GitHub API returns that body;
    - `github.com/<owner>/<repo>/releases.atom` contains the same note text for the tag, proving the app fallback can consume it;
    - asset `ChatBar-<version>.apk` exists.
13. If verification reports missing, incomplete, or mismatched notes, repair the Release with `gh release edit v<version> --notes-file <file>`, then repeat the range audit and verification. Never report success from workflow inputs, dispatch output, or asset presence alone.
14. Pull the workflow-created version bump with `git pull --ff-only`, remove temporary notes files, then confirm local workspace is clean and matches `origin`.

Report version, commit, workflow result, Release URL, APK asset, exact user-facing notes, and verification result.

## Client Reality Check

GitHub Release body existence does not prove the app can display it. When the app reports empty notes:

1. Read `chatbar-app-update`.
2. Verify API and Atom output separately with `python .agents/skills/chatbar-release-publish/scripts/verify_release.py`.
3. Inspect the installed app version and its `AppUpdateChecker` primary/fallback paths.
4. State clearly when a fix requires publishing and installing a newer client; editing server-side notes cannot change retrieval code already installed.
