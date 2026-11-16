# ComicViewer 1.6.0

Release candidate prepared 16 November 2026.

This release adds series overviews, publication details, and support for comics stored
as loose image folders.

- Open a series to see its cover, issue counts, and **Continue**. Expand **Issue N: title**
  to read the description and publication details for the same issue Continue will open.
  Missing fields stay hidden, and search or filters do not change the Continue target.
- The overview scrolls away to make room for comics. The toolbar info button brings it
  back and toggles visible details. Search, filters, scroll position, and expanded state
  survive the relevant navigation and recreation flows.
- Check who wrote or illustrated the current issue from the reader’s info button.
  Creator credits appear before its description, and the details follow the active issue
  during continuous series reading. Narrow layouts offer **Reader menu → Issue details**.
- Choose **Folders → Import image folder** to read images directly inside one folder as
  a comic, in natural filename order. Optional **ComicInfo.xml** can supply its title,
  series, description, credits, publication details, cover, and reading-direction hint.
- The folder manager lists **Library folder** and **Image comic** entries. Its **?** button
  explains the two import actions. **Rescan all folders** discovers comic files in library
  folders and refreshes pages and metadata in image comics. Routine status stays hidden.
- Saved positions and bookmarks follow image document identities when pages are reordered.
  Bookmarks for temporarily absent images return with those same identities. Refreshes wait
  while reading, and failed or incomplete listings keep saved data.
- Volume keys move by 90% of the viewport in continuous mode, keeping a small overlap.
  They continue to turn pages or spreads in the other layouts.

Existing libraries, settings, user edits, read status, favorites, and bookmarks are
preserved. Older imports refresh publication metadata in the background when access is
available. Page/spread metadata hints are parsed but do not override the reader’s layout.

ComicViewer remains offline, requests no Android permissions, and includes no third-party
runtime libraries. Android 8.0–16 and English/Finnish remain supported. No comics are bundled.
