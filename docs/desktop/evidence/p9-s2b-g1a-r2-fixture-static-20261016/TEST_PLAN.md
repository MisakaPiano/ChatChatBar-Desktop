# G1A-R2 test fixture regression SOURCE — NOT RUN

Only DesktopProcessLifecycleTest.kt changes relative to the reviewed R1 source draft.
Five added test methods exercise the actual withRoot helper, not an alternate policy:

1. Body returns normally but deliberately keeps a synthetic ownership: helper throws, directory remains, and the lock remains held.
2. Body already throws a known primary with an earlier secondary: exact original primary remains thrown, earlier suppression stays first, cleanup diagnostic is appended. Parameter cases cover both unannounced retention and finishSyntheticLifecycle's declared-incomplete root state.
3. Suppression-disabled primary: explicit test-only carrier preserves exact primary as cause/field and the cleanup Throwable as a separate field, with retained files and ownership. No silent loss of either diagnostic.
4. Body catches/ignores an incomplete-teardown failure and returns with the retained-root declaration: withRoot still rejects drain NOT CONFIRMED. Declaration protects files; it never authorizes green success.
5. Ordinary confirmed completion still cleans the synthetic temporary directory.

Regression roots in the first four tests deliberately have no background jobs or writers. Their finally helper closes ONLY their explicitly acquired test lock, reprobes ownership, then removes the synthetic directory. It does not release any production lock or clean a root with pending tasks. The existing real-container bounded teardown and ownership-before-container tests are unchanged.

Future validation requires a separately authorized safe window: Desktop main/test compilation, DesktopProcessLifecycleTest, and the prior G1A/R1 focused tests if Project approves. Preserve actual RED/XML/logs in a fresh evidence location. No test, compile, GUI, packaging or G1B run is authorized by this plan.
