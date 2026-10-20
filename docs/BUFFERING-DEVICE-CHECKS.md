# Reader buffering device checks

Run this comparison before promoting the refactor branch to a release. CI verifies the policy,
Android compilation and APK packaging; it does not measure device rendering or execute the
Android instrumentation suite.

## Current baseline and default

On 2026-10-20, the user reported that higher buffering works on Pixel 8a and brings a major
usability benefit. This is qualitative on-device evidence; no automated instrumentation result,
peak allocation measurement or CPU-equivalence claim is implied.

The default follows RAM class: Standard below 4 GB, Increased from 4 GB, and High from 8 GB.
Android reports kernel-visible RAM, so the class floors are 3400 MiB and 6800 MiB to allow
for reservations on nominal 4 GB and 8 GB devices. Unknown RAM falls back to Standard. Explicit
saved choices remain unchanged. The selected default is independent of the app heap limit;
actual cache budgets still shrink with the heap, and Android low-RAM devices cannot start
speculative decoding. This is a RAM rule rather than a model/SoC whitelist or benchmark ranking.
Google lists Pixel 8a with [8 GB RAM and Tensor G3](https://support.google.com/pixelphone/answer/7158570?hl=en).
The 4 GB Increased tier is a policy choice; a physical-device result for that tier is still pending.

Before each speculative decode, the app checks Java heap headroom and native heap allocations.
System pressure is sampled opportunistically at most once per five seconds, with Android memory
callbacks providing immediate trimming. Extra work requires free memory beyond the system's
low-memory threshold plus a reserve. Estimated Java/native allocations reserve up to 128 MiB for
one bounded fallback decode. This estimate is not complete process PSS or graphics-memory usage.
Memory callbacks clear speculative tiles and pause extra work for 30 seconds. Backgrounding
suspends it until resume; resuming cannot bypass a still-active pressure cooldown.

Recently visible tiles displaced by the visible LRU may enter the same speculative cache, sharing
its existing ceiling with ahead-of-reader tiles. Explicit trimming, closure and OOM discard tiles
without retaining them. Raster fallback draws borrowed cached pixels from other sampling levels,
clipped to the requested tile; it does not add decoding or promote fallback tiles into the visible LRU.

The reported 1988 x 3056 CBZ pages occupy about 23.18 MiB at sample 1. With a 32 MiB visible cache,
two such pages cannot remain in the visible LRU, explaining why forward prefetch can mask a load
that backward navigation reveals. With its 24 MiB frame budget, the production policy also changes
the first page from sample 1 to sample 2 when a second page enters the viewport. Cached raster
fallback covers both sampling directions. These are reproduced cache/policy mechanisms, not a
confirmed device reproduction of the user's private archive. Retest the reported file in single,
spread and continuous modes, at Standard/Increased/High, including rapid reversal and memory trim.

## Remaining comparison

Use the same comics and device settings for **Standard**, **Increased** and **High**. Include a
memory-limited device/configuration and a device with ample headroom. Test a CBZ with large
raster pages, a tall-strip CBZ, a multipage PDF and an image-based EPUB. Record the Android
version, heap limit, comic sizes and page dimensions with the results.

| Test | Observe for each level |
| --- | --- |
| Cold open, then repeat the same scroll | Time to first usable page separately from visible loading flashes during steady movement |
| Single page, two-page spread, continuous | Visible resolution and tile wait, especially at a page or issue boundary |
| Rapid reversal, slider jumps, maximum zoom | Stale tiles, request lag and whether newly visible tiles move ahead of old prefetch work |
| Missing adjacent issue, rotation, background/resume | Incorrect issue loads, changed reading position/read status or retained offscreen work |
| Repeated open/close and memory pressure | Peak Java/native memory, OOM, decoder lifetime and whether speculative tiles are discarded first |

Compare Standard against v1.1.0 on the same device to check that its visible quality and working
set did not regress. Check a PDF viewport spanning two pages, plus raster mid-zoom movement where
loading artifacts were previously reported. A higher setting is useful only if it reduces visible
wait without excessive memory or frame delays. Do not treat an APK build as this measurement.

For the outstanding R5 gate, run
`./gradlew --dependency-verification strict connectedDebugAndroidTest` on an attached device,
record all 15 production instrumentation cases (including two raster rendering cases),
then perform the database delegate extraction separately and rerun the same cases.
