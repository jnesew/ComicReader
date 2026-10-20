# ComicViewer 1.2.0

Release candidate: versionName `1.2.0`, versionCode `4`. No release has been published.

## Smoother reading

Page buffering offers Standard, Increased and High. Device default selects High on capable
8 GB-class devices, using the Pixel 8a as the user-tested memory baseline. Smaller devices keep
Standard. Your saved Standard, Increased or High selection takes priority; choose Device default
to restore automatic selection.

Extra decoding pauses when the app is backgrounded or memory is under pressure. Buffering checks
Java heap headroom, native allocations and system memory while keeping visible pages prioritized.

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

## Upgrade and validation notes

The application ID remains `io.github.jnesew.comicviewer`. Use the existing signing key to upgrade
an installed release and retain settings, library state and document grants. This candidate uses
database schema 10; upgrading from 1.1.0 preserves legacy completion once and existing layout choices.

The user reported a major usability benefit from higher buffering on Pixel 8a. This observation
does not establish equivalent CPU performance or memory behavior on every 8 GB device. The default
uses a memory capability rule, not a device/SoC benchmark ranking. Low-memory and native-allocation
pressure checks remain active for every buffering selection.

Connected Android instrumentation, broader device comparisons and the R5 database baseline remain
outstanding. R5 delegate extraction and F-Droid signing/recipe/submission checks are separate gates;
their completion is not claimed by these release notes.
