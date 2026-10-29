# Standalone rendering comparison

**ComicViewer Render Bench 1.3.0** is an offline, optimized diagnostic APK with a
separate `<configured application ID>.renderbench` identity and separate app data.
It installs alongside ComicViewer, requests no Android permissions, and needs no
ADB or Google services. The ordinary release/debug app does not contain its launcher,
fixture pack or runner. The reader now uses bounded, prioritized preparation at
its existing capacity. The larger candidate ceiling remains a benchmark choice.

## Next live-device battery

Install the 1.3.0 APK over the previous benchmark. Its higher version code preserves
existing reports; normal ComicViewer and its library remain separate. On each phone:

1. Open **ComicViewer Render Bench**. Check the displayed actual app heap and refresh
   rate. Optionally enter the OS distribution label.
2. Select **Fast scroll: uncovered tiles and preparation**, tap **Run comparison**, and leave the app visible.
3. When it finishes, tap **Export results ZIP** and save the report locally.
4. The existing **Reader candidate** and **Reader candidate: sustained sessions** suites remain available for a broader check when needed.

The fast-scroll suite targets transient dark placeholders. The previous round-two and
Quick/Individual/Repeated choices remain available as diagnostic references.
Duration estimates are approximate and can vary substantially by device.
Keep brightness, refresh-rate settings, charging and background load comparable.
The app holds the screen awake while visible; it does not run a background service.

Stop, rotation, backgrounding or a real trim/low-memory callback interrupts a run.
The interruption reason and completed cases/checkpoints remain exportable after
restarting the app. Pressure clears optional pixels and suspends speculation;
decoder cleanup runs on the worker, not in the main-thread callback. Incomplete,
interrupted, failed and timed-out cases are excluded from paired timing comparisons.
A finished runner does not by itself mean every case passed: inspect case statuses.
The first readiness timeout ends that case, records its last coverage/motion state,
and continues the battery; final status becomes `completed_with_case_errors`.

## Fast-scroll preparation controls

`scrolling` compares the frozen original High baseline, the existing reader candidate,
and three diagnostic changes at the **same original shared capacity**. It runs
three workloads (raster continuous, tall strips and three-document series), five
policies, four speeds and two repeats: **120 measured cases plus three warmups**. Normal reader
defaults remain the existing candidate; none of the new controls is enabled there.

| Policy | Difference from `reader_candidate` | Cost being tested |
| --- | --- | --- |
| `scroll_pipeline_2` | Up to two outstanding speculative tiles per renderer instead of one | One extra reserved output can keep the existing decoder fed while the main thread delivers scroll frames; may retain work that soon becomes stale |
| `scroll_tall_bands_2` | Up to two approaching viewport bands on tall raster pages instead of one | More runway before the reader reaches unprepared content; may spend work on farther pixels |
| `scroll_pipeline_and_bands_2` | Both controls together | Checks whether the controls help one another or only increase wasted work |

All retain bounded admission, priority replacement, the per-generation repeat guard,
visible priority, pressure cooldown/background suspension and **one active speculative
decode across the session**. PDF preparation retains one near band. No extra worker,
capacity increase, full-page preview pass or adaptive controller is introduced.

Cases request **1, 2, 4 and 8 viewport heights per second**, in forward and
reverse directions, using four consecutive drags per action. **Each speed has its
own freshly opened documents, renderers and caches**, so slower passes cannot warm
the faster case's tile set. Drags end with Cancel to avoid fling acceleration, with
no readiness wait between drags. Each case begins at the first page after a
reset/readiness check and fixed 400 ms preparation interval; reverse follows forward.
The second repeat reverses speed and policy order. The speed is part of the case
identity and baseline pairing; it is also exported in `results.csv`.
The ordinary zoom/jump/burst-jump probes follow the ladder. This is a controlled
drag profile, not a claim to reproduce every user's finger/fling pattern.

Every motion action exports requested speed, delivered distance, elapsed motion time,
delivered speed and motion-only counter deltas. Catch-up readiness and trailing
settle are separate from that motion interval. Compare delivered distances/speeds:
event delivery, touch slop, document boundaries and a slow UI can reduce actual motion.
The analyzer exposes those values instead of treating a nominal input as achieved.

Coverage now separates:

- `exact_missing_area_ms`: requested-detail pixels missing, including areas covered by fallback;
- `uncovered_area_ms`: neither requested tiles nor cached fallback covers the area, so the placeholder remains;
- `fallback_area_ms`: missing requested detail that is covered by cached pixels.

`uncovered_frames` counts View draws with more than 0.001% uncovered area; motion
counter deltas reveal brief holes that may contribute little area-time. These are
draw observations, not a count of frames actually presented by the display.

These are viewport-fraction area-times, not the sum of dark-colored pixels in the
comic, a presentation trace, or proof of a driver fault. Raster fallback coverage
unions overlapping cached source rectangles before clipping to the viewport. The
extra coverage calculation is active only with benchmark metrics. Old reports lack
these fields and the analyzer leaves them unavailable. `coverage_metrics` identifies
the extension while existing protocol-2 action definitions remain readable. Use the
baseline inside the new APK; added measurement work can affect absolute timing.

## What changed in protocol 2

Reports identify `schema: 2`, `protocol: render-bench-2`, source revision and fixture
hashes. Compare policies with the baseline **inside the same new run**. Changes to
timing windows, the fixed-height progress line and scroll settling mean old/new
absolute timings are not a controlled implementation comparison.

| Coverage / diagnostic | Question addressed | How it is controlled |
| --- | --- | --- |
| Same raster CBZ in single, spread and continuous modes | Is a difference due to mode/layout or a different comic/codec? | Same source file; common anchor probes at pages 2, 4, 2, 0, followed by natural mode-specific input. Layout/scale still differ and are recorded |
| Admission-only, original-cap refill, 64 MiB admission and 64 MiB refill | Is improvement due to bounded admission, refill, or retaining more nearby content? | Refill-only versus admission-only and refill-64 versus capacity-64 isolate the refill switch. Capacity and prospective future-growth admission remain a deliberate bundle |
| PDF frame/scale and lookahead controls | Which smaller combination explains the PDF gains? | Extra PDF-only policies avoid applying every option to every workload |
| Per-action frame breakdown | Why can detail improve while frames get longer? | Intended-vsync attribution separates opening, turns, reversals, zooms, jumps, scrolls and idle refill; phases include draw, sync, command issue and GPU when supported |
| Per-action tile-work/planning/guard counters | Is work wasted, or is admission/planning expensive? | Counter deltas, capacity versus memory denials, future-growth reservations and summed call durations. Planning includes guard work; do not add overlapping times |
| Slow/fast scrolling and rapid jumps | What happens during directional movement, flings and cancellation? | Defined gesture inputs; actual motion completion observed; burst jumps allow intervening draws before the final readiness check |
| Same-session cycles and persisted memory checkpoints | Does work or retained memory grow with continued use? | Documents, renderers, cache and View remain alive across cycles; no forced GC or per-cycle reopen |
| Runtime memory/display metadata | Did an emulator or phone receive the intended limits? | Actual app heap, normal/large memory classes, large-heap flag, ABIs, viewport, refresh samples, requested/applied cache and sampled memory are exported |

All policies use High buffering, the existing visible-frame/zoom budgets and decoder
concurrency, and the same input script for the workload. `baseline_high` preserves
the earlier reader's High policy within this instrumented APK. `reader_candidate`
uses the new reader policy. No adaptive controller, extra worker or new coarse
rendering pass is introduced.

## Reader candidate comparison

`candidate` runs **48 measured cases**: the same eight workloads and common anchor
probes as round two, three policies, two repeats with reversed order, and eight
separate warmups. `candidate_sustained` runs the same three policies over raster
continuous, EPUB and series, with five cycles in each of nine sessions and
**45 checkpoints**. Compare candidates to the baseline inside each new run.

| Policy | Purpose |
| --- | --- |
| `baseline_high` | Preserve the live-tested original High behavior |
| `reader_candidate` | Current reader: bounded reservations, priority replacement, correct next-frame scale, coalesced completion refill and narrow geometry-aware preparation; original capacity |
| `reader_candidate_64` | The same candidate with an explicit ceiling up to 64 MiB, clipped to one quarter of the actual heap and subject to prospective native/system/live-renderer-growth guards |

The candidate prepares one adjacent page/spread in the travel direction. Continuous
reading prepares nearby approaching bands in travel order; very tall pages and
PDFs use a single band. Candidate selection uses jointly budgeted predicted-frame
scales. An immediate band can replace farther/unplanned optional pixels. Equally
useful tiles do not evict one another merely to admit more work. Recently visible
history uses spare bytes without a protected fraction. Each candidate renderer has
at most one outstanding speculative tile; completion refills it, so a queue of
farther reserved outputs cannot block the next useful region. Owners that leave the
candidate region cancel their queued work and plans. A running prefetch that
becomes visible can satisfy that request without redecoding it.

Successful optional preparation is remembered within the current lookahead generation.
Evicting a tile cannot trigger an endless decode/replace/refill cycle: the same key
can retry only for a strictly nearer priority or a new generation. Visible requests
are always allowed. The per-generation record is bounded to 128 keys per renderer;
`repeat_prefetch_skips` records attempts suppressed by this rule.

Retained and reserved outputs share one ceiling. The ordinary reader retains its
existing heap-derived capacity; the 64 MiB allowance is an explicit diagnostic
choice, not an automatic capacity change or fill target. Increased and Standard
levels retain their existing capacity rules. Native/system guards, transient
decode reserve, one speculative permit, visible priority, pressure cooldown and
background suspension remain active.

New counters identify `priority_replaced_bytes`, `near_admission_denials`,
`tile_publications`, `published_tile_bytes`, and `peak_published_tile_bytes`.
Publication size and per-action sync/command phases help investigate prepared-frame
cost; they do not establish an upload or driver bottleneck. Readiness observes
tile availability after a View draw, not GPU completion or on-screen presentation.
Use forward/reverse actions and frame durations together when accepting a candidate.

## Round 2: modes and causes

There are **102 measured cases**, two repeats with reversed policy order, and eight
separate baseline warm-ups. Workload order rotates the starting policy to reduce
consistent first-position bias. Order is recorded; warm-ups use `repeat: -1` and
never participate in candidate comparisons.

Eight workloads cover raster single/spread/continuous, tall strips, PDF spreads,
image EPUB, GIF fallback and a three-document continuous series. Six policies run
on every workload:

| Policy | Difference from baseline |
| --- | --- |
| `baseline_high` | Existing High selection and heap-derived shared ceiling |
| `admission_only` | Bounded retained-plus-pending admission, deduplicated/byte-bounded planning and refused-request protection; original range, capacity and no refill pump |
| `refill_only` | The admission group plus completion-triggered refill at the original ceiling |
| `capacity_admission_64` | Requested 64 MiB plus prospective admission accounting for pending output and future visible/backing growth of live renderers |
| `refill_64` | The same 64 MiB admission policy plus completion-driven refill; no expanded lookahead, new history protection or predicted-frame option |
| `combined_96_mib_requested` | Previous full combined experiment, retained as a frame/detail tradeoff reference |

PDF additionally runs `future_frame_only`, `frame_refill` (predicted frame plus
original-cap refill), and `lookahead_only`. These controls narrow the cause of the
previous gains. Some experiments deliberately bundle mechanisms; do not attribute
all changes from `capacity_admission_64` to memory size alone.

For the historical round-two controls, requested capacity is clipped to half the actual app heap, up to the experiment's
128 MiB maximum. Native/system guards can refuse work below the byte ceiling, and
low-RAM devices refuse speculation. Requested bytes are not a fill target. The
existing single speculative permit, visible priority, native transient reserve,
pressure cooldown and background suspension remain in force. Standard buffering
cannot be enabled by these diagnostic options.

## Sustained sessions

Nine measured sessions compare baseline, 64 MiB admission and 64 MiB refill over
raster continuous, EPUB continuous and three-issue series. Each session keeps its
resources for **five cycles** of scrolling, reversals, zooms, jumps and idle time:
**45 cycle checkpoints**, plus three separate warm-ups.

`document_open_count`, `renderer_creation_count` and `session_sequence` describe
setup once per case. `checkpoints.jsonl` records cycle deltas, sampled Java/native
memory and retained/pending bytes before continuing with those same objects. The
worker persists each checkpoint outside action windows. An unexpected kill leaves
running/incomplete state and partial checkpoints; it never becomes a passing case.

This is a session-reuse diagnostic, with one session per policy/workload, not a
statistically confident performance ranking or an hours-long leak/thermal test.
No memory-filler allocations deliberately force real OS pressure. Reports do not
measure energy or establish the maximum safe cache on a phone.

## Readiness and frames

`actions.jsonl` carries case identity, cycle, occurrence, start/end timestamps,
readiness kind, readiness milliseconds, action-window duration, tile-work deltas,
retained/pending bytes, page/document slots and frame summaries. The window includes
a declared trailing settling interval. Counter deltas attribute completed operations;
an operation can begin before its window and complete inside it. It captures activity after exact pixels first
become ready as well as the wait itself; compare both readiness and frame cost.

Round-two scroll readiness starts at finger-up and waits for actual fling completion
and requested-resolution coverage, up to eight seconds. It is not the old near-zero
poll after a fixed 500 ms wait. The full action window also contains the gesture
itself, including missing detail while moving. Legacy suite scroll records explicitly
identify their fixed-wait readiness convention.

Frame reports are attributed by intended-vsync timestamp to half-open action windows,
including delayed callbacks. A 250 ms end-of-case grace period allows delivery of
late reports; callbacks later than that are not guaranteed to be captured. First-draw
frames are excluded and counted. A bounded buffer holds 8192 frame samples per case;
overflow and dropped reports invalidate frame diagnostics and remain visible in the
report. A completed case may have invalid frame diagnostics; do not treat missing
frames as zero cost. Unattributed case frames are counted separately.

Frame phases report supported-sample counts, mean, p95 bin and maximum. Unsupported
phases are unavailable, not zero; GPU duration and platform deadlines require API 31.
Before API 31 the deadline is approximated using refresh rate at callback time, with
observed refresh ranges recorded. Phase durations may overlap and do not sum to a
pipeline total. They do not by themselves identify texture upload, GC or another root
cause. See the [Android FrameMetrics reference](https://developer.android.com/reference/android/view/FrameMetrics).

Frame histograms use 1 ms bins: bin 16 represents durations from 16 to below 17 ms;
1000 is overflow. Report counts differ between policies, so a miss percentage alone
cannot rank smoothness. Short action windows often have too few frames for a useful
p95; inspect count and maximum as well. Case p95 readiness can be the slowest action
in a short script, so separate opening, forward and reverse actions.

## Fixtures and scope

Eight unchanged synthetic files totaling about 22 MiB remain bundled only in this variant:
`raster.cbz` (ten JPEG pages), `strips.cbz` (six mixed/tall PNG pages), `vector.pdf`
(eight vector-pattern pages), `images.epub` (six WebP image-spine pages), `fallback.cbz`
(three GIF pages), and `issue-a.cbz`/`issue-b.cbz`/`issue-c.cbz` (two pages each).
Setup verifies the manifest hashes and prepares private files before measurement.
Use **Export synthetic comics ZIP** for manual opening through a document provider.
Results do not include the comics. Generated artwork uses the repository MIT license;
no private or third-party comics belong in the pack.

```bash
python3 scripts/verify-render-bench-fixtures.py
# Regeneration only, with host Pillow 13.0.0:
python3 scripts/generate-render-bench-fixtures.py
```

The series case uses three actual documents/renderers and the continuous View. It
does not drive the production database, adjacent loader or progress/grant coordinator.
Application-private files do not reproduce provider latency or a process/kernel-cold
launch. Rotation/backgrounding and real-provider operation still need appropriate
manual/connected checks. Sampled memory is not full process/PSS/GPU memory; 500 ms
samples may miss peaks. Exact-gap area-time may already display softer fallback and
is not blank-screen time or a complete visual-quality assessment.

## Build, verify and analyze

Use the [pinned toolchain](BUILDING.md):

```bash
scripts/build-render-bench.sh
```

The optimized, non-debuggable APK is signed by the local debug key and copied to
`dist/ComicViewer-RenderBench-v<bench-version>-<source>.apk`. The independent benchmark
version/code lives in `gradle.properties`; increment before distributing changed
contents. Dirty builds carry `-dirty` in their revision. No production signing key
is required and no test-only installer flag is used.

Result ZIPs contain `run.json`, `cases.jsonl`, `events.jsonl`, `actions.jsonl`,
`checkpoints.jsonl`, `results.csv`, `summary.txt` and `state.json`. No serial, account,
private library URI or external app inventory is collected. The OS label is entered
by the user; battery/charging values are context, not energy measurements.

```bash
python3 scripts/analyze-render-bench.py path/to/results.zip
python3 scripts/analyze-render-bench.py path/to/results.zip --actions
python3 scripts/test-analyze-render-bench.py
```

The analyzer reads protocol-1 exports too. It pairs completed cases by workload and
repeat, and new actions by cycle/name/occurrence. It rejects duplicate identities,
excludes incomplete/failed/timed-out/unpaired cases, reports invalid frame diagnostics,
and leaves unsupported phase deltas unavailable. Repetition counts are descriptive;
never pool emulator and phone timings as equivalent trials. Physical evidence governs
performance/default decisions; emulators provide correctness and mechanism coverage.

Developer checks, in addition to the repository gate:

```bash
./gradlew --dependency-verification strict testDebugUnitTest testRenderBenchUnitTest lintRenderBench assembleRenderBench
./gradlew --dependency-verification strict connectedDebugAndroidTest
```

Emulator-only launch extras `suite=smoke_v2` cover all new focused configurations
with shortened actions (51 measured cases + eight warm-ups). `suite=smoke_v2_session`
checks nine reused sessions with two cycles each. Older `smoke`/`smoke_all` entries
remain available. These automation modes are functional checks, not hardware forecasts.
Normal launcher use requires a tap. Keep local reports and device-specific notes in
Git-ignored `private/`. Verify no requested permissions, expected hashes/signature,
and absence of benchmark assets/classes from the normal release before delivery.

`suite=smoke_scrolling` checks the five scroll policies over all three workloads,
with one repeat, two drags per action and the 2/8 viewport-per-second targets
(30 measured cases plus three warmups).
It is a shortened functional profile, not a substitute for the full speed ladder.
