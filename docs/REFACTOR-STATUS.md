# Refactor implementation status

Branch: refactor/reader-library-ownership. Base: v1.1.0 (80b7c94), including the
already published release/1.1 README/screenshot commits through 8624230.

## Completed

- R0: source-security and database policy scripts pass. Direct SDK baseline build
  passes; all 80 existing JVM tests pass.
- R1: extracted comic editor, reader navigation/settings dialogs, cover loader,
  grid adapter, and library query/grouping controller.
- Dialogs emit commands to their caller. Grid favorites emit a command rather
  than writing SQLite. HomeView retains presentation and view preferences.
- Cover cache publication is serialized on the main thread and checks closure.
  Page/direction dialog callbacks reject a replaced document.

## Verification limits

Pinned Gradle 8.14.4 is not cached and network download is unavailable here.
Direct SDK builds use installed Java 17 compiler modules and Android SDK 36,
Build Tools 35.0.1. They verify compilation but do not replace lintRelease,
R8/resource-shrunk release testing, or F-Droid reproducibility.
No Android device/emulator is attached. Dialog, grid and lifecycle device checks
remain required. No release/signing/production branch changes were made.

## Remaining

R2 library import/scan orchestration; R3/R4 reader ownership, continuous series
and indexing; R5 actual Java database harness and decomposition; R6 page mapping;
F1 F-Droid preparation and final verification. Follow REFACTOR-PLAN.md.
