# Refactor implementation status

Branch: refactor/reader-library-ownership. Base: v1.1.0 (80b7c94), including the
already published release/1.1 README/screenshot commits through 8624230.

## Completed

- R0: source-security and database policy scripts pass. Direct SDK baseline build
  passes; all 80 existing JVM tests pass.
- R1: full GitHub CI passed at d8ec11c (run 34263255542). Extracted comic editor, reader navigation/settings dialogs, cover loader,
  grid adapter, and library query/grouping controller.
- Dialogs emit commands to their caller. Grid favorites emit a command rather
  than writing SQLite. HomeView retains presentation and view preferences.
- Cover cache publication is serialized on the main thread and checks closure.
  Page/direction dialog callbacks reject a replaced document.

- R2: import/backfill and folder scans have separate coordinators and task ownership.
  MainActivity supplies picker results, status callbacks and a reader-busy predicate.
  Scan completion now also checks its generation. Database shutdown waits off-main
  for all database-using workers; source files are still never deleted by forgetting.
  Full GitHub CI passed at a239be8 (run 34263691114). Device/provider tests remain outstanding.

- R3/R4: MainActivity now composes ReaderSession and ReaderController. The session
  owns one DocumentResources holder per open document; continuous buffering shares
  that holder. Progress capture, continuous-series loading and borrowed indexing
  have separate controllers. Preview closes before its document. Request identity
  rejects stale completions after cancellation/retry, including the same URI.
  Index callbacks check generation and exact resource identity; model publication
  occurs on the main thread. Adjacent-load failure closes both resources.
- Direct SDK compilation and all 86 JVM tests pass, including request cancellation,
  duplicate completion, resource closure, spread-end progress and placeholder
  persistence cases. Source-security and database policy scripts pass.

- R5 harness: seven production Android SQLite integration cases compile. Added a
  platform-only instrumentation runner and CI packaging check; no new dependencies.
  Database delegate extraction is intentionally pending the actual device baseline.
  Only a named-fixture constructor was added to LibraryDatabase; schema/SQL unchanged.
- R6: pure ContinuousPageMap owns issue/local/global indexing and anchor restoration.
  All 91 JVM tests pass, including prepend, eviction, index-count changes, unavailable
  one-page issues, missing anchors and boundary round trips.
- F1 preparation: localized store text, existing icon/selected captures, release-note
  drafts, recipe template, canonical unsigned Gradle release command, Git-checkout
  reproducibility and signed-rebuild verification commands. See FDROID-READINESS.md.

## Verification limits

Pinned Gradle 8.14.4 is not cached and network download is unavailable here.
Direct SDK builds use installed Java 17 compiler modules and Android SDK 36,
Build Tools 35.0.1. They verify compilation but do not replace lintRelease,
R8/resource-shrunk release testing, or F-Droid reproducibility.
No Android device/emulator is attached. Dialog, grid and lifecycle device checks
remain required. No release/signing/production branch changes were made.

## Remaining

- Run the actual Android database baseline, extract the R5 delegates, rerun that suite.
- Run final pinned Gradle lint/build and instrumentation packaging, plus release-device
  coverage. The successful remote CI runs above cover R1/R2 only.
- Complete the exact screenshot-source inventory and pinned AGP artifact notice check.
- Select release version/code, verify the real signing certificate, test the recipe and
  signed candidate against the independent F-Droid rebuild, then submit when authorized.
- Upload later local checkpoints: automatic approval review rejected creating the GitHub
  tree for jnesew/ComicReader, citing insufficient explicit authorization for that
  destination and possible disclosure. No alternate upload route was attempted.
  Remote branch remains at a239be8. Local commits retain the subsequent work.
