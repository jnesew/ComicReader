# ComicViewer user guide

## Import comics

Use the **Add comics** plus icon in the library toolbar to select one or more CBZ, ZIP, supported EPUB, or PDF files through Android's
system document picker. ComicViewer remembers Android's document grant so a title can normally be
reopened without selecting it again.

The folder icon opens **Library folders**. Add one or more folders through Android's picker;
ComicViewer scans them recursively on launch or return to the library, skips unchanged files, and
reconciles verified exact copies with earlier imports. The manager shows each folder's location and
scan status, with actions to rescan, reconnect, or remove a folder. It never requests access to all
device storage. An existing saved folder is retained automatically when upgrading.

Removing a folder stops scanning it and releases its access after the reader has finished using it.
It never changes source files or erases reading history. If a verified identical copy remains in
another selected folder, ComicViewer uses it with the same progress, bookmarks, favorites, and edits.
An independently accessible manual import can also keep a comic readable. Otherwise the entry stays
in the library as unavailable until its source is reconnected.

## Library

The Titles view supports search, favorites, reading-status filters, cover-size choices, and sorting
by recent reading, import time, title, or progress. Use the visible **Titles / Series** selector to
switch views. Opening a title marks it as started and restores
its saved page and view state.

Series view groups titles conservatively using supported embedded metadata or folder structure.
Standalone titles remain separate. **Edit comic** changes the displayed comic title, chooses an
existing or new series and issue number, restores automatic grouping, or explicitly keeps the
comic standalone. **Use original title** restores the detected title. These edits change only
ComicViewer metadata and never rename or modify the comic file.

The Favorites filter in Titles shows favorite comics. In Series it shows favorite series and
favorite standalone comics. Series favorites are independent: starring a series never stars its
issues, and new issues appear in that saved series automatically. Opening a favorite series shows
all its issues; you can then apply search or reading-status filters. Returning restores the parent
view's search, filter, and scroll position. Use each card's options menu to change its favorite state;
a small star beside its title indicates a favorite without covering the artwork.

**Unread** is the explicit read status, independent of saved reading position. Progress tracks appear
only after reading has started or the title is marked read. Cards show a separate issue number when
structured metadata supplies one. Dates embedded in filenames or titles remain part of the title;
ComicViewer does not guess publication dates from them.

Long-press a title or series, or use its options menu, to forget it. Forgetting deletes
ComicViewer's local reading state and caches but never the original comic files. Tapping an
unavailable title also offers the same choice while retaining the option to reconnect or rescan
its source folder instead.

Rescanning an accessible folder can automatically reconnect an issue whose file moved or whose
old location became unavailable. ComicViewer compares the full file contents with an identity it
saved while the old file was readable; it retains reading progress, read status, bookmarks and
your edits. A matching filename or cover alone is never enough. Recent imports up to 128 MiB are
fingerprinted in the background, and older accessible imports are gradually covered on subsequent
launches or scans. Files without a saved full fingerprint, including larger files, may still need
their folder reconnected or the original document imported again. Incomplete scans never establish
that an issue was deleted.

After a complete folder scan proves that files are no longer present, **Review unavailable
comics** appears in the library menu. It allows selecting and forgetting those stale entries in
one operation. ComicViewer excludes titles made unavailable merely because folder access was
stopped, revoked, or could not be verified by a complete scan.

## Reading modes

**Single page** mode displays one page at a time. **Two-page spread** keeps the cover and landscape
pages alone, pairs subsequent portrait pages, and reverses their visible sides for RTL reading.
**Continuous** mode stacks pages vertically and decodes only visible and nearby regions. Fit width
is intended for tall strips: width is constrained while the page retains its full scrollable
height.

**Reader options → Page buffering** offers Standard (the original behavior), Increased (roughly
one screen ahead), and High (up to three screens ahead with some behind). **Device default** uses
Standard below the 4 GB RAM class, Increased from 4 GB, and High from 8 GB. An explicit saved choice
always takes priority; choose Device default to restore automatic selection. Extra preparation
pauses under memory pressure and while the app is backgrounded. Higher settings may prepare
fewer tiles on memory-limited devices. Buffering never changes saved position or read status.

In paged modes, advancing beyond the final page or spread opens the next issue, while moving back
from the first page opens the final page or spread of the previous issue. Android's system Back
action still returns to the library. Continuous mode buffers the adjacent issues and ordinary
vertical scrolling crosses an issue separator in either direction. Loading and retry states appear
at the boundary when needed. An unavailable issue is shown as a named notice in its normal series
position. Scroll or navigate forward/backward to pass it, or choose **Retry** to try opening it
again. Notices cannot be bookmarked and do not change the missing issue's saved progress or page
count. Series navigation never wraps.

Each title remembers its page, page-relative scroll position, zoom, zoom-gesture lock,
favorite state, and page bookmarks. Layout and direction follow the global reader defaults
until you choose a per-title setting. The layout control and reading-direction dialog both
offer “Use global default” to restore inheritance. An explicit per-title Auto direction
still follows publication metadata even if the global default is right-to-left.

Reader Options provides a global default zoom for titles without remembered view state, and for all
titles when per-title zoom memory is disabled. Opening precedence is remembered per-title zoom,
then the configured global default, then the built-in Fit width fallback. Continuous scroll starts
fitted to width when no per-title zoom is restored.
Reader Options also provides a default layout (single page, spread, or continuous) and
default direction (Auto, left-to-right, or right-to-left). Changing either affects titles
that still use the global default. Titles read before this update retain their previous
saved layout; unopened titles follow the new default.

Reading position and read status are separate. Use a title's menu to mark it read or
unread without moving its saved page. A series menu can mark every currently imported
issue read or unread; any newly imported issue starts unread, so a previously caught-up
series will show its new unread count. A rediscovered moved issue retains its status.
Moving forward past the end of an issue marks it read. In continuous mode, scrolling
through its bottom or forward into the next issue does the same. Jumping straight to
its last page or previewing it does not mark it read.

## Controls

| Gesture or control | Result |
|---|---|
| Tap the left or right edge | Previous or next page or spread, adjusted for reading direction |
| Horizontal swipe while fitted | Previous or next page or spread |
| Drag | Pan a zoomed page or scroll vertically |
| Pinch | Zoom around the gesture focus |
| Double tap | Zoom in or return to Fit width |
| Center tap | Show or hide reader controls |
| Bottom reading-layout control | Choose single-page, two-page spread, or continuous mode |
| Fit/zoom control | Select fit or zoom and lock or unlock touch zoom |
| Page slider | Preview pages while dragging and open on release |

The reader toolbar has a ribbon icon for the current page's bookmark and a separate star for the
current comic's favorite state. They toggle independently and follow the active issue during series
reading. Unavailable notices cannot receive page bookmarks.

The reader menu provides jump to page, the bookmark list, **Edit comic**, Auto/LTR/RTL
reading direction, and Reader options. Reader options contains background colors, volume-key
navigation, tap zones, screen-awake behavior, and external-keyboard shortcut capture.

## Formats and limitations

- CBZ and ZIP pages are ordered naturally by path and filename.
- EPUB support covers EPUB 3 fixed-layout publications and conservative image-centric spine pages.
  Scripted, encrypted/DRM, remote-resource, unsafe-path, layered, and general reflowable EPUBs are
  rejected.
- PDF pages are rendered with Android's platform PDF renderer.
- CBR/RAR and CB7/7z are not supported.

Very large or malformed documents may be rejected by the documented
[hostile-input limits](SECURITY_MODEL.md) instead of risking unbounded memory or storage use.
