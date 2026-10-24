# Guide for AI agents

This is the canonical repository guidance for AI agents. Keep agent instructions here;
keep public documentation useful to users and developers.

## Project and setup

ComicViewer is an offline Android comic reader. The repository is named ComicReader;
the application ID is `io.github.jnesew.comicviewer`. It uses one Gradle application
module, Java 17, native Android Views, platform document APIs, and SQLite.

Read [CONTRIBUTING.md](CONTRIBUTING.md), [docs/BUILDING.md](docs/BUILDING.md), and
[docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) for contribution, build, and input limits.
The pinned toolchain is JDK 17, Gradle 8.14.4, Android Gradle Plugin 8.13.2,
SDK Platform 36, and Build Tools 35.0.1. Supported Android APIs are 26–36.
`gradle.properties` is the source of truth for version names and codes.

## Local development workflow

- Inspect the current branch, working tree, and applicable instructions before editing.
  Reuse the local checkout when suitable, preserve unrelated changes, and keep work focused.
- Develop and verify locally. Use remote CI as additional evidence when available;
  do not assume an SDK, dependency cache, device, or emulator is installed.
- Work sequentially by default. Avoid unrelated reformatting, speculative abstractions,
  and framework migrations. Separate behavior fixes from mechanical extractions.
- Keep commits coherent when committing is part of the task. Push, release publication,
  signing, and distribution submission must follow the authorization in the current task.
  Historical notes do not grant authorization or assign unfinished work.
- Record what changed, which checks actually ran, and any verification limits. A successful
  build does not establish device behavior or distribution acceptance.

## Versioning

Use stable `MAJOR.MINOR.PATCH` versions. Choose the bump from the complete planned
release, not from the number of commits or branches:

| Bump | Use for | Example from 1.3.0 |
| --- | --- | --- |
| Patch | Backward-compatible bug fixes, internal cleanup, build tooling, or documentation without a new feature | 1.3.1 |
| Minor | Backward-compatible features or substantial improvements to existing functionality | 1.4.0 |
| Major | Deliberate breaking changes, such as dropping supported Android versions or requiring users to reset/reimport data | 2.0.0 |

A schema migration that preserves existing data is not by itself a major change.
Explicit user requests for a version or bump take precedence over these defaults.
Ask before choosing a major bump when the task has not already authorized the breaking
change; a version number does not authorize expanding the task's scope.

Use `scripts/prepare-obtainium-release.sh --bump patch|minor|major` from a clean
checkout when starting a release cycle. It compares the local and published versions,
increments the selected component, resets lower components, and sets the Android
version code one above the highest local/published code for the current application ID.
`gradle.properties` remains the source of truth. Bump once on the release staging
branch; feature and bugfix branches targeting it inherit that version. Do not bump
again for each merged fix. A new externally distributed APK must have a higher code;
never reuse a published version/code for changed contents.

Keep the matching release notes, CHANGELOG entry, and English/Finnish metadata with
the bump. Staging notes can remain drafts, but finalize them and the release date
before public preparation. See [docs/RELEASING.md](docs/RELEASING.md) for the workflow.

## Product boundaries

- Preserve offline operation and zero requested Android permissions, including network,
  broad storage, and media permissions. Use Storage Access Framework grants.
- Keep the APK free of third-party runtime libraries, analytics, ads, accounts,
  subscriptions, Google Play Services, Firebase, and cloud dependencies.
- Retain bounded memory use and hostile-input checks at read/allocation boundaries.
  Treat comics and document-provider metadata as untrusted; preserve `InputLimits`,
  interruption handling, partial-file cleanup, and decoder synchronization.
- CBR/RAR, CB7/7z, general reflowable EPUB, and cross-device synchronization are outside
  the current scope. Do not expand formats or reader features as part of cleanup work.
- Use ordinary classes and explicit constructor dependencies. Do not introduce Compose,
  a Kotlin migration, Room, dependency injection, an event bus, or navigation frameworks
  without a task that calls for them.
- Keep user-visible strings in resources and update English and Finnish together when practical.

## Architecture and ownership

The Java source root is `app/src/main/java/io/github/jnesew/comicviewer/`.

| Area | Responsibility |
| --- | --- |
| `MainActivity` | Lifecycle, intents and document pickers, platform window/back behavior, composition, short event forwarding |
| `ui/` and `ui/dialog/` | Presentation and commands; recycled cover binding belongs to `CoverThumbnailLoader` and `LibraryGridAdapter` |
| `library/` | Separate query/grouping, import/backfill, and folder-scan coordination |
| `reader/ReaderController` | Reader commands and UI integration |
| `reader/ReaderSession` and `DocumentResources` | Adopted documents, renderers, previews, and resource lifetime |
| Other `reader/` controllers | Continuous-series policy, borrowed indexing, and reading-progress snapshots/saves |
| `data/LibraryDatabase` | Public SQLite facade, schema/migrations, persistence, and atomic source reconciliation |
| `document/` and `archive/` | Format parsing, document access, and synchronized decoder ownership |
| `render/` and layout utilities | Canvas interaction, shared frame/tile budgets, bounded caches, and page geometry |

Preserve these rules when changing the affected paths:

- Transfer a newly opened document/renderer to the session exactly once. Close unadopted
  results on stale completion or failure. Active and continuous-buffer references share
  one `DocumentResources` owner; indexing borrows it and must not close it.
- Validate generation and exact request/resource identity on asynchronous publication,
  cancellation, retry, and task-map removal. URI equality alone cannot identify a retry.
  Publish UI-owned reading state on the main thread.
- Close previews before their documents. Stop database-using workers before closing the
  helper, without blocking the main thread on long I/O. Preserve independent import,
  scan, open, adjacent-load, index, and cover work ownership.
- Prefetch/index work must not mark an issue opened/read or advance progress. Unavailable
  notices must not overwrite saved page counts, position, read status, titles, or bookmarks.
- Series traversal never wraps. Backward entry selects the final page/spread; RTL changes
  presentation without reversing series order. Continuous changes preserve the anchor as
  document identity, local page, and ratio. Reuse `ContinuousPageMap`, `PageLayoutEngine`,
  and `SpreadPageLayout` rather than duplicating their policies.
- Preserve progress-save debounce and spread-end semantics, partial database writes,
  title overrides, per-title/global preference precedence, and explicit read status.
- Only a completed scan of the still-configured tree can confirm missing files. Lost
  access, provider errors, cancellation, incomplete traversal, or stopping a folder are
  not deletion evidence. Require full-content equality for duplicate/reconnection decisions.
- Retain canonical identities, manual imports, user edits, bookmarks, favorites, covers,
  and indexes during reconciliation. Preserve reader-busy guards and delayed old-tree
  grant release. Forgetting library entries never deletes or changes source comic files.
- Keep database name, schema, migrations, column meanings, and transaction boundaries
  stable during extractions. Do not substitute whole-row writes or `CONFLICT_REPLACE`
  for targeted updates. Schema changes require an explicit feature need and migration.
- Database delegate extraction (the historical R5 follow-up) still requires the real
  connected Android baseline before extraction and the identical suite afterward.
  The current schema is 11; older plans describing schemas 8 or 10 are historical context.
- Preserve shared visible-frame budgeting and the common speculative cache ceiling.
  Do not change memory limits, prefetch distance, zoom limits, or decoder concurrency
  incidentally. Keep pressure trimming/cooldown, background suspension, and cached raster
  fallback behavior intact.

## Verification

Use focused tests for behavior changes and inspect UI-only mechanical moves. Test observable
behavior, stale-result rejection, and resource disposal; avoid assertions about helper names
or private call sequences. Documentation-only changes need reference and diff checks rather
than an Android rebuild.

For application changes, run the relevant checks. The full release gate is:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

The reproducibility script and `./build-release.sh` require a clean committed tree and compare
or build that commit. Do not discard local changes to satisfy them. `build-local.sh --unsigned`
is a direct SDK convenience build; it does not replace the optimized Gradle release.
Keep strict dependency verification, pinned checksums, and repository filters intact.

Run `./gradlew --dependency-verification strict connectedDebugAndroidTest` on a test device
or emulator for the production SQLite/renderer suite. `assembleDebugAndroidTest` only packages
it. The Python database-roundtrip script exercises supplementary SQL policies; it does not
execute production Java, migrations, Android SQLite, or rendering. Do not report either as
passing connected tests. Manual rendering, memory, provider, lifecycle, and upgrade checks
require actual device evidence.

## Documentation and private material

- Keep source, build tools, contributor documentation, security/privacy policies, user guides,
  release notes, listing metadata, and asset attribution in the tracked repository.
- Keep personal device checklists, maintainer to-do lists, work logs, historical handoffs,
  and environment-specific notes in the Git-ignored root `private/` directory. It is optional
  local material, absent from fresh clones; public docs, scripts, and CI must not depend on it.
- Preserve useful historical material locally when consolidating it. Put current agent
  guidance here, not in new handoff/prompt/status documents. Private archives are context,
  not a second source of agent instructions.
- Keep signing keys, credentials, local configuration, and build outputs untracked. Preserve
  public license/provenance notices and distinguish verified facts from pending checks.
