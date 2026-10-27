# PREVIEW-B1 / D-041 bounded engineering and Windows evidence

**PROJECT CODE REVIEW REQUIRED — Gate B NOT SIGNED.** Base `243168e97609f45d8265a2e6ba77db09f6bfa125`. Tested correction and packaged source commit `a0b0a0707142a8b9a2c7bfd4d33acb106138ae0f`. Final evidence receipt SHA is in DELIVERY/final response. Branch feature/phase8-fish-audio only; remote desktop5ef95428 unchanged. Six pre-existing unknown attachment/handoff/cache files kept unread/unhashed/unchanged/unstaged. No same-directory parallel task observed; safe fast-forward only, no reset/force/upstream sync.

## Authority, actual defects and minimal change

Read CURRENT Source Map, baseline JSON, Current State, Roadmap, D-041,20 checklist,93 Preview policy and95 D-038. Formal validated baseline remains1.4.1. D-041's existing checker/panel/build identity and Windows app-image packaging were reviewed, not inferred accepted from their existence. Repository has no dedicated Desktop Windows packaging/process skill; the applicable app-update skill's Desktop section and Windows Computer Use skill were read. Its feature facts were updated with strict metadata rules; no Android workflow, updater download/install flow, Prompt, schema or data recovery code changed.

Existing checker intended one explicit unauthenticated Releases GET, desktop-only tags/channels, trusted browser release pages, bounded metadata, and no automatic download/install/restart. New independent tests found two real failures: JsonPrimitive.boolean/content coercion accepted wrong JSON field types, and UTF8 replacement decoding admitted corrupt notes. Initial actual run:3 suites45 tests,43 PASS/2 FAIL,exit1 (1m16s). raw/red preserves production/test snapshots, XML and command/log/exit. Correction uses strict UTF8 decoding and exact boolean/string token types, retaining absent/null notes as missing. It does not add a second update system, broaden trusted sources or change valid release behavior.

Final actual run: **3 suites /45 PASS /0 failures/errors/skips /exit0 (31s)**. Update10 (5 existing+5 new), Startup20, ProcessLifecycle15. Do not add RED45 to final45 or inherit earlier historical totals. Run-Tests.ps1 and raw/green contain actual commands/JUnit/source snapshots. Test fixtures are inline JSON/MockWebServer, no preset contents or real credential source.

## D-041 verification matrix

| Capability | Evidence/result | Boundary |
|---|---|---|
| Desktop SemVer/prerelease/stable ordering | Numeric preview10>2; stable>rc; invalid versions rejected | PASS bounded JVM protocol tests; not all possible SemVer extensions |
| Desktop-specific source/tag | Fixed MisakaPiano/ChatChatBar-Desktop releases API and desktop-v prefix; Android tags/drafts ignored | PASS; no real Release/Tag/API request made |
| GitHub identity and channel metadata | Strict booleans/strings, channel/tag consistency, duplicate versions, date validation, malformed JSON/UTF8 refusal | PASS; original2 RED fixed |
| Notes and absent notes | Parsed text and missing notes tested; panel statically displays notes/visible missing message using text | JVM/static PASS; actual rendered result NOT TESTED |
| Trusted browser destination | Exact HTTPS host/repo/tag, no userinfo/port/query/fragment/encoded-path substitution; button rechecks | JVM/static PASS; actual external browser handoff NOT TESTED, no browser/profile opened |
| New/no-new/empty channel | Selection results and loopback GET tests | PASS |
| Offline/timeout/cancellation | Closed local socket, injected250ms client timeout, NO_RESPONSE cancellation within3s | PASS; no false successful result |
| Redirect/retry/duplicate attempts | 302 and503 refused,408 follow-up cannot send another wire request; explicit later request succeeds, request counts bounded | PASS per-check; UI double-click suppression static busy guard only |
| Response limits | Content-length and chunked >1MiB rejected;100-entry ceiling/static notes limit retained | PASS focused limit tests/static review |
| No request credentials | GET/body0, no Authorization/Cookie observed in loopback requests | PASS for constructed default requests; not a universal arbitrary injected-client proof |
| UI lifecycle/manual-only | Panel coroutine cancellation, busy disabling, generic visible failure, no startup auto-check/download/install/restart reviewed | Static PASS; live Data/update page NOT TESTED |
| Packaged identity | Both actual app jars contain exact901/902 version and source commit; no development fallback, distinct Android baseline label | ARTIFACT_VERIFIED; screen text not captured |

## Actual Windows app images and normal shutdown

Build-Package.ps1 creates local **createDistributable app-images**, not MSI/EXE installers: Preview0.1.0-preview.901 (exit0/32s),902 (exit0/34s). Both use the exact source commit above; native package numeric core0.1.0 is independently configured from Android. Their217-file manifests, SHA/length and embedded desktop-build.properties are in raw/windows-901 and902. Actual executable paths are inside ignored build/preview-b1. Final verification confirmed all packaged bytes unchanged after execution. Packages are retained locally, not uploaded or published.

Windows-Probe.py creates a brand-new synthetic custom data root and bootstrap, isolated LOCALAPPDATA/APPDATA/USERPROFILE/HOME/TEMP/TMP, and child user.home via JAVA_TOOL_OPTIONS. All source data comes from explicit inline synthetic records: character, session, message/image reference, draft, legacy SaveSlot, player settings,1px PNG and silent WAV. No provider configuration/API credential exists. Credential directory contains only an explicitly opaque **noncredential sentinel**, never a protected credential or DPAPI payload. No actual DPAPI operation or existing install/root is needed.

Both real packaged applications opened targetable ChatChatBar windows. Windows Computer Use screenshot capture failed twice for901 (`FrameArrived timed out`, then `window capture timed out`); text-only accessibility returned only window/pane. Therefore no coordinates were guessed and Data/update-page navigation was not asserted. `raw/windows-ui-observations.json` is explicitly a transcription of actual tool responses, not invented raw screenshots. The skill requires using its JS APIs for Windows app automation; no direct PowerShell UI Automation fallback was used. This is an automation blocker, not evidence that the update page itself fails.

Both observed windows were normally closed with Alt+F4, never killed. Launcher exits0/0; fixed lifecycle traces show COMPOSE_RETURNED, drains, OWNERSHIP_RELEASED, MAIN_RETURNED and SAFE_EXIT_HANDOFF.902's actual CIM inventory shows launcher30388 and a same-executable child37040, consistent with a separate runtime host. No child handle was retained for its actual exit code: **JVM child exit codes UNKNOWN**, not inferred from launcher0. A module query on the launcher yielded no file/result, so it cannot support an in-process JVM claim. After both, scoped process inventory confirms no remaining launcher/runtime process. Root lock was actually unavailable while902 ran and available after close.901 ownership release is supported by its trace and successful exclusive second startup. No installer/registry mutation was performed.

## Preservation result and installer boundary

All **8/8 original synthetic business/media files** remained byte-identical after901 and902; bootstrap selected-root bytes and opaque noncredential sentinel also unchanged. App initialization added its normal derived/default files, separately inventoried. Two app-images with different Preview identities ran sequentially against the same custom root; this is **APP_IMAGE_SIDE_BY_SIDE_SAME_SOURCE_DIFFERENT_PREVIEW_VERSION**, not an in-place installer upgrade. All manifests/hashes/results are in raw/preservation.json and before/exit inventories.

The media check retains PNG/silent WAV bytes; it does not prove generated Fish MP3/voice-anchor playback or migration. The SaveSlot is minimal legacy inline JSON, not a v8 streaming package exercise. RAG/Memory and all user workflows are outside this small fixture's preservation claim. Those limits further prevent treating it as full supported-data installer acceptance.

**Real EXE/MSI clean install, Preview N→N+1 installer upgrade, uninstall retention, active protected credentials, actual DPAPI/profile migration and registry integration: NOT TESTED.** Environment-variable isolation does not create a separate Windows SID/HKCU or make an installer safe against existing registrations. No isolated VM/test OS account has been established, so that subtask stops before installation rather than touching the current Windows installation or SecretStore. The noncredential sentinel cannot prove retention of valid protected keys. These remain release blockers independently of checker tests/app-image retention.

## Next verification and stop

Project can review the minimal checker correction and real raw tests immediately. Remaining UI evidence needs a working screenshot/accessibility environment or a later explicitly scoped human Windows check for entering Data/update page and browser result display. Real installer acceptance needs a separate disposable Windows environment plus synthetic protected-credential strategy/authorization; it cannot be replaced by this side-by-side app-image result. No manual user action is required to finish this bounded source/evidence handoff, and no unsupported full Gate B PASS is claimed.

No real .cbbackup, production data root/key/Token, real Provider, GitHub Release/Tag/upload, G2C, desktop merge or baseline change. P11 PENDING/D-038 BLOCKED/Post-P8 HOLD/Public Preview HOLD remain.

**STOP: PREVIEW-B1 / D-041 PROJECT CODE REVIEW GATE.**
