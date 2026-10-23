# ComicViewer 1.2.0

## Smoother reading

Page buffering offers Standard, Increased and High. Device default selects Increased for
the 4 GB RAM class and above, High for 8 GB and above, and Standard below 4 GB. Detection allows
for kernel-reserved RAM. Your saved Standard, Increased or High selection takes priority; choose
Device default to restore automatic selection.

Extra decoding pauses when the app is backgrounded or memory is under pressure. Buffering checks
Java heap headroom, native allocations and system memory while keeping visible pages prioritized.
Recently visible tiles can use the same extra-buffer budget for backward turns. Raster pages reuse
cached pixels while a new sampling level loads, reducing dark flashes near frame-budget boundaries.

## Reader preferences and read status

- Set a global page layout and reading direction, then override them per title when needed.
- Mark an issue or a complete series read/unread without moving its saved reading position.
- See unread and caught-up counts for series. New issues imported later start unread.
- Reading to the end through forward navigation marks an issue read; visiting its final page
  directly does not.

## Automatic recovery of moved comics

Rescanning an accessible folder can reconnect an unavailable comic after a verified full-content
match, preserving reading state, bookmarks, favorites and your metadata edits. Full fingerprints
are retained for accessible imports up to 128 MiB through bounded background work. A filename or
cover match alone never authorizes recovery; files without a retained identity keep their old entry.

## Internal improvements

The ownership refactor separates library imports, folder scans, reader controls and open documents,
with stronger cancellation and stale-result handling. English and Finnish store metadata and
build-verification tools are prepared for F-Droid.
