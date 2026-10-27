# Desktop internal app-image 1004

Source `ec39c141761c55790e3fe604bae05f735856f7df`; internal synthetic packaging identity only. No MSI was installed or repaired during these runs. This does not close the existing Windows Installer rollback failure.

- Actual app-image build: exit 0; 217 packaged files; embedded Desktop version `0.1.0-preview.1004`, installer version `0.1.1004`, source commit as above. Package inputs and output file hashes are retained separately.
- Two actual launches (`initial-1004`, `interactive-1004`): suspended launcher assigned to an owned Windows Job before resume, exact isolated environment, main window observed, selected data root independently proven busy, normal close, launcher exit 0, maximum two owned processes and zero remaining. No Provider action was triggered.
- Seed and independent post-close repository verification: exit 0, synthetic card/session/message/draft/image/bootstrap and three invented Windows-protected credentials retained. The retained output files whose names contain `private` contain synthetic-run status or shutdown stages, not real user data or credentials.
- Initial bounded PrintWindow capture succeeded for the owned synthetic window only. Image stays local; a screenshot of an early empty timeline is not a completed data-page smoke test.
- Computer Use subsequently found the exact owned app-image window. Windows frame capture timed out twice; accessibility exposed only a window/pane. No guessed click or credential entry followed. Data-page/update-page UI navigation remains NOT TESTED in this run. The probe's normal-close request was issued and all owned processes exited.

The private runner is copied here as the exact synthetic probe source. Only `launch-close` was executed; installer/repair/uninstall branches were not executed. GUI observations are separate from JVM tests; no JUnit test count is assigned to these launches. Studio results are not counted as Desktop evidence.

Real phone archives, imported real credentials, decrypted records, native real-credential blobs and real-run logs/XML are excluded from this directory. Normal installation, fault rollback, N→N+1 installer upgrade, physical Android and power-loss behavior are not proven by this app-image run.
