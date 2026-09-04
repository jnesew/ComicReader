# Changelog

All notable public changes to ComicViewer for Android are recorded here.

## Unreleased

### Reader

- Added cover-aware two-page spreads with natural LTR and RTL placement, standalone landscape
  pages, spread navigation, and shared zoom and pan controls.
- Added forward navigation from the end of an issue into the next available issue in its series.

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
