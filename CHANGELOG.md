# Changelog

All notable public changes to ComicViewer for Android are recorded here.

## 1.4.0 - 2026-10-28

### Library and appearance

- Added System default, Light, and Dark appearance choices. The library and its dialogs
  follow the selected appearance; reader controls and the selected canvas color stay independent.
- Simplified the Titles/Series tabs and sort/filter controls. File and folder import remain
  directly accessible from the toolbar and the empty library.
- Moved issue numbers onto compact cover badges and cover actions to the top corner,
  with the same actions available by long-pressing a card.
- Replaced in-progress page counts with a bar in the Unread/Completed status area.
  Progress includes saved scrolling within a page, including single-page long strips,
  while completion remains an explicit reading state.
- Fixed cover alignment after scrolling mixed PDF and numbered-comic results down and back up.
  Missing issue numbers, reading status, and favorites no longer change card heights.
- Refreshed the screenshots using public-domain sample comics, including light appearance.

### Development

- Updated Gradle to 8.14.6 and Tink's generated Protobuf classes, and replaced
  deprecated Gradle property syntax. Unsafe legacy generated code now fails the build.
- Updated vulnerable transitive Android build and test-tool libraries, removed obsolete
  verification pins, and added resolved dependency checks to CI and release builds.
- Added Android regression coverage for both appearance palettes, recycled status and issue
  badges, and mixed-format cover alignment across cover sizes and larger Finnish text.

## 1.3.1 - 2026-10-25

### Fixes

- Fixed missing GIF cover thumbnails and failed GIF comic imports. Covers retain
  their existing crop policy with bounded decoding for formats without region support.
- Fixed the doubled percent sign in the English and Finnish Actual size zoom option.

### Development

- Added a tracked local release preparation script with explicit version bumps,
  published APK version checks, reproducible builds, and verified release bundles.
- Release preparation prompts for signing passwords with hidden terminal input by
  default, reuses the store password when possible, and validates the private key
  before building. Unattended operation requires explicit environment-based configuration.
- Added one-time signing setup and a tracked public certificate pin, removing
  repeated keystore selection and manual certificate configuration.
- Fixed signature-aware APK comparison on newer Python versions that clear ZIP
  filename flags, with real SDK signing and comparison regression tests.
- Documented release preparation and versioning rules for release staging branches.

## 1.3.0 - 2026-10-24

### Library

- Added multiple library folders with per-folder scan status, removal, and a shared rescan action.
  Existing single-folder selections migrate automatically. Verified identical copies across
  folders share one library entry and retain reading state when another copy becomes the source.
- Added independent series favorites. Favoriting a series includes future issues without changing
  individual comic favorites; opening it from Favorites shows all its issues.
- Refreshed the library toolbar, Titles/Series selector, cover cards, issue labels, and icons.
  Favorite markers no longer cover the artwork, and unread cards hide empty progress tracks.
- Preserve search, filters, and scroll position when returning from a series and across recreation.
- Fixed cover alignment in compact series grids, including mixed reading states and larger text.

### Reader

- Added a comic favorite action beside the separate page-bookmark control.
- Fixed zoom buttons starting from the wrong scale after Fit page, Actual size changing after
  relayout, and continuous zoom shifting the focused content.
- Fixed stale tiles remaining distorted after background indexing corrects page dimensions.
- Preserve precise scrolling and flinging through very long continuous collections.
- Fixed previews for very tall PDF pages and restored EPUB/ComicInfo parsing on Android while
  retaining rejection of DTDs and external entities.
- Bound retained full-image fallback bitmaps to the renderer's working-set budget and validate
  actual image headers before decoder allocation, including while page dimensions are provisional.

### Maintenance

- Added real Android coverage for multi-folder reconciliation, migrations, library navigation,
  mixed-format rendering, maximum-size pages, and long-document scrolling.
- Fixed host-side connected-test dependency resolution.

## 1.2.0 - 2026-10-20

### Reader

- Retain recently visible tiles within the existing extra-buffer limit for backward page turns,
  and reuse cached CBZ/raster pixels while changing sampling levels during scrolling or zooming.
- Added Standard, Increased and High page buffering. Device default selects Increased for
  the 4 GB RAM class and above, High for 8 GB and above, and Standard below 4 GB. Detection allows
  for kernel-reserved RAM; explicit saved choices take priority. Runtime memory safeguards
  remain active regardless of the selected tier.
- Added a Device default option to restore automatic buffering selection. Backgrounding suspends
  speculative work; Java heap, native allocation and system pressure checks limit extra decoding.
- Added global layout and reading-direction defaults, with per-title overrides and inheritance.
- Completion follows deliberate forward navigation beyond an issue or an explicit read action,
  independently of the saved page position.

### Library

- Added read/unread actions for individual issues and complete series, with unread and caught-up
  counts. Newly imported issues remain unread in an already-read series.
- Rescans can reconnect moved, unavailable comics after a verified full-content match, preserving
  positions, read status, bookmarks, favorites and metadata edits. Fingerprints for accessible
  imports up to 128 MiB are retained through bounded background work.

### Maintenance

- Separated library imports/scans, reader controls, indexing and open-document ownership, with
  cancellation and stale-completion checks. Database delegate extraction remains a separate
  follow-up pending the connected Android baseline.
- Prepared English/Finnish store metadata, release notes and build-verification tools for F-Droid.

## 1.1.0 - 2026-09-08

### Library

- Added consistent forget actions for unavailable titles and complete series, including explicit
  confirmation that comic files are never deleted.
- Added a review-and-remove flow for entries proven missing by a complete folder scan. Titles made
  unavailable by lost or released folder access are excluded from bulk removal.
- Added a per-comic metadata editor for custom display titles, automatic/manual/standalone series
  grouping, existing-series suggestions, issue numbers, and restoring the detected title.

### Reader

- Failed opening/retry attempts preserve saved titles, grouping, availability, and page indexes.
- Extend visible-frame tile budgeting to CBZ/raster images and avoid offscreen high-resolution
  tile requests that compete with currently visible pages.
- Mirror the page slider, preview position, and bottom navigation arrows for RTL reading.
- Unavailable series issues now have named notices that ordinary navigation can pass, with Retry
  and no changes to the missing issues' reading progress, page counts, or bookmarks.
- Budget PDF tile resolution across all visible pages together to prevent cache churn in
  continuous and two-page views, including viewports straddling page boundaries.
- Added cover-aware two-page spreads with natural LTR and RTL placement, standalone landscape
  pages, spread navigation, and shared zoom and pan controls.
- Added forward navigation from the end of an issue into the next available issue in its series.
- Added backward navigation from the first page into the final page or spread of the previous issue.
- Continuous mode now buffers adjacent issues and crosses issue boundaries through ordinary
  scrolling, with inline separators and loading, unavailable, and retry states.
- Fixed continuous scrolling stalling on short single-page issues and restoring into the previous
  issue when the buffered content is shorter than the viewport.
- Simplified the reader menu and moved Background color and Hardware shortcuts into Reader options.

## 1.0.3 - 2026-08-31

### Reader

- Fixed flashing dark tile placeholders when zooming PDF pages in paged or continuous mode.

## 1.0.2 - 2026-08-30

### Reader

- Added a configurable global default zoom with per-title remembered zoom taking precedence and
  Fit width as the built-in fallback.

## 1.0.1 - 2026-08-29

### Compatibility

- Changed the Android application ID from `com.localtools.comicviewer` to
  `io.github.jnesew.comicviewer` before F-Droid submission, giving the app a project-owned namespace.
- Android treats 1.0.1 as a separate app from 1.0.0. The retired 1.0.0 build must be uninstalled
  first; its settings, reading state, and folder grants do not migrate automatically.

## 1.0.0 - 2026-08-29

Initial public release.

### Library

- Searchable, recycled cover grid with configurable density, filters, sorting, favorites, and
  persistent reading progress.
- Multi-file import through Android's system document picker.
- Optional recursive scan of one user-selected folder without broad storage access.
- Conservative series grouping from embedded metadata or folder structure, with manual overrides.
- Duplicate reconciliation that preserves progress, bookmarks, favorites, covers, and document
  access.

### Reader

- CBZ, ZIP, fixed-layout or image-centric EPUB, and PDF support.
- Paged and continuous-scroll modes with memory-bounded tiled decoding.
- Fit width, fit page, actual size, explicit zoom controls, pinch zoom, double-tap zoom, and an
  optional per-title gesture lock.
- Per-title progress, page-relative position, zoom, reading direction, bookmarks, and favorites.
- Page previews while scrubbing, natural page ordering, immersive controls, configurable canvas
  colors, tap zones, volume keys, and external-keyboard shortcuts.
- English and Finnish resources.

### Privacy, security, and release engineering

- Offline operation with no requested Android permissions, accounts, ads, analytics, telemetry,
  proprietary Google runtime dependency, or third-party production library.
- Storage Access Framework file selection and explicit backup/device-transfer exclusions.
- Bounded hostile-input handling for archives, EPUB metadata, PDFs, images, provider streams, and
  cache writes.
- Pinned Gradle wrapper and Android build tooling, strict dependency verification, read-only CI,
  source/security checks, and byte-reproducible unsigned release builds.
- Custom adaptive and legacy launcher artwork.
