# Phase 3F interoperability artifacts

These test-only artifacts were generated from the canonical fixture in
`sharedCore/src/testFixtures/kotlin/com/example/chatbar/interop/Phase3FInteropFixture.kt`
at Desktop source commit `88bf0b154b6472085acface0879d50fdc607c826`.

- `desktop/`: produced through `DesktopAppContainer`, the Desktop character
  transfer/materialization stack, `DesktopCharacterCardPngRenderer`, and the
  shared FormatCard/WorldBook transfer services.
- `android/`: produced by `Phase3FAndroidInteropTest` through `ChatBarApp`,
  `CharacterCardTransferService`, `AndroidCharacterResourceStore`, the Android
  PNG renderer, and the shared FormatCard/WorldBook transfer services on
  `Pixel_3a_API_34` (`emulator-5554`, API 34).

The platform directories intentionally remain separate. JSON exports can differ
in generated `exportedAt` values, and PNG pixel encoding/rendering is
platform-specific. Tests compare the embedded package semantics and logical
resource bytes while ignoring only documented local IDs, timestamps, and
materialized resource paths.

`SHA256SUMS.txt` records the exact committed artifact bytes and sizes.
