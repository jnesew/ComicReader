# ComicViewer 1.4.0

The library has a lighter layout, with clearer Titles/Series tabs and sort/filter
controls. File and folder import stay in the top toolbar and the empty library.
Cover actions are available from the corner menu or by long-pressing a card.

Choose **System default**, **Light**, or **Dark** from **Library menu → Appearance**.
The library and its dialogs use that appearance. Reader controls retain their dark
palette, and the reader's canvas color remains independently selectable.

Issue numbers now appear as small badges at the bottom of covers. Beneath each
title, **Unread** and **Completed** remain visible labels; comics in progress show
a thin bar in the same space. The estimate includes scrolling within a page, so a
single-page long strip can show useful progress. Pages have equal weight, making
the estimate approximate when page heights differ. Saved position alone never
marks a comic completed. The reader still shows page counts, and library
accessibility descriptions include both percentage and page position.

Mixed PDF and numbered-comic covers now remain aligned after scrolling down and
back up. Card height stays consistent across issue badges, favorites, and reading
states. Screenshots have been refreshed with public-domain sample comics and a
light-mode example.

For developers, transitive Android build and test-tool libraries have been updated,
with strict resolved-dependency checks added to CI and release builds. The app
continues to operate offline without requested permissions or third-party runtime
libraries. Existing libraries, reading positions, favorites, and bookmarks are retained.
