# Phase 7 B — authorized live smoke evidence

After the user personally saved the credential through Desktop's Windows-protected SecretStore
UI and explicitly authorized live testing, one guarded generation completed successfully.
This continues the historical pre-credential checkpoint in `35_PHASE7_B_CREDENTIAL_GATE.md`;
it is not the P7-B review checkpoint or Phase close.

- Same `feature/phase7-image-novelai` branch; no baseline promotion or parked-sync adoption.
- Explicit opt-in `:desktopApp:runNovelAiPhase7Smoke` runner delegates to the existing task
  runtime, SecretStore hydration, free-eligibility guard, global file lease and durable fuse.
  No credential argument, environment variable, ordinary JSON or diagnostic output is used.
- V4.5 Full, text-to-image, Normal Square 1024×1024, 28 steps, one sample, no guidance,
  zero automatic generation retries. Prompt: a ceramic cup still life, no people.
- Runtime preflight confirmed free eligibility; postflight confirmed active membership and
  unchanged Anlas. Generation completed and published a decoded image plus resolved-seed recipe.
- Durable Phase count: **1/8**; no pending request or blocked marker after completion.
- The initial runner invocation stopped at missing data-root acquisition, before creating the
  runtime or making network requests. The runner now creates its isolated output root first.
- Successful output is retained in ignored `app/desktopApp/build/phase7-live-data/images/`;
  its authority is `entities/desktop_phase7_novelai_smoke.json` under that root. Reuse it for
  local persistence, preview and metadata checks; no additional live images are needed for B.
- Verification: guarded task ended `COMPLETED`, Gradle `BUILD SUCCESSFUL`; fuse status and
  saved image/recipe checked without reading credential storage. No image or credential is
  committed to Git.

The opt-in runner is independent of normal `check`, `test`, build and application launch.
Every future live invocation still requires explicit user authorization and all Phase limits.

Before P7-B review, the existing image and recipe were copied, byte-verified, to ignored
`.phase7-live-data/` at repository root. The runner now uses that location so Gradle clean
cannot discard the reusable sample. The original build-directory copy remains intact.
This local retention operation sent no request and did not touch the global safety journal.
