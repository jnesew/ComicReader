# ComicViewer 1.5.0

Release candidate prepared 2 November 2026.

This release adds more control over the library layout and improves rendering
recovery and memory budgeting.

- Choose **Cover only**, **1 line**, or **2 lines** from **Library menu → Title display**.
  Progress and status move up when title rows are removed and remain aligned across
  cards. Two lines preserves the previous layout and remains the default.
- Routine folder scans no longer show a notice on the main library screen. Incomplete
  scans and unavailable folder access still show a notice that opens the folder manager.
  One folder finishing successfully no longer clears another folder's warning.
- Visible pages, detailed tiles, sampled backing images, and slider previews share a
  session memory budget. Inexpensive page coverage appears before detailed tiles,
  and allocation failures retry at a cheaper resolution.
- Background trimming, cancelled requests, and transient decoding failures recover
  without publishing stale results. Healthy pages remain available when a neighboring
  page cannot be decoded.
- Reuse extracted archive pages and resident tiles to reduce repeated work when
  returning to a page or changing scale. Continuous reading stays anchored while
  background indexing updates page dimensions.
- Recycled covers receive the current thumbnail result, and obsolete or background
  thumbnail work is cancelled and restored when needed.
- Refresh the screenshot gallery, including light appearance and all three title modes.

The reader continues to work offline without requested Android permissions or
third-party runtime libraries. Existing libraries, settings, reading positions,
favorites, and bookmarks are preserved. Android 8.0–16 remains supported.
