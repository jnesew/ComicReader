# Standalone rendering comparison

The `renderBench` build is an offline, optimized test application. It installs beside
ComicViewer as `<configured application ID>.renderbench`, with separate app data and
the label **ComicViewer Render Bench**. It needs no ADB, Google services, accessibility
service, network, or Android permissions. Its test screen, fixture pack and runner are
not included in ordinary debug/release APKs.

## Build and install

Use the [pinned development toolchain](BUILDING.md), then:

```bash
scripts/build-render-bench.sh
```

The signed, non-debuggable APK is copied to
`dist/ComicViewer-RenderBench-v<bench-version>-<source>.apk`. It uses the local Android
debug signing key, which is separate from being a debuggable build. It uses the normal
release optimizer; no production signing credentials are required. The new application
has its own stable version and code in `gradle.properties`; increase its code before
distributing changed contents under that application ID. The embedded source revision
ends in `-dirty` for an uncommitted build.

Transfer the APK to the test phone and install it with its package installer. The
separate identity avoids replacing the normal app or touching its library/grants.
Launch it, optionally enter the OS distribution label, select a suite and tap
**Run comparison**. Leave the app visible. Stop, backgrounding or rotation interrupts
the run and preserves its recorded cases/checkpoints. Runs keep the screen awake
while visible; they do not continue as a background service.
Real trim/low-memory callbacks clear optional pixels, suspend speculation and stop
the run, with the reason recorded. Decoder disposal runs on the worker, not inside
the main-thread callback. Pressure-interrupted cases are excluded from timing comparisons.

## What each suite compares

Every policy uses **High** buffering, the same visible-cache/frame budgets, existing
decoder concurrency and the same timed input sequence. The baseline is the current
High policy inside the same instrumented binary; it is not an unmodified historical APK.
All policies include safe disposal of unadopted results. Repetitions reverse the policy
order to reduce order/warm-cache bias. No production default is changed by the benchmark.
Each fixture/mode first opens one baseline viewport as a separate warm-up case
(`repeat: -1`, status `warmup`), excluded from paired comparisons. This reduces first-use
codec/JIT effects; it does not eliminate thermal or OS-cache variation.

| Configuration | Change from baseline | What it can estimate |
| --- | --- | --- |
| `baseline_high` | Existing High selection and limits | Reference for this build/device |
| `lookahead_only` | Direction-aware candidates extending up to six viewports / six future pages or spreads, clipped by a byte prefix and bounded reservations | Value of candidate selection/distance as a bundle |
| `refill_only` | Completion-triggered refill of the original range with bounded pending bytes | Value of a bounded refill pump; original range still limits reach |
| `future_frame_only` | Joint scale planning within each predicted future frame instead of the current first-visible scale clamp | Whether preparing the expected tile levels avoids extra waits |
| `history_fallback_only` | A soft quarter-cache history allowance and shared-cache coarser PDF fallback lookup | Reversal/transition behavior; raster fallback already existed |
| `capacity_admission_64` / `96` | Requested larger cache plus prospective native/system admission, including pending output and future visible/backing growth of live renderers | Capacity and the safety admission policy together; these are deliberately not independent factors |
| `combined_original_cap` | First four changes at the original High byte ceiling | Combined scheduling/continuity benefit at comparable capacity |
| `combined_96_mib_requested` | Combined changes plus a requested 96 MiB ceiling and prospective admission | Whether extra usable capacity adds benefit on the tested workload |

The applied byte ceiling is clipped by half the actual Java heap limit; native/system
guards can refuse work even below that ceiling. A requested 96 MiB cache is not a
promise of 96 MiB occupancy. Logs record requested/applied capacity, actual occupancy,
pending output, admission denials and sampled memory. Low-RAM devices still refuse
speculative decoding. One speculative permit and visible priority remain in force.

**Quick comparison** runs baseline, combined at original capacity, and combined at
requested 96 MiB over raster page turns, strip scrolling and PDF spreads, twice in
alternating order. **Individual changes** runs all nine configurations over seven
format/mode cases, including image EPUB, GIF fallback and three adjacent issue resources.
**Repeated comparison** repeats the quick workload eight times. Duration estimates in
the app are approximate and depend on decode speed. Thermal/power state is recorded
when available; the repeated suite is not a laboratory power or thermal benchmark.

The adjacent-issue scenario exercises actual documents, renderers and the continuous
View with three resource owners. It does not drive the production library database or
adjacent loader: progress/SAF/grant/lifecycle correctness remains covered separately by
connected tests and manual checks. There is no slow adaptive controller, new coarse
render pass, extra decoder worker or predicted provider-copy scheduler in this build.

## Fixtures

Eight synthetic files totaling about 22 MiB are bundled in the test variant:

- `raster.cbz`: ten 1988 × 3056 JPEG pages with deterministic texture and edge markers.
- `strips.cbz`: six PNG pages with mixed aspect ratios, up to 800 × 10000.
- `vector.pdf`: eight pages of vector rectangle patterns, with no external fonts/artwork.
- `images.epub`: six image-spine WebP pages with varied heights.
- `fallback.cbz`: three GIF pages for full-image fallback decoding.
- `issue-a.cbz`, `issue-b.cbz`, `issue-c.cbz`: two-page issues for shared resources/boundaries.

These are ordinary supported comic files, not fake renderer outputs. Setup verifies
their shipped SHA-256 hashes and prepares private copies before measurements. Use
**Export synthetic comics ZIP** to save copies for manual opening with your usual
document provider. Result export does not include the comic files.

The generated pack is checked in with `app/src/renderBench/assets/bench/manifest.json`.
Verify it without installing image libraries:

```bash
python3 scripts/verify-render-bench-fixtures.py
```

Regeneration uses host-only Pillow 13.0.0:

```bash
python3 scripts/generate-render-bench-fixtures.py
```

Seeds and ZIP timestamps are fixed. Encoder/library changes may change bytes; review
and commit the generated files and manifest together. Both devices must use the same
APK/fixture hashes. All artwork is generated by the script for this repository and is
available under the repository's MIT license. Do not add private or third-party comics
to this pack. Extreme input-limit fixtures are intentionally outside the core phone run.

## Export and interpret results

After a run tap **Export results ZIP**, choose a local destination in Android's file
picker and return that ZIP for analysis. Results stay private until explicitly exported.
The most recent run is available after restarting the app, including an interrupted
run's last saved checkpoint. File-picker interaction is outside the measured period.

The ZIP contains `run.json`, `cases.jsonl`, `events.jsonl`, `results.csv`, `summary.txt`
and `state.json` where available. Case-start/end markers are persisted outside timed
actions. It contains model/API/OS-label information, source/configuration/fixture
identity, policy order, readiness events, frame metrics, retained/pending bitmap peaks,
500 ms Java/native allocation samples, and availability/timeout/failure information.
It contains no serial number, account identifier, private library URI, or external
app inventory. The OS label is user-provided, not inferred from the model.

```bash
python3 scripts/analyze-render-bench.py path/to/exported-results.zip
```

The analyzer pairs candidates with the completed baseline case for the same fixture,
mode and repeat. It reports descriptive changes in readiness and missing-area time,
excluding failed, interrupted, timed-out and unpaired cases. Two repeats do not provide
statistical confidence. Compare effects within each device rather than attributing a
cross-phone difference to hardware or operating system alone. Check frame misses,
memory/pressure, actual cache use and thermal conditions before choosing a configuration.

Important limits:

- `exact_missing_area_ms` integrates the fraction of currently drawn image area that
  lacks its requested exact-resolution tiles. The area may already display softer
  fallback pixels. It is not a count of blank screen milliseconds or final visual QA.
- Readiness is sampled at roughly 16 ms and times out at eight seconds. Percentiles
  summarize a small set of scripted actions; raw events retain those actions separately.
  Any timeout invalidates that case for timing comparison. Scrolling includes a fixed
  settling wait; inspect missing-area time across the motion as well as readiness afterward.
- Frame metrics exclude first-draw frames and count lost reports. Before API 31,
  deadlines are approximated from the display's initial refresh rate. Frame p95 is a
  1 ms histogram bin, with overflow at 1000 ms. On-time frames can show missing detail.
- Fresh renderer sessions do not flush OS file caches or establish process-cold launch.
  These runs use app-private fixture files, not a real provider's latency.
- Native allocated bytes and owned bitmap counters are not full process/GPU memory.
  The 500 ms sampler can miss short peaks. The in-app suite does not collect logcat,
  `dumpsys`, heap dumps, Perfetto or privileged system traces.
- Synthetic pressure is not currently part of the timing suite. Connected tests cover
  pressure policy; genuine system memory pressure/kill behavior needs separate evidence.
- Unexpected termination can prevent final records. An incomplete checkpoint never
  counts as a passing test. Rotation, provider selection, visual seams/blur and
  background/resume still need guided manual checks.

## Developer checks

Use the normal repository verification gate plus:

```bash
./gradlew --dependency-verification strict testDebugUnitTest lintRenderBench assembleRenderBench
./gradlew --dependency-verification strict connectedDebugAndroidTest
```

The connected suite includes a real-decoder stationary refill regression requiring
24 tiles to fill from four-tile submissions without intervening draws, within one shared
ceiling. Its injected memory supplier makes it deterministic; it is not a real-memory
pressure benchmark. The API 26 fixture-copy helpers use `InputLimits.copy` instead of
the newer `InputStream.transferTo` method.

For emulator automation only, the test launcher accepts `--es suite smoke` through
`am start`; the regular launcher requires a user tap. Smoke exercises the core formats
and series scenario once with baseline/combined at original capacity. It is a functional
check, not a hardware benchmark. Inspect/export results through the same app UI.
`--es suite smoke_all` exercises all nine configurations with the same shortened
gestures and one repeat. Warm-up cases remain separate. These automation-only modes
do not replace the full-length on-device comparisons.

Before distributing, verify the benchmark is non-debuggable, requests no permissions,
and contains the expected fixture hashes. Verify the normal release APK contains no
`bench/` assets or `RenderBenchActivity`. Keep result bundles and local test notes under
Git-ignored `private/`, never in the fixture source set.
