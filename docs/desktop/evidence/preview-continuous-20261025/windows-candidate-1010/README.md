# Internal Desktop candidate 1010

Built source: **946c3791153634b0af65286313a497d141d5c598**. Desktop version `0.1.0-preview.1010`, Windows version `0.1.1010`, ordinary `desktop` packaging identity. This is an **unpublished internal candidate**, not an installed upgrade or Preview acceptance.

## Actual build and lifecycle results

- Offline JDK17 app-image build: exit0, 152.93 seconds, 777 frozen production input files. Read-only package audit: exit0, **217 files / 63 JARs / 21,161 class entries**. These are ZIP entries, not unique classes.
- Two native app-image cold starts in a newly owned **synthetic** environment: normal main window, selected-root exclusion, normal close, launcher exit0, maximum two owned processes, zero remaining. Three independent companion executions (seed and two verifies) exit0 and retain the invented card/session/message/draft, image/bootstrap and three invented Windows-protected values. They are not JUnit tests and do not add to earlier test totals.
- Complete image file lengths/hashes match after both launches. The external synthetic companion is not in the product. The lifecycle-only harness copies selected observation functions; it has no MSI install/repair/uninstall functions. Ordinary packaging identity does not authorize ordinary user-data access: LOCALAPPDATA/APPDATA/home/temp/bootstrap are explicitly task-owned.
- The exact manual entrypoint passes actual **Windows PowerShell 5.1** `-PreflightOnly`, exit0. That preflight launches nothing; the Data/update/support-copy human check remains **PENDING**. The two native launches, not this preflight, prove observed startup.

## Genuine MSI RED and corrected build invocation

The first MSI attempt used an external I-drive build directory. Jpackage produced an unsigned MSI, but the existing safety tool rejected it because its input/workspace/output were outside `app/desktopApp/build`. The Gradle invocation exited1; an explicit diagnostic reproduced the exact path assertion before mutation. This was a caller directory error, not an unexplained safety failure. The original unsigned artifact and both failure logs remain; no guard was relaxed and no final MSI was published from that invocation.

A separate new repository-owned build directory then ran the unchanged Gradle packaging/safety flow: **exit0, 178.85 seconds**. The final MSI is **166,498,304 bytes**, SHA-256 `ef421e7ac4d7abe8ef11fc8a87b3fc39a62753dc4b0a9cb2eb222fe5d9bd3ae8`. Read-only MSI verification confirms the ordinary product/upgrade identity, per-user installation, separate program directory, two shortcuts, recursive cleanup removal, downgrade block and removal inside the transaction. The synthetic failure action is absent; CAB length/hash matches the safety receipt. Signing was not performed. The MSI-build main JAR is byte-identical to the main JAR in the native-tested app-image.

**No MSI was executed in this checkpoint.** Successful build/static correction does not prove installation, upgrade, rollback, occupied-file handling or uninstall. The earlier synthetic rollback **registration failure remains FAIL**, including the separately retained partial repair result. No registry/ACL/security changes, elevation, repeated repair or ordinary installation took place here.

## Evidence and limits

`RAW_INDEX.json` binds selected byte-exact **synthetic** build/lifecycle text and source snapshots. Filenames inherited from the synthetic probe may contain `private`; their selected contents are invented fixture status/task paths, not real user data. No package binaries, real archives, extracted records, protected blobs, private baseline or real-data logs are included. `SOURCE_IDENTITY.json` pins five related source blobs and the 777-file input manifest. The content review is explicit selection plus known-pattern checks, not universal secret-absence proof.

No new JVM/JUnit suites ran: this checkpoint packages unchanged production code and performs native build/lifecycle/static checks. Earlier media-fix 99-test and integrated headless results retain their own exact source identities. No Provider generation action was requested; no full network capture, physical Android, Windows reboot/power-cut/cross-account, installer acceptance or human Data-page result is implied. No tag, Release, package upload, merge or baseline/parity promotion occurred.
