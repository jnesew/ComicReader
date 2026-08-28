# Changelog

All notable public changes to ComicViewer for Android are recorded here.

## 1.0.0 - Unreleased

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
