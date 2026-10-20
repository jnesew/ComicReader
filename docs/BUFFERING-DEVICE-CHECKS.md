# Reader buffering device checks

Run this comparison before promoting the refactor branch to a release. CI verifies the policy,
Android compilation and APK packaging; it does not measure device rendering or execute the
Android instrumentation suite.

## Current baseline and default

On 2026-10-20, the user reported that higher buffering works on Pixel 8a and brings a major
usability benefit. This is qualitative on-device evidence; no automated instrumentation result,
peak allocation measurement or CPU-equivalence claim is implied.

The default uses the tested device's memory class: non-low-RAM, at least 6800 MiB of kernel-visible
RAM (allowing reservations on an 8 GB device), and at least a 256 MiB app heap select High. Other
devices select Standard. Explicit saved choices remain unchanged. This is a memory capability
rule rather than a model/SoC whitelist or benchmark ranking. Google lists Pixel 8a with
[8 GB RAM and Tensor G3](https://support.google.com/pixelphone/answer/7158570?hl=en).

Before each speculative decode, the app checks Java heap headroom and native heap allocations.
System pressure is sampled opportunistically at most once per five seconds, with Android memory
callbacks providing immediate trimming. Extra work requires free memory beyond the system's
low-memory threshold plus a reserve. Estimated Java/native allocations reserve up to 128 MiB for
one bounded fallback decode. This estimate is not complete process PSS or graphics-memory usage.
Memory callbacks clear speculative tiles and pause extra work for 30 seconds. Backgrounding
suspends it until resume; resuming cannot bypass a still-active pressure cooldown.

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
record all 13 production instrumentation cases,
then perform the database delegate extraction separately and rerun the same cases.
