# ComicViewer 1.3.0

## Multiple library folders

Add several folders through Android's file picker and manage them together in **Library folders**.
Each folder has its own scan status and remove action; **Rescan all folders** refreshes the collection.
Your existing folder selection is retained automatically when upgrading.

Verified identical copies across folders share one library entry. When a source becomes unavailable
or its folder is removed, an accessible verified copy can take over while preserving reading
progress, bookmarks, favorites, and title edits. Removing a folder never changes your comic files.

## A clearer library and independent series favorites

- Switch directly between **Titles** and **Series**, with refreshed toolbar icons and cover cards.
- Favorite a whole series independently of its individual comics. New issues join that favorite
  series automatically, and opening it from Favorites shows all its issues.
- See favorites beside titles instead of over the artwork, along with structured issue numbers
  when available. Unread cards hide empty progress tracks.
- Return from a series to your previous search, filter, and scroll position. Library navigation
  also survives screen recreation.
- Compact series grids keep covers aligned across mixed reading states and larger text sizes.

The reader now has a comic-favorite star alongside a separate page-bookmark ribbon.

## More reliable scaling and rendering

- Zoom buttons use the displayed scale after **Fit page**. **Actual size** remains consistent
  after relayout, within the existing zoom bounds.
- Continuous zoom keeps the content under your focus, and very long collections retain precise
  scrolling and flinging.
- Background page indexing no longer leaves tiles stretched after correcting page dimensions.
- Very tall PDF pages have bounded previews, and supported EPUBs and ComicInfo metadata parse
  correctly on Android while retaining protections against external XML resources.
- Full-image fallback decoding retains less memory and validates actual image headers before
  allocation. Large images that require this fallback may appear softer at high zoom.
