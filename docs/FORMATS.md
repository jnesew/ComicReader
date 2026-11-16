# Format and metadata support

ComicViewer reads CBZ/ZIP, supported image-centric EPUB, PDF through Android's PDF renderer,
and explicitly selected folders of raster pages. Import and reading instructions are in the
[user guide](USER_GUIDE.md). Input and allocation limits are in the [security model](SECURITY_MODEL.md).

## ComicInfo

CBZ/ZIP archives may contain `ComicInfo.xml`; image folders may contain it beside their pages.
The optional XML is limited to 1 MiB and uses the same bounded, DTD/entity-rejecting parser in
both cases. Malformed or unsafe metadata does not prevent otherwise valid pages from opening.
Unrecognized fields are ignored. Duplicate folder sidecars are ignored because their precedence
is ambiguous.

| Fields | Current use |
| --- | --- |
| `Title` | Detected title; filename/folder-name fallback. User title overrides win. |
| `Series`, `Number` | Automatic series grouping and issue ordering. Explicit grouping wins. |
| `Manga` | `YesAndRightToLeft` supplies the Auto direction hint. `Yes` alone does not imply RTL. |
| `Pages/Page` with `Type="FrontCover"` | First valid marked page supplies the cover. Invalid indices fall back to page zero. |
| `Volume` | Shown in the series Continue issue details and active reader issue details; does not split or rename series groups. |
| `Year`, `Month`, `Day` | Shown as a partial publication date when a year is available, without inventing missing components. Invalid components are absent. |
| `Summary` | Shown for the exact Continue issue or active reader issue; normalized and limited to 4,096 code points. |
| `Writer`, `Penciller`, `Inker`, `Colorist`, `Letterer`, `CoverArtist`, `Editor`, `Translator` | Shown as separate roles in the series and reader issue details. |
| `Publisher`, `LanguageISO` | Shown in the series Continue issue details and active reader issue details. |
| `Pages/Page` attributes `Image`, `Type`, `DoublePage` | Parsed and exposed. `DoublePage` does not yet alter spread layout. |

Marked nonzero CBZ covers are refreshed when opened or imported, so a cover cached by an older
reader does not hide a newly recognized `FrontCover` hint.

Publication fields are cached in schema 13's `publication_metadata` table as bounded JSON keyed by
canonical comic URI. The existing background metadata worker refreshes older imports after migration.
The cache follows proven source relinks and exact duplicate merges and is removed when a title is
forgotten. Failed opens do not erase an existing cache, and late metadata writes cannot recreate a
forgotten entry. It does not change user title/grouping overrides, read status, bookmarks, or position.
Page hints remain available from the opened `ComicDocument.metadata()` and are not duplicated in this
library cache. Actual image dimensions and discovered page counts remain authoritative.

The field conventions follow the [Anansi ComicInfo documentation](https://anansi-project.github.io/docs/comicinfo/documentation).
This is a supported subset, not a claim of complete ComicInfo compatibility. EPUB's existing title,
cover, spine order, direction, and series handling remains separate. ComicBookInfo ZIP-comment JSON
is not currently interpreted.

## Image folders

Import uses a read-only Storage Access Framework tree grant. Only directly contained JPEG, PNG,
WebP, GIF, and BMP files are pages. Subdirectories and unrelated files are excluded. The existing
library scanner continues to scan for supported comic files; it does not infer comics from artwork
or automatically flatten chapter directories.

The folder manager lists explicitly imported image comics alongside library folders. Its rescan
action refreshes the image comic’s pages and publication metadata without discovering child comics.
Refreshes defer while reading; incomplete or failed listings preserve the saved manifest and metadata.

A document session fixes the page ordering and provider identities at open time. Images are streamed
and extracted on demand into the existing bounded raster cache; the folder is not converted into
a persistent private archive. Dimensions are inspected progressively and are not trusted from an
old directory timestamp or a ComicInfo declaration. The listing must be complete, and existing
entry-count, page-count, stream-byte, image-dimension, interruption, and atomic extraction limits apply.
Known child sizes are also bounded in aggregate to 4 GiB. Detected image-size/timestamp changes during
reading require reopening. Providers with missing or inaccurate timestamps cannot establish a
byte-for-byte snapshot; document identities are not a content-equality proof.

The database stores the current page manifest and resolves saved position by provider document URI.
If the saved image is absent, the position falls back to a bounded nearby page with its offset reset.
Bookmarks retain their image identities even while those images are absent, returning to the visible
bookmark list if the identities return. Providers may change identities when files are renamed or
moved; no filename-based reconnection or duplicate merging is attempted. Explicit read status,
favorites, title edits, and series choices survive manifest refreshes.

Schema 12 adds only `image_folders` and `image_folder_bookmarks`; the existing progress, bookmark,
source, and page-index column meanings remain unchanged. Forgetting a folder comic removes its
library state and cached cover without modifying source images. A shared tree grant is retained
while the same tree is still a configured library folder or a manually imported folder comic.
