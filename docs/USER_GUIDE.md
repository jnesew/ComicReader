# ComicViewer user guide

## Import comics

Use **Open comics** to select one or more CBZ, ZIP, supported EPUB, or PDF files through Android's
system document picker. ComicViewer remembers Android's document grant so a title can normally be
reopened without selecting it again.

The optional library-folder setting grants access to one user-selected folder. ComicViewer scans it
recursively on launch or return to the library, skips unchanged files, and reconciles exact copies
with earlier manual imports. It never requests access to all device storage.

## Library

The Titles view supports search, favorites, reading-status filters, cover-size choices, and sorting
by recent reading, import time, title, or progress. Opening a title marks it as started and restores
its saved page and view state.

Series view groups titles conservatively using supported embedded metadata or folder structure.
Standalone titles remain separate. Series name and issue order can be corrected from the title
menu.

Removing a title deletes ComicViewer's local metadata and caches but not the original comic file.

## Reading modes

**Paged** mode displays one page at a time. **Continuous** mode stacks pages vertically and decodes
only visible and nearby regions. Fit width is intended for tall strips: width is constrained while
the page retains its full scrollable height.

Each title remembers its page, page-relative scroll position, zoom, zoom-gesture lock, reading mode,
reading direction, favorite state, and page bookmarks.

Reader Options provides a global default zoom for titles without remembered view state, and for all
titles when per-title zoom memory is disabled. Opening precedence is remembered per-title zoom,
then the configured global default, then the built-in Fit width fallback. Continuous scroll starts
fitted to width when no per-title zoom is restored.

## Controls

| Gesture or control | Result |
|---|---|
| Tap the left or right edge | Previous or next page, adjusted for reading direction |
| Horizontal swipe while fitted | Previous or next page |
| Drag | Pan a zoomed page or scroll vertically |
| Pinch | Zoom around the gesture focus |
| Double tap | Zoom in or return to Fit width |
| Center tap | Show or hide reader controls |
| Bottom reading-mode control | Switch between paged and continuous modes |
| Fit/zoom control | Select fit or zoom and lock or unlock touch zoom |
| Page slider | Preview pages while dragging and open on release |

The reader menu also provides bookmarks, title favorites, series assignment, Auto/LTR/RTL reading
direction, jump to page, canvas colors, volume-key navigation, tap zones, screen-awake behavior, and
external-keyboard shortcut capture.

## Formats and limitations

- CBZ and ZIP pages are ordered naturally by path and filename.
- EPUB support covers EPUB 3 fixed-layout publications and conservative image-centric spine pages.
  Scripted, encrypted/DRM, remote-resource, unsafe-path, layered, and general reflowable EPUBs are
  rejected.
- PDF pages are rendered with Android's platform PDF renderer.
- CBR/RAR and CB7/7z are not supported.

Very large or malformed documents may be rejected by the documented
[hostile-input limits](SECURITY_MODEL.md) instead of risking unbounded memory or storage use.
