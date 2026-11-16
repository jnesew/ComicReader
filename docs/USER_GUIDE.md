# ComicViewer user guide

## Import comics

Use the **Add comics** plus icon in the library toolbar to select one or more CBZ, ZIP, supported EPUB, or PDF files through Android's
system document picker. ComicViewer remembers Android's document grant so a title can normally be
reopened without selecting it again.

To read loose images as one comic, choose **Folder icon → Import image folder** and select
its folder. Images directly inside that folder become pages in natural filename order
(`1`, `2`, `10`); subfolders are excluded. A folder containing several chapter folders must have
each chapter imported separately. Use embedded ComicInfo series metadata or **Edit comic** to group
them into a series. Library-folder scans continue to discover comic files rather than image folders.

Image comics appear in **Library folders**, labeled **Image comic**.
**Rescan all folders** refreshes their pages and ComicInfo metadata, alongside scans for new comics
in library folders. Refreshes wait while the reader is open; unreadable or incomplete folder listings
keep the saved pages, metadata, and reading state. Removing an image comic from this list uses the
same **Forget** confirmation as its library card and never changes source files.
See the [folder manager example](SCREENSHOTS.md#library-management) for the two folder types.

Reopening an image-folder comic also refreshes its pages. Saved position and bookmarks follow Android's
image document identities when pages are inserted or reordered. Bookmarks for absent images stay
hidden until those same identities return. A provider that changes an image's identity on rename
makes it a new page; ComicViewer does not guess equivalence from its filename. Source images are
never changed or copied into a permanent imported archive.

The folder icon opens **Library folders**, with **Add folder** and **Import image folder** actions
side by side. The **?** button beside the heading explains both actions and how their rescans differ.
Folder rows show the type and location; scan status appears only while scanning or after a failure
or incomplete scan. **Add folder** selects folders to scan for comic files through Android's picker;
ComicViewer scans them recursively on launch or return to the library, skips unchanged files, and
reconciles verified exact copies with earlier imports. The manager shows each folder's location and
scan status, with actions to rescan or remove a folder. It never requests access to all
device storage. An existing saved folder is retained automatically when upgrading.
Routine scanning stays silent on the main library screen. A notice appears there only
if a scan is incomplete or folder access is unavailable; tap it to open the folder manager.

Use **Rescan all folders** to re-import forgotten comics that are still present in the selected
folders. Existing entries keep their saved state. Forgotten comics are imported afresh: Forget
permanently removes their progress, bookmarks, favorites, and edits.

Removing a **Library folder** stops scanning it and releases its access after the reader has finished using it.
It never changes source files or erases reading history. If a verified identical copy remains in
another selected folder, ComicViewer uses it with the same progress, bookmarks, favorites, and edits.
An independently accessible manual import can also keep a comic readable. Otherwise the entry stays
in the library as unavailable until its source is reconnected.

## Library

The Titles view supports search, favorites, reading-status filters, cover-size choices, and sorting
by recent reading, import time, title, or progress. Use the visible **Titles / Series** selector to
switch views. Opening a title marks it as started and restores
its saved page and view state.

Use **Title display** in the library menu to choose **Cover only**, **1 line**, or
**2 lines** (the default). Cover only hides titles and moves progress bars and status
text directly below the covers. One line reserves one title row; two lines keeps the
existing two-row title area, even for short titles. Progress and status stay aligned
across cards in each mode. The choice applies to both Titles and Series and is remembered.
See the [title-display comparison](SCREENSHOTS.md#title-display) for all three layouts.

Series view groups titles conservatively using supported embedded metadata or folder structure.
Standalone titles remain separate. **Edit comic** changes the displayed comic title, chooses an
existing or new series and issue number, restores automatic grouping, or explicitly keeps the
comic standalone. **Use original title** restores the detected title. These edits change only
ComicViewer metadata and never rename or modify the comic file.

While reading, the **information** button beside bookmark and favorite opens the current issue’s
publication details, with creator credits first and its description below. During continuous series
reading, the details follow the active issue. On narrow screens or with large text, use
**Reader menu → Issue details**. Issues without publication details show a short message there.
Opening details does not change your page, bookmarks, favorite, or read status.

Opening a series shows its cover, issue totals, and a **Continue** action above the issue grid.
Continue resumes the most recently opened unfinished available issue, or the first unread
available issue when none is in progress. A completed series can resume its most recently opened
available issue. The target comes from the entire series, including when search or a filter hides it.

The issue row above Continue shows its number and embedded title when available; an explicit title
edit takes precedence. Tap the row's chevron to expand that same issue's description, creator
credits, publisher, language, volume, and publication date. Missing fields leave no empty rows.
When the Continue target changes, its details update and start collapsed. Descriptions are always
issue-specific; another issue's synopsis is never substituted. The overview scrolls away as you
browse comics while the series title and issue filter stay available. The information button in
the toolbar brings the details back when scrolled away and toggles them open or closed when visible.
Returning to Series restores the parent search, filter, and scroll position.
See the [expanded series example](SCREENSHOTS.md#series-details) for the description and credits.

Metadata is cached locally so browsing does not open source comics on the UI thread. After upgrading,
previous imports refresh their metadata in the background when their document access is available.
No online descriptions or biographies are fetched.

The Favorites filter in Titles shows favorite comics. In Series it shows favorite series and
favorite standalone comics. Series favorites are independent: starring a series never stars its
issues, and new issues appear in that saved series automatically. Opening a favorite series shows
all its issues; you can then apply search or reading-status filters. Returning restores the parent
view's search, filter, and scroll position. Use each card's options menu to change its favorite state;
a small star beside its title indicates a favorite without covering the artwork.

**Unread** is the explicit read status, independent of saved reading position. Title cards show
**Unread** or **Completed**; comics in progress show a thin bar in the same space below the title.
The bar estimates position using both the saved page and the scroll position within that page, so
it also moves while reading a single long strip. Pages have equal weight, so the estimate is
approximate when their heights differ. Reaching the end of the bar does not itself mark a comic
completed. The reader still shows the page counter, and accessibility descriptions include both
the library percentage and the page counter.

Series cards show issue totals and unread counts, or **Caught up**. Individual issue numbers,
when supplied by structured metadata, appear as small badges over the bottom of the cover.
Missing issue numbers do not leave a blank line beneath the title. Dates embedded in filenames
or titles remain part of the title; ComicViewer does not guess publication dates from them.

Tap the options button on a cover, or long-press a title or series, to open its action menu.
Choose **Forget** in that menu to remove the entry. Forgetting deletes
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

Use **Library menu → Appearance** to choose **System default**, **Light**, or **Dark**.
System default follows Android's light/dark preference. The reader controls stay dark, and the
page background remains the independent **Reader options → Background color** choice.

## Reading modes

**Single page** mode displays one page at a time. **Two-page spread** keeps the cover and landscape
pages alone, pairs subsequent portrait pages, and reverses their visible sides for RTL reading.
**Continuous** mode stacks pages vertically and decodes only visible and nearby regions. Fit width
is intended for tall strips: width is constrained while the page retains its full scrollable
height.

Continuous mode fits each page to the available width independently, preserving its aspect ratio.
Different source widths therefore remain fully visible at Fit width, although a narrow page can
appear enlarged beside a wider page. Zooming in intentionally shows part of the page; drag
horizontally to reach either edge and vertically to reach the bottom. Fit page shows a whole
page or spread at once in paged modes; in Continuous it returns to Fit width. Very tall strips
can look extremely narrow at Fit page. Manual zoom and Actual size obey the existing zoom bounds,
so Actual size may be approximate for unusually small or large pages.

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
When volume-key navigation is enabled, Volume down moves forward and Volume up moves backward.
Single-page and spread layouts turn pages; continuous mode scrolls 90% of the reader viewport per
press, leaving overlap for following text and panels. At the current content boundary, navigation
can continue into an adjacent series issue. Holding a volume key does not repeat navigation or
change the system volume while this option is active in the reader.


## Formats and limitations

- CBZ and ZIP pages are ordered naturally by path and filename. Optional `ComicInfo.xml` supplies
  a title, series, issue number, cover selection, and an Auto reading-direction hint. The same
  metadata can sit alongside pages in an explicitly imported image folder. User edits and explicit
  direction settings take precedence. Other supported metadata fields are described in
  [Format and metadata support](FORMATS.md).
- Image folders support JPEG, PNG, WebP, GIF, and BMP through Android's image decoders, using the
  same page and memory limits as raster archive pages. They use the system folder picker and
  require no broad storage or media permission.
- EPUB support covers EPUB 3 fixed-layout publications and conservative image-centric spine pages.
  Scripted, encrypted/DRM, remote-resource, unsafe-path, layered, and general reflowable EPUBs are
  rejected.
- PDF pages are rendered with Android's platform PDF renderer.
- CBR/RAR and CB7/7z are not supported.

Very large or malformed documents may be rejected by the documented
[hostile-input limits](SECURITY_MODEL.md) instead of risking unbounded memory or storage use.
