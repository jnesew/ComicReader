**ComicViewer: refactor design and F-Droid readiness**

Design date: 8 September 2026. Intended implementer: Astra Medium. This is a proposed implementation handoff; no application changes, releases, or submission have been made.

**1. Recommendation and baseline**

Complete a bounded refactor of application orchestration, library presentation, database responsibilities, and continuous-page mapping. Then prepare distribution metadata and verify the release build in F-Droid's environment. Refactoring is a maintenance decision, not an F-Droid requirement.

Keep the single Gradle application module, Java 17, native Android Views, platform document APIs, existing SQLite database, and zero third-party runtime dependencies. Use ordinary classes and constructor arguments. Do not introduce a dependency injection framework, event bus, Compose, Kotlin migration, Room, navigation framework, or repository interface for every class. The aim is that a reader change, scan change, and dialog change each have a clear home.

Verified repository: [jnesew/ComicReader](https://github.com/jnesew/ComicReader)

| Baseline | Verified state |
| --- | --- |
| Public main and v1.1.0 tag target | 80b7c94a85b5868834c660b8f4b472ecb10f53ed |
| release/1.1 | 86242301306f0e92cf5cf8007c16577b4e300fc9 |
| Difference between those commits | README and screenshots only; application/build source is identical |
| App identity | io.github.jnesew.comicviewer; visible name ComicViewer |
| Version properties | versionName 1.1.0; versionCode 3 |
| Database | comicviewer.sqlite3; schema version 8 |
| Toolchain | JDK 17; Gradle 8.14.4; AGP 8.13.2; compile/target SDK 36; Build Tools 35.0.1; min SDK 26 |

Before implementation, refresh remote refs and check for newer work. Start an isolated refactor branch from current main, incorporating the existing screenshot commits through normal history integration if still needed. Do not reset another chat's checkout. Preserve released tags. No separate recovery framework is needed: commit coherent stages and push checkpoints to the working branch.

Code inspection used the clean release/1.1 checkout, checked against live GitHub branch and tag data. No build, device test, signed-APK comparison, or F-Droid build was run for this design.

**2. What to refactor**

Line counts are diagnostic context, not acceptance quotas.

| Current class | Lines | Decision | Intended result |
| --- | ---: | --- | --- |
| MainActivity | 2,737 | Highest priority | Android lifecycle, intent/picker bridge, window configuration, screen composition, and short event forwarding |
| LibraryDatabase | 1,294 | Refactor carefully after real database tests | Keep its public facade and one SQLiteOpenHelper; extract cohesive SQL operations behind it |
| ComicCanvasView | 1,105 | Narrow extraction in this pass | Separate continuous issue/page addressing; retain View, gesture, transform, and drawing behavior |
| HomeView | 885 | Refactor presentation responsibilities | View construction and binding; separate cover loading, grid adapter, and library query/grouping |
| EpubParser | 632 | Defer | Already a focused parser; possible future resource/XML helpers do not justify touching security-sensitive behavior now |
| ReaderScreen | 486 | Small cleanup only | Remain the reader UI; expose a narrow canvas-facing API if needed by the extracted controller |
| ComicArchive | 485 | Preserve | Existing ComicDocument implementation is already an appropriate format boundary |
| TileRenderer | 458 | Preserve rendering policy | Retain per-frame budgeting, caches, fallback, and decoder synchronization |
| PdfComicDocument | 423 | Preserve | Platform PDF ownership and synchronization remain in the document implementation |
| LibraryFolderScanner | 246 | Preserve traversal boundary | Caller owns scan policy; scanner continues to enumerate provider entries |
| PagePreviewLoader / ReaderPreferences | 223 / 218 | Preserve | Focused classes; adjust callers and lifecycle integration only |
| Existing layout, series, zoom, input-limit utilities | Small focused classes | Reuse | Avoid creating competing implementations of already extracted policies |

In particular, deferred scroll buffering/flicker fine-tuning stays deferred. Do not increase memory budgets, prefetch distance, zoom limits, decoder parallelism, or accepted EPUB formats as part of this refactor. CBR, PDF outlines/ToC, cloud services, and new reader features remain outside scope.

**3. MainActivity extraction map**

These names express responsibilities; minor naming adjustments are fine. Keep the boundaries stable.

| Proposed component | Existing work to move | Ownership and interface |
| --- | --- | --- |
| ui/dialog/ComicEditorDialog | showComicEditor, editorLabel, editorRadio | Receives editor values and series suggestions; returns a validated edit command. It does not write SQLite or rebuild the reader itself. |
| ui/dialog/ReaderOptionsDialog | showReaderOptions, theme/direction choices, keyboard capture | Displays current settings and emits commands. Group related small dialogs in this class initially; do not create a framework. |
| ui/dialog/ReaderNavigationDialogs | jump and bookmark dialogs | Receives counts/bookmarks and emits page selections; controller handles navigation and persistence. |
| library/LibraryImportCoordinator | importComics, processLibraryItem, cover/metadata backfills, their job deduplication | Owns background import/backfill operations and documents it opens solely for those operations; reports results without retaining Activity views. |
| library/FolderScanCoordinator | folder choice state, cooldown/cancellation, processScannedEntry, duplicate probes, rename handling, scan finalization | Owns scan generation, active task, pending old permission release, scan counts, and reconciliation policy. Uses the existing scanner and fingerprint helper. |
| reader/ReaderController | ReaderScreen/Canvas listener handling, layout/zoom/bookmark/favorite commands, navigation, metadata-edit refresh | Connects reader session and UI. It does not own independent copies of session documents or continuous resource maps. |
| reader/ReaderSession | openComic/activateArchive lifecycle, active document/resource identity, index task lifecycle, preview replacement, close/trim | Sole owner of adopted reader document resources. Publishes current reader state and handles stale completions. |
| reader/ContinuousSeriesController | begin/leave continuous session, adjacent requests/retry, issue selection, boundary state, buffer membership | Owns series policy and pending adjacent-request identities. Requests resource adoption/release through ReaderSession. Never independently closes an adopted document. |
| reader/ReadingProgressController | scheduleSave/saveNow, flush on transition/pause/home | Captures current reading state and saves it through the existing database operation. Preserve 400 ms debounce and current pageEnd/scroll-ratio semantics. |
| reader/DocumentIndexCoordinator | common active/adjacent indexing callback plumbing | Borrows a session-owned document; cancellation and completion are tied to that exact resource. It must not close borrowed documents. |

Keep Android picker launching, ACTION_VIEW handling, platform back registration, edge-to-edge configuration, system bars, and keep-screen-on in MainActivity. These are legitimate Activity duties.

Avoid recreating the original monolith as ReaderController. Opening resources belongs to ReaderSession; continuous traversal policy belongs to ContinuousSeriesController; saves belong to ReadingProgressController. A class that owns all three is not the intended result.

Extraction order inside each component: move behavior and fields together, preserve calls and conditions, compile, then simplify duplicated plumbing. Do not combine extraction with altered algorithms.

Import/backfill and reader indexing may share metadata persistence helpers, but their resource lifetimes differ. An import operation opens and closes its own document; reader indexing borrows one. Preserve this distinction explicitly instead of writing a universal loader with ambiguous ownership.

**4. Reader state, resource lifetime, and concurrency**

Use a small main-thread-owned session state with these conditions: library/closed, opening request, reading session, and closed lifecycle. An opening request can coexist with the currently displayed reading session until activation; do not discard the old session merely because a new request began.

Each request needs a session generation and request identity. Each adopted resource needs an identity distinct from its URI: retrying the same URI must not allow an old callback to modify or close the replacement.

| Resource/state | Owner | Required lifetime rule |
| --- | --- | --- |
| Pending newly opened document/renderer | Opening task until adoption | Transfer ownership once on valid completion; otherwise close all created parts, including exception paths |
| Adopted document + TileRenderer | ReaderSession, through one DocumentResources holder | Active issue references the same holder as the continuous buffer; no duplicate owner |
| PagePreviewLoader | ReaderSession | Close old preview before switching its document; discard old preview completions |
| Continuous order, boundaries, requested neighbors | ContinuousSeriesController | Main-thread state; requests include generation, series, URI, and request identity |
| Index work | DocumentIndexCoordinator | Borrow resource; reject work/results after cancellation, eviction, replacement, or shutdown |
| Mutable reading state | Reader session/progress controller on main thread | Workers return results or snapshots; do not mutate a UI-owned ReadingProgress object in the background |
| Library import/backfill work | LibraryImportCoordinator | Independent of reader open generation; its own operation cancellation and per-title job deduplication |
| Folder scan work | FolderScanCoordinator | Separate scan generation and configured tree; a new reader request cannot accidentally invalidate unrelated import work |
| SQLite helper | Activity-scoped composition owner | Remains open until work that can access it has stopped; shutdownNow alone is not proof that workers have exited |

Use explicit callbacks and a small injectable scheduler/dispatcher only where needed to test timing. Preserve existing executor counts and queue separation initially: archive opening 1, adjacent loading 2, indexing 1, library work 2, scans 1, covers 2. Renderer and preview classes retain their own current execution rules.

Cancellation is cooperative, so every asynchronous publication must validate that it still belongs to the current operation. Removing a task from a map must also check request identity; an obsolete completion must not remove a newer retry for the same URI.

Shutdown sequence: invalidate operations and stop accepting new commands; capture/flush the last valid reading state; detach callbacks and clear canvas/preview references; cancel pending work; close owned resources under their existing synchronization rules; finish database shutdown only after database users have stopped. Do not block the main thread waiting for long I/O. Make resource close idempotent and document the safe order. Inspect actual renderer/document locking before deciding exactly where a close runs.

Preserve the current behavior contracts:

- Prefetching or indexing a neighboring issue does not mark it opened, advance progress, or complete it.
- An unavailable placeholder cannot overwrite saved count, page, completion state, title override, or bookmarks.
- Series traversal does not wrap. Moving backward opens the final page or spread; RTL changes reader presentation without reversing series order.
- Continuous prepend, buffer eviction, index updates, and successful retry preserve the viewport anchor as document identity + local page + ratio.
- Keep the current active/previous/next buffer policy, including continued loading through several short single-page issues and unavailable notices.
- Save the current issue before switching ownership; preserve existing two-page pageEnd save behavior.
- Preserve opening zoom precedence: remembered title setting when enabled and applicable, then global default, then built-in fallback.
- Reader saves must not overwrite independently updated cover/index/metadata state. Test the existing partial-write behavior and title-edit interaction before moving it.

Some lifecycle paths deserve scrutiny during extraction: current active and adjacent indexing have different cancellation checks, and some callbacks mutate progress off-thread. Treat a demonstrated bug as a separately identified fix with a regression check, rather than silently changing behavior under an extraction commit.

**5. Library and home-screen design**

FolderScanCoordinator should retain the existing conservative source rules:

- Only a completed scan of the still-configured tree can confirm missing files.
- Lost access, incomplete traversal, provider errors, cancellation, and stopping a folder are not proof of deletion.
- Full content equality is required for duplicate reconciliation; a filename or sample hash is insufficient.
- Preserve canonical URI relinking, manual-import identity, title overrides, bookmarks, favorite state, and surviving cover/page-index data.
- Keep the existing reader-active/opening guard around destructive reconciliation.
- Do not release the old tree grant early when switching folders. Keep the pending-release and successful-scan behavior.
- Forgetting removes library state and owned caches; it never renames, moves, or deletes source comic files.

MainActivity supplies a narrow reader-busy predicate and the picker result. The scan coordinator does not inspect ReaderScreen or ComicCanvasView.

Split HomeView into:

| Component | Responsibility |
| --- | --- |
| HomeView | Toolbar/search widgets, folder status, empty state, series-back UI, grid container, view-state preferences |
| LibraryGridAdapter | Recycled title/series cards and click events |
| CoverThumbnailLoader | Existing bounded cache, background decode, in-flight deduplication, binding tokens, close/trim |
| LibraryQueryController | Query/sort/filter/grouping, using LibraryDatabase and SeriesOrganizer; returns a library snapshot |

Views and adapters emit favorite/edit/open/forget commands; data changes are handled by the controller/coordinator layer. Move query logic first without simultaneously changing its scheduling. An asynchronous query redesign can be a later measured change. Preserve selected series, search, sort, filter, density, labels, and recycled-cover identity behavior.

CoverThumbnailLoader must never put a completed bitmap into a recycled card for a different item, or update a closed screen. Preserve cache sizes and cover decode sizes.

**6. Database decomposition**

Keep LibraryDatabase as the public facade and SQLiteOpenHelper for this pass. Keep database name, schema version 8, migrations, column meanings, indexes, URI identities, preferences, and storage locations unchanged.

Extract package-private delegates:

| Delegate | Methods/responsibilities |
| --- | --- |
| ReadingStateStore | Reading state writes, bookmarks, reading direction; retain targeted update semantics |
| ComicMetadataStore | Detected/custom title, automatic/manual/standalone series, favorites, normalization |
| PageIndexStore | Page-index reads/replacement and archive indexing state |
| LibrarySourceStore | Scanned source records, fingerprints, canonical URI moves, exact duplicate merge, scan completion/availability |

LibraryDatabase can retain library queries, row mapping, import initialization, schema setup, and facade forwarding. Extract a shared row mapper only if delegates actually require it. Leave public nested result types in place unless moving them materially helps callers.

Each delegate receives the existing SQLiteDatabase handle for an operation; it must not create a second helper or connection. Keep multi-table transactions intact. In particular, mergeExactDuplicate, relinkCanonicalUri, finishFolderScan, forgetAll, and replacePageIndex must each retain one explicit transaction boundary. Calling several independent store methods is not an adequate replacement for one atomic operation.

Do not change schema to make the refactor easier. Do not replace targeted updates with whole-row writes or CONFLICT_REPLACE. Do not revise duplicate-winner policy during extraction.

**7. Canvas scope**

Extract a pure ContinuousPageMap from installContinuousDocuments and the issue/global/local addressing arrays. It receives ordered issue identities and page lists and provides issue boundaries, flattened pages, and conversions. It does not open files, query SQLite, hold Activity, or own TileRenderer. The view retains the mapping from issue identity to borrowed renderer.

Use an immutable ViewportAnchor value for document key, local page, and ratio where existing repeated argument groups justify it. Test prepend, removal, replacement, missing anchors, single-page issues, and reindexing. Preserve existing clamping/fallback behavior.

Keep PageLayoutEngine and SpreadPageLayout as the geometry implementations. Do not rewrite fit/zoom transforms, gestures, scroller behavior, separator rendering, or tile scheduling now. A later pass could extract viewport math, but combining that with session ownership and database changes increases regression surface unnecessarily.

TileRenderer.drawPages currently budgets rendered/raster scale across all visible requests for a renderer. Do not turn it into independent per-page budget decisions. Keep RenderedTilePolicy and the repaired cache/fallback behavior unchanged.

**8. Stages and verification**

Use one refactor integration branch, for example refactor/reader-library-ownership. Each row is a coherent, buildable checkpoint; larger rows can take several commits. Use a subsequent chore/fdroid-readiness branch for distribution work. No requirement to create a PR or change branch per helper class.

| Stage | Work | Gate before progressing |
| --- | --- | --- |
| R0 | Establish baseline; record active branch/SHA, existing tests, runtime dependency graph, and device behavior | Existing source checks/build gate passes, or record a concrete environment block; no unverified implementation claims |
| R1 | Extract dialogs and cover loader/adapter | Compile; inspect dialog cancellation, editor save/reset, recycled covers, and both dialog entry points |
| R2 | Extract library query/import/backfill and folder scan orchestration | Unit checks for scan generation/finalization and full-equality reconciliation policy; device rescan/folder replacement/manual import smoke test |
| R3 | Extract ReaderController, ReaderSession, resource holder, progress controller; keep continuous logic temporarily inside the session if needed | Deterministic stale-open/cancel/close tests, progress snapshot tests; device open/home/pause/reopen/preview checks |
| R4 | Extract ContinuousSeriesController and shared reader indexing coordination | Test stale adjacent retry/eviction callbacks; run series and unavailable-issue device cases |
| R5 | Exercise real Java SQLite code, then extract database delegates | Actual database integration checks plus upgrade smoke test; schema and transaction behavior unchanged |
| R6 | Extract ContinuousPageMap and finish dead-code/API cleanup | Mapping/anchor tests; full release candidate regression gate |
| F1 | Canonical release build, listing files, notice corrections, metadata recipe | Source recipe build and local signed-candidate comparison pass; public asset verification follows release |
| F2 | Tag/publish the validated release and submit its exact build recipe when authorized | Released APK/tag/version/certificate match; F-Droid review and publication tracked separately |

The existing scripts/verify-database-roundtrip.sh creates sample tables and executes copied SQL through Python sqlite3. It is useful for Unicode and policy examples, but does not execute LibraryDatabase Java, Android ContentValues, actual migrations, or the complete schema. It cannot be the sole gate for R5.

Add a small device/emulator instrumentation harness that calls the production LibraryDatabase against an isolated database; a platform instrumentation harness is sufficient. Avoid runtime dependencies. If this cannot run in the implementation environment, finish the harness and provide exact execution instructions; do not mark the database refactor verified. Establish it before changing database internals.

Minimum database integration cases: custom title survives scan/reopen and reset works; partial progress saves preserve cover/index and metadata; duplicate merge unions bookmarks and preserves canonical selection rules; URI relink updates related tables atomically; interrupted/inaccessible scan cannot enable confirmed-missing cleanup; missing review excludes manual or uncertain copies; bulk forget deletes only intended rows/caches; schema-8 reopen and representative existing upgrade paths work. Extend existing parser/geometry tests instead of duplicating their implementations.

Use hand-controlled executors/fakes at ownership boundaries for session tests. Test behavior and resource disposal; do not assert helper names or private call sequences. UI-only dialog moves need focused inspection, not a large new test suite.

Existing full release gate:

~~~bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
~~~

Run the full gate at baseline and the final candidate; use targeted tests/builds between stages. The reproducibility script requires a clean committed tree. Add the new database harness and ownership tests to the relevant gates. Do not repeatedly rebuild the toolchain or run every expensive check after each mechanical edit.

Use docs/1.1-DEVICE-TEST-CHECKLIST.md as the starting point for manual coverage. Add explicit upgrade-from-v1.1.0 state retention, interrupted opening/scanning, retry of the same URI, and pause/destroy during pending work.

Final device coverage must include CBZ, image-based EPUB, and PDF; single/spread/continuous; LTR/RTL/Auto; long strips; consecutive missing issues; several short one-page issues; metadata edits and folder rename/duplicates; previews and zoom lock; rotation, system Back, hardware navigation, pause/resume; stationary high-zoom PDF/CBZ flicker regressions and ordinary scroll loading transitions. Test a release-optimized APK as well as unit/debug behavior.

**9. F-Droid: actual readiness gaps**

F-Droid accepts free/open source apps built from public source and reviews dependencies and asset rights. The current source has an MIT license, no third-party runtime libraries, no requested manifest permissions, and no network updater. This is a favorable starting point, not an inclusion approval. F-Droid does not require an architectural refactor. [F1]

| Area | Current state | Planned work |
| --- | --- | --- |
| Public source and identity | Present; GitHub-owned reverse-domain app ID | Preserve identity and existing signing key; use a tested public release commit |
| Runtime freedom/privacy | No declared runtime dependencies or permissions | Verify releaseRuntimeClasspath and final APK again; no special F-Droid flavor is currently needed |
| Build tooling | Pinned Gradle/AGP/SDK and strict verification | Confirm exact recipe works in F-Droid environment; retain checksum/repository restrictions |
| Listing metadata | No fastlane/metadata tree | Add English and Finnish text, selected screenshots, icon, release notes |
| Version extraction | Properties are read through Gradle providers | Configure UpdateCheckData to read gradle.properties |
| Reproducibility | Existing script compares two unsigned builds using one selected builder | Verify real release path, real Git checkout metadata, and F-Droid rebuild against the signed APK |
| License notices | Present, but AGP row uses blanket SDK terms; historical thumbnails lack itemized provenance | Correct tooling notice against component license/POM; document exact screenshot artwork sources or replace unclear material |
| Submission | No recipe prepared in this turn | Prepare/test fdroiddata metadata; later submit for review |

AGP's source is Apache-2.0 licensed; the notice should not describe its license solely as Android SDK terms. Verify the pinned artifact's POM/NOTICE while correcting the table, and distinguish SDK distribution terms from component licenses. This is documentation cleanup, not a need to replace AGP. [F7]

For screenshots, reuse the recent selections, including the current editor image; exclude the uncropped notification image already rejected. Carry Pepper & Carrot credit and CC BY 4.0 information into the store asset attribution. Identify each historical comic used in thumbnails and its source, or use the already clearly licensed sample art. Do not add another round of photography unless rights or legibility require it.

**10. Signing and canonical release build**

Recommend developer-signed reproducible distribution from the first F-Droid release. This offers a path for existing Obtainium/GitHub installations to receive compatible updates using the same package and signing certificate. F-Droid-signed distribution is an alternative, but a different signing key prevents ordinary in-place updates across those channels. Reproducibility is recommended by F-Droid, not universally mandatory for inclusion. [F2]

Do not hand the private signing key to F-Droid. Its upstream-binary verification route uses a versioned APK URL and an allowed certificate fingerprint; publication depends on the source rebuild matching the upstream APK. [F3]

Project-specific tasks:

1. Make Gradle release the canonical distributable build for the next release. Preserve minification/resource shrinking initially. The direct build-local.sh route invokes javac/D8 and packages resources differently; treat it as a developer convenience unless its output is deliberately chosen and independently verified as the official distribution path.
2. Build a clean unsigned Gradle release from the final tagged Git commit; sign that APK with the existing key. Record exact JDK and SDK platform revision as well as the already pinned versions.
3. Improve reproducibility verification to include independent clean Git checkouts of the same commit. The current script uses git archive twice; both copies lack Git metadata. AGP can embed VCS information, so matching two exported trees does not establish equality with a normal checkout release. Keep any archive test as a supplementary check. [F3]
4. Compare the actual signed release against the independently rebuilt unsigned APK using signature-aware verification, then run the same check through F-Droid's recipe. Comparing unsigned SHA-256 values alone is insufficient.
5. Build Tools 35.0.1 deserves a focused signing check: apksigner 35 changed ZIP alignment behavior. Evaluate the documented --alignment-preserved signing option with the current verifier and check the result, rather than assuming signing only appends bytes. Do not rewrite an already published APK. [F6]
6. Add a reproducibility-to-release check to the release procedure. Existing security-build CI checks unsigned builds; it does not prove equality with a published release asset.
7. Test upgrading the current signed v1.1.0 installation to the new signed candidate, retaining library data, URI grants, reading position, bookmarks, edits, favorites, and settings.

The v1.1.0 release page reports certificate SHA-256:
2b1a3a161ca63ffc15718f5be8897597dc33d3fce663634e219cef8950b8c80f

This is a reported value, not an independently verified certificate in this review. Verify it against the actual public APK before filling AllowedAPKSigningKeys. Never substitute a debug/test certificate.

Version 3 is the current source versionCode. Select the next code above every distributed APK's actual code; use 4 only if that remains the maximum when release preparation happens. The next versionName is a release decision; do not bake 1.1.1 or 1.2.0 into extraction work.

If reproducibility is blocked, isolate the difference and report it. Do not silently switch to a new F-Droid key, change app ID, remove checksum verification, or relax source scanning to make a green result.

**11. Store files and recipe**

Create plain text/image files under fastlane/metadata/android/en-US/ and fi-FI/. Fastlane software itself is unnecessary. Use these files; include them in the submitted release commit, since F-Droid reads release metadata. [F4]

| Relative file | Content |
| --- | --- |
| title.txt | ComicViewer; at most 50 characters |
| short_description.txt | At most 80 characters; for example: Offline comic reader for CBZ, PDF and image-based EPUB |
| full_description.txt | At most 4,000 characters; features, supported EPUB subset, Android requirement, local file access, no accounts/network/services |
| changelogs/&lt;versionCode&gt;.txt | At most 500 characters, filename is the integer versionCode |
| images/icon.png | Existing launcher artwork exported for the listing |
| images/phoneScreenshots/* | Selected current phone screenshots in PNG/JPEG |
| images/featureGraphic.png | Optional; not a submission blocker |

Keep captions/attribution accurate, and leave ordinary screenshot storage in the app repository. Finnish metadata complements the existing Finnish UI. Additional languages or promotional artwork can wait.

The submission recipe belongs in the fdroiddata repository as metadata/io.github.jnesew.comicviewer.yml. Keep a review copy in project docs if useful. This is the intended shape, not a validated submission file:

~~~yaml
Categories:
  - Reading
License: MIT
SourceCode: https://github.com/jnesew/ComicReader
IssueTracker: https://github.com/jnesew/ComicReader/issues
Changelog: https://github.com/jnesew/ComicReader/releases

AutoName: ComicViewer
RepoType: git
Repo: https://github.com/jnesew/ComicReader.git

Binaries: https://github.com/jnesew/ComicReader/releases/download/v%v/ComicViewer-Android-v%v.apk
AllowedAPKSigningKeys:
  - REPLACE_WITH_VERIFIED_LOWERCASE_CERTIFICATE_SHA256

Builds:
  - versionName: REPLACE_WITH_RELEASE_VERSION
    versionCode: REPLACE_WITH_RELEASE_CODE
    commit: REPLACE_WITH_FULL_RELEASE_COMMIT_SHA
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$
UpdateCheckData: 'gradle.properties|comicViewerVersionCode=(\d+)|.|comicViewerVersionName=([0-9.]+)'
CurrentVersion: REPLACE_WITH_RELEASE_VERSION
CurrentVersionCode: REPLACE_WITH_RELEASE_CODE
~~~

Use the full commit hash for the initial build and test the property regex/tag filter. Confirm the category against current fdroiddata. Keep descriptions in localized upstream files. Add environment setup only as required by the tested F-Droid builder; the existing Gradle root/module layout and exact pinned tooling must be exercised there. Do not copy placeholder values into a submission. [F5]

F-Droid submission sequence:

1. Finalize refactor and release-build verification.
2. Commit store metadata, notices, release notes, and required build changes before tagging.
3. Prepare fdroiddata recipe; run fdroid readmeta, rewritemeta, lint, checkupdates, and build for the app using the documented environment.
4. Resolve scanner/build differences precisely. Do not add broad scanignore/scandelete entries as a shortcut. A verified Gradle wrapper is build bootstrap, not a bundled runtime library.
5. Publish the validated signed release when authorized, complete the upstream-binary comparison, and submit a merge request to fdroiddata. A request-for-packaging issue is a fallback if direct packaging cannot be prepared. [F8]
6. Respond to maintainer feedback, then confirm a production F-Droid build and listing. Passing local CI does not guarantee acceptance or a publication date.

**12. Handoff instructions for Astra Medium**

Implement R0–R6 in order after the user authorizes implementation. Work sequentially by default. Reuse the existing tested helpers and Android APIs. Preserve all behavior and schema invariants above.

At each checkpoint, state what moved, what owns the state now, tests actually run, and any remaining device verification. Commit/push normal feature-branch checkpoints so work can resume from Git. Avoid large cross-cutting reformatting and unrelated fixes.

If an extraction reveals a reproducible bug, document it and make a focused fix/checkpoint. Do not label changed behavior as a purely mechanical move. Do not expand the pass into a full application rewrite.

After the refactor candidate is verified, proceed to F1 preparation. Treat release publication and the fdroiddata submission as separate user-visible actions governed by the authorization available at that time. The current turn requests design only.

Refactor completion means: MainActivity no longer owns import/scan/reader machinery; ownership is unambiguous; HomeView no longer handles SQLite policy and decoding; database transaction and persistence behavior is tested; continuous addressing is isolated; no known behavior regressions remain. It does not mean every large file is small.

F-Droid readiness means: a specific public commit builds in its environment, the listing files are present, version discovery works, source/assets/dependencies are reviewable, and the chosen signature path is verified against the exact release APK. Submission and acceptance are later states.

**Sources**

Repository facts come from files at the verified commit and live GitHub refs/releases, not assumptions about an older development repository. Key source locations:

- [Java source tree](https://github.com/jnesew/ComicReader/tree/80b7c94a85b5868834c660b8f4b472ecb10f53ed/app/src/main/java/io/github/jnesew/comicviewer)
- [build.gradle](https://github.com/jnesew/ComicReader/blob/80b7c94a85b5868834c660b8f4b472ecb10f53ed/app/build.gradle)
- [verify-reproducible-build.sh](https://github.com/jnesew/ComicReader/blob/80b7c94a85b5868834c660b8f4b472ecb10f53ed/scripts/verify-reproducible-build.sh)
- [verify-database-roundtrip.sh](https://github.com/jnesew/ComicReader/blob/80b7c94a85b5868834c660b8f4b472ecb10f53ed/scripts/verify-database-roundtrip.sh)
- [v1.1.0](https://github.com/jnesew/ComicReader/releases/tag/v1.1.0)

External requirements checked on 8 September 2026:

- [F1] [F-Droid Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [F2] [F-Droid Quick Start Guide, signing/reproducibility and version detection](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F3] [F-Droid Reproducible Builds, upstream signatures and VCS metadata](https://f-droid.org/en/docs/Reproducible_Builds/)
- [F4] [F-Droid descriptions/graphics/metadata](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [F5] [F-Droid Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [F6] [apksigcopier upstream documentation, compare and Build Tools 35 alignment](https://github.com/obfusk/apksigcopier)
- [F7] [Android Gradle plugin source license example](https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-master-dev/build-system/gradle-core/src/main/java/com/android/build/gradle/BasePlugin.kt)
- [F8] [F-Droid developer FAQ, submission route](https://f-droid.org/docs/FAQ_-_App_Developers/)
