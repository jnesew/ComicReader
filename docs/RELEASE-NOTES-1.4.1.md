# ComicViewer 1.4.1

Released 29 October 2026.

This maintenance release improves page preparation and cached-tile reuse while
keeping the existing reader features and memory limits.

- Nearby content receives priority during page preparation, with bounded tile
  reservations and protection against repeated speculative decoding.
- Preparation uses the expected next frame's scale and crop, including PDF pages.
  Tall pages and PDFs prepare nearby bands while retaining visible-work priority.
- Existing memory-pressure trimming, cooldown and background suspension remain in place.

Developer diagnostics are available through a separate offline Render Bench APK
with synthetic fixtures and local result exports, and an optimized Reader Test
APK for side-by-side checks. Benchmark fixtures and the runner are excluded from
the regular reader APK.

Project-resolved Guava in the Android build/test tooling is patched for
CVE-2026-102554, with strict dependency verification retained. The separate
Gradle-bundled library boundary is documented in the
[build instructions](BUILDING.md#dependency-verification).

The reader continues to work offline without requested Android permissions or
third-party runtime libraries. Existing libraries, settings, reading positions,
favorites and bookmarks are preserved.
