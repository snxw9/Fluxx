# TEXT FIX 9: font import regression, Playfair overlap hole, sharp imported/animated glyphs

Device-assisted pass on the A16 (SM-A165F), with the user present. Linked from `TEXT_UI_OVERHAUL.md`.

## 9a. Font import regression (fixed, device-confirmed)

### Symptom

Importing any font (static or variable, including delete then re-import) did nothing visible:

- no new family in the Fonts list;
- search found nothing;
- no error line.

### Evidence (logcat on the A16)

Debug build, with temporary `FluxxFonts` log lines on the import path:

```
18:33:55.103 D/FluxxFonts: page composed
18:33:57.723 I/ActivityTaskManager: START u0 {act=android.intent.action.OPEN_DOCUMENT … PickActivity} from uid 10352 (com.fluxx.android.debug)
18:33:59.654 D/FluxxFonts: ON_STOP cancelAll mode=Fonts
18:34:00.157 D/FluxxFonts: page disposed
```

What did not happen:

- No `picker result` line followed.
- No `:font_worker` process was started.
- `files/fonts/index.json` stayed at 24 bytes (an empty library).

In the first, uninstrumented capture the window manager logged `appStopped=true` for `MainActivity` at 18:28:37.467, while the picker was in front.

### Root cause

`TextInspectorPanel` registers an `ON_STOP` observer that calls `cancelAll()`. Since TEXT UI 5 (`769e4bf`, which turned the Fonts popup into the in-sheet Fonts page), `cancelAll()` ended with `mode = Browse`.

The system document picker is a full-screen activity, so opening it stops `MainActivity`. That leads to this chain:

1. `ON_STOP` fires `cancelAll()`.
2. The inspector leaves the Fonts page.
3. `TextFontsPage` is disposed, which unregisters its `rememberLauncherForActivityResult` launcher.
4. When the picker returns its URI there is no registered callback, so the result is dropped.

`FontLibraryRepository.import` never ran, and no error was possible. The worker, the native library, `TextIdlePrewarm` and `SdfDiskCache` were not involved; the device log shows the worker was never started.

### Fix

- `cancelAll()` still cancels every live draft (numeric, colour, Source Text, axis drag), but keeps the Fonts page open (`TextInspectorLifecycle.afterStop`).
- Imports now run in the repository's own scope (`FontImportRunner` inside `FontLibraryRepository.startImport`), not a composition scope. Leaving the page or recomposing can no longer cancel an import or lose its result.
- The outcome is published as `FontLibraryRepository.importStatus`: `Idle` / `Importing` / `Imported(family, faces)` / `Failed(message)`.
- If the picker result arrives after activity recreation, the page is restored by `rememberSaveable` and the launcher re-registers with its saved key.
- The index merge and remove arithmetic is extracted into `FontLibraryIndex`, so it can be tested on the JVM.
- The idle prewarm after import is wrapped so it can never fail or stall an import.

### Visible-error rules (`TextFontsModel.statusLines`)

1. Every status line is shown, in this order:
   1. page error (load / delete);
   2. import failure;
   3. "Importing font…" or "Imported *family* (N faces)";
   4. the requested-font-missing notice.

   Before this fix, an ordered `when` let the missing-font notice hide the error.
2. Error lines stay until tapped (dismiss) or until the next import or delete action.
3. The "Imported …" confirmation clears after 4 s (`CONFIRMATION_MS`).
4. Every `Throwable` is reported, not only `Exception`, via `FontImportErrors.message`:
   - `UnsatisfiedLinkError` → "Font engine unavailable (…)".
   - `OutOfMemoryError` → "Not enough memory…".
   - Anything without a message → "Font import failed (*class*)".
5. Worker calls (inspect / prepare) are bounded by `WORKER_TIMEOUT_MS = 60 000 ms`. On timeout the call is cancelled, its binding is released, and the status becomes "Font worker did not respond within 60 s; the font was not imported". `importing` therefore cannot stay true forever.

### Device confirmation

After installing the fix, the user imported:

- a static Playfair Display;
- the variable Playfair Display (`wght` axis).

Both appeared in the list and applied. On the device afterwards:

- `index.json` was 913 bytes and listed both files;
- the `:font_worker` process started at 19:07:43;
- there were no crashes.

### Tests

`FontImportTest`, 7 tests:

- the picker no longer closes the Fonts page;
- error-priority rendering, including an import error while a missing-font reference exists;
- worker timeout;
- non-`Exception` failures;
- the import survives the composition leaving;
- delete then re-import of the same sha256 shows the family again;
- static, variable and TTC fixtures.

Total: JVM 364 (357 previous + 7), 0 failures.

## 9b. Overlap-safe SDF (fixed, device-confirmed)

### Symptom

On imported Playfair Display and Vibes, both the variable fonts and their static instances, glyphs showed holes and cuts inside the strokes:

- the crossbar of `e`;
- the bowls and descenders of `g p q y`;
- `&`;
- serif joins.

Other static fonts (e.g. FOT-Matisse Pro) rendered cleanly. A device screenshot zoom showed hairline cuts at contour junctions.

### Root cause

These fonts keep **overlapping contours**. Variable fonts always do, and statics generated from the variable masters often do too. The vendored **FreeType 2.14.1** SDF renderer (`FT_RENDER_MODE_SDF`), even with the `sdf` module's `overlaps` property on (the engine's default since F1), inverts the inside/outside sign along those junctions. The error grows with ppem.

Host evidence, using `text-proof sdfcheck` against an independent FreeType coverage raster of variable Playfair `e`:

| Tier (ppem) | Holes |
|---|---|
| 48 | 8 |
| 96 | 36 |
| 192 | 140 |

The holes sit exactly where the crossbar contour overlaps the bowl.

The **bundled** fonts are affected too, which was not known before: 133 of 5,584 Latin-range glyphs at 48 ppem had sign errors of up to 168 pixels (composites and overlapping shapes, e.g. Inter gid 1550, Noto Serif gid 112).

### Fix

`core-engine/src/main/cpp/text/sdf_coverage.{h,cpp}`, called from `FontManager::sdf`:

1. FreeType renders the SDF as before. Its grid and bearings are unchanged.
2. **Self-check.** The same outline is rasterised once more with FreeType's non-zero-winding coverage rasteriser, which unions overlaps correctly, into a k× supersampled mask on the SDF's own grid. Bounds: k = 1–4, chosen so the mask is at most **1 Mi pixels** (e.g. k = 4 at 48 ppem, k = 2 for a 384 ppem `@`). A glyph fails if any pixel with ≥ 7/8 coverage reads outside, or any pixel with zero coverage reads inside.
3. **Only failing glyphs** are rebuilt:
   - an exact Euclidean distance transform (Felzenszwalb–Huttenlocher, both sides) of the mask;
   - the distance sampled at each output pixel centre;
   - the result encoded with FreeType's own byte mapping (`map_fixed_to_sdf`: 128 = edge, ± spread → 255 / 0).

   The tier ppem and spread table from TEXT UI 6 is unchanged.
4. Glyphs that pass keep FreeType's bytes exactly, so there is no edge, stroke or faux-bold change on correct glyphs. `FontStats::coverageRebuilds` counts the rebuilds.

The worker (imported fonts), the base catalog and the tier catalog all use `FontManager::sdf`, so every path is covered.

### Cost (host, Windows Release build)

| Step | Cost |
|---|---|
| FreeType SDF | 12.5 ms/glyph at 48 ppem (unchanged) |
| Self-check | **0.19 ms/glyph** (≈ 1.5 % extra) |
| Rebuild, failing glyphs only | **3.4 ms/glyph** at 48 ppem |

Memory: a mask of at most 1 MiB, plus two float distance grids of at most 4 MiB each, held only during a rebuild.

### Cache keys

The generator version is part of the persisted key:

- `SdfDiskCache.GENERATOR = 2`, which must equal native `coverage_sdf::GeneratorVersion`; a JVM test reads both.
- Files are now named `v2-48-8-<font>-<glyph>.sdf`.
- Files from any other generator are deleted on first use and never seeded.

On the A16, the old `v1-48-8-…` files were replaced by `v2` files after the first preparation.

### Tests and proofs

- **JVM:** `TextSdfDiskCacheTest.keyCarriesTheGeneratorVersionAndOlderBitmapsAreNeverReused`. JVM total 365.
- **Host native:**

  ```
  tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts sdfregress [FONT...]
  ```

  It checks, in turn:
  1. A synthetic overlap union has no hole.
  2. FreeType byte mapping and supersample bounds.
  3. Rebuild parity against FreeType on clean bundled glyphs: worst mean |Δ| **0.98 / 255**, worst pixel 11.
  4. All 12 bundled faces: every glyph whose FreeType SDF is correct is **byte-identical**, and every glyph that is not is rebuilt with **0 holes**.
     - 48 ppem: full Latin-1, 2206 identical / 58 rebuilt.
     - 96 / 192 / 384 ppem: mixed sample, 132 / 0, 132 / 0, 128 / 4.
  5. Every given font file at all four tiers: 0 holes.

  Run with the user's variable and static Playfair (kept in the scratchpad, not committed): 74 identical / 20 rebuilt at 48 / 96 / 192, 73 / 21 at 384, 0 holes. `PASS`.
- **Diagnostics:**
  - `text-proof … sdfcheck FONT CHARS [TIER|tag=value] [art]` shows holes per glyph, with an ASCII map.
  - `text-proof … ftaudit bundled|FONT [PPEM]` lists raw FreeType failures and per-step timings.

### Device confirmation

The user checked variable Playfair (several weights via the axis and named instances), Vibes, and static Playfair: "holes are gone".

### Finding for 9c

FreeType's SDF renderer is very slow at the large tiers: about 0.3 s per glyph at 192 ppem and 0.45–1.1 s at 384 ppem on the host (half of the measured time including the reference render). The coverage path costs a few ms for the same glyph.

## 9c. Sharp tiers for imported fonts and animated glyphs (device-confirmed)

### Symptom and cause

Imported-font text was a little soft at large sizes, and high-contrast hairlines broke (e.g. the "@" ring in Playfair). The cause is the TEXT UI 6 limit: layouts whose base bitmaps came from the isolated worker (imported faces, and per-character catalogue packets used by Explode and animators) were pinned to the 48 ppem base tier, because the in-app tier catalog can only open bundled faces.

### Spec

**Tier selection.** One per-layer policy for every font. It is unchanged from TEXT UI 6 (`SdfAtlasTiers.select`: px/em with 15 % hysteresis, distance-encoding cap, `degrade` under the cap). `collectOne` no longer special-cases prepared bitmaps (`wanted = tierFor(layout)`).

Catalogue handles (Explode, animators, character offset and value) already received the layer's tier in `applyTextTiers`, and now actually use it. The policy stays **per layer**, as documented; per-glyph selection was not added.

**Who rasterises.** The key `(font, glyph, ppem)` is unchanged.

- Bundled ids (`fluxx.*`) go to the existing off-thread tier catalog.
- Imported ids (`user.<identity>`) are **external**. `TextRenderer::takeExternalRequests` returns the missing ones, at most 256 per call, and marks them in flight.

**Protocol.** `RenderBridge.textTierRequests` → `ImportedTierFetcher` → `FontWorkerClient.tiers` → `FontWorkerService.TIERS` → `IsolatedFontNative.tiers` → `rasteriseTiers` → reply → `RenderBridge.supplyTextTierGlyphs` → `TextRenderer::supplyTierGlyphs`.

- **Transfer.** The same file-descriptor scheme as PREPARE:
  - the request packet goes into a temp file;
  - the imported font files are passed as read-only descriptors with face, named instance and axis coordinates;
  - the reply comes back in an output temp file.
- **Wire format** (`prepared_text_packet.{h,cpp}`, mirrored by `TierWire` in Kotlin), big-endian:
  - request `FTRQ` v1: keys;
  - reply `FTTG` v1: glyph records with font, glyph, ppem, spread, w, h, left, top and pixels; then a `failed` list and a `deferred` list.
- **Caps:**

  | Limit | Value |
  |---|---|
  | Keys per call | ≤ 256 |
  | Reply size | ≤ 8 MiB (`MaxTierReplyBytes`; checked in the worker, the client and the decoder) |
  | Font files | ≤ 64 faces / 32 MiB |
  | Request size | ≤ 1 MiB |
  | Glyph bitmap | ≤ 2048 × 2048 |
  | Worker time per call | 20 s; the rest is **deferred**, never dropped |
  | Worker call timeout | 60 s (`WORKER_TIMEOUT_MS`) |
- **Validation:**
  - only tiers 96 / 192 / 384, with exactly the TEXT UI 6 spread table (16 / 32 / 32), are accepted;
  - the base tier is never requested, since it ships in the prepared packet;
  - unknown fonts fail, and never fall back to `fluxx.sans` (`FontManager::contains`);
  - the renderer accepts only keys it has in flight.
- **Never blocking the render thread.** The preview submits to one background thread (`fluxx-imported-tiers`) and keeps drawing the lower tier. `awaitTiers` counts in-flight external keys, so the existing settle-and-redraw (≤ 1.5 s, cancellable) shows the sharp frame when the glyphs land.
- **Export.** Export fetches inline before preparing each frame (`EXPORT_TIER_ROUNDS = 8` for deferred keys), so it stays deterministic like bundled tiers.
- **Failures:**
  - A worker crash, timeout, malformed reply or deleted font file supplies a failure reply. Those keys stay on the lower tier, `lastError` and logcat (`FluxxText`) record why, and the app never crashes.
  - Missing-font fallback, hash verification on `resolve` and isolated-process crash isolation are unchanged.

**Sharing.** Supplied glyphs join the same CPU ready cache and atlas as bundled tiers:

- key `(font, glyph, ppem)`;
- eviction highest tier first, then LRU;
- CPU tier budget 16 / 8 MiB;
- atlas cap 24 / 12 MiB;
- 15 % hysteresis;
- degrade under the cap.

**Tier generator.** Tiers above 48 ppem are generated from coverage on FreeType's grid (`coverageTier`, see 9b) instead of FreeType's SDF renderer. On the host that renderer costs about 0.3 s per glyph at 192 ppem and about 0.5–1 s at 384 ppem, which would make imported (and bundled) tiers impractically slow. The coverage path averages **66 ms per glyph across the 96/192/384 tiers** for variable Playfair. The base tier keeps FreeType's bytes plus the 9b self-check.

**Disk cache.** `SdfDiskCache` still holds only base-tier imported glyphs: `v<generator>-48-8-…`. Tier glyphs are memory-only.

### Memory impact

Imported tiers spend the same budgets as bundled tiers (no new cap):

| Budget | Normal | Low-RAM |
|---|---|---|
| Atlas | 24 MiB | 12 MiB |
| CPU ready cache | 16 MiB | 8 MiB |

Per call there is one transient reply of ≤ 8 MiB plus its file. The worker holds a ≤ 1 MiB coverage mask and ≤ 8 MiB of distance grids during a glyph.

### Tests

- **JVM** — `ImportedTierTest` (8 tests):
  - tier selection for imported faces and catalogues;
  - request wire round trip and caps (Kotlin/native constants agree);
  - reply validation before native code;
  - the preview never waits, while export fetches inline;
  - worker death / timeout / `UnsatisfiedLinkError` / malformed reply fall back to the lower tier with an error;
  - a deleted font file keeps the lower tier;
  - eviction order, cap and hysteresis are shared;
  - cache keys carry ppem and generator version.

  JVM total: **373**.
- **Host native** — `text-proof … tierproof [FONT]`:
  - wire round trip, the 256-key cap and base-tier rejection;
  - byte and time budgets both defer;
  - unknown font fails;
  - forged spread rejected;
  - the spread table;
  - variable Playfair `wght=700` at 96/192/384: 12 glyphs, 618 KiB reply.

  `PASS`.

### Device confirmation

The user checked imported (variable Playfair) text at 300 / 500 / 1000 px, plus bundled text and animated / Explode text: "imported text looks sharp now". Logcat shows the font worker serving requests, with no tier failures and no crashes.

### Device results (A16, `ImportedTierAcceptanceTest`, 3/3 pass)

The test uses the committed OFL fixture `SourceSerif4Variable-Roman.otf`. The fixture is imported and deleted again; the user's fonts are untouched.

| Test | Result | Time |
|---|---|---|
| `isolatedWorkerRasterisesImportedTiersWithinCaps` | 36 tier glyphs (96/192/384), 0 failed, 0 deferred, 1441 KiB; 5.5 s total, **153 ms/glyph including IPC** | 5.7 s |
| `workerCrashDegradesToTheLowerTierWithAnError` | after `crashForTest`, a failure reply ("The isolated font parser stopped…") and no app crash; the next call succeeds | 1.8 s |
| `exportUsesTheSharpImportedTierDeterministically` | 24 px needs no imported tier; 600 px export supplied **10 tier glyphs inline**, 0 failed, 0 pending; first frame 2833 ms, `tierBytes` 0.8 MiB (cap 16 MiB) | 6.4 s |

### Host regression with coverage tiers

`sdfregress` passes:

- Bundled tiers 96 / 192 / 384 are on FreeType's grid with 0 holes, worst mean drift **0.53 / 255** wherever FreeType is correct.
- At 384, 4 bundled glyphs had FreeType holes; the coverage tier has none.
- The 48 ppem base is unchanged: 2206 glyphs identical, 58 rebuilt.
- Playfair has 0 holes at every tier.

## 9d. Deferred device suites: results, fixes, findings

### How the suites were run (A16, SM-A165F, Android 16)

- The app and test APKs were installed with `adb install -r` (`-t` for the test APK). App data and projects were kept, and **nothing was uninstalled**.
- `connectedDebugAndroidTest` was used only with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, because the default uninstall would delete the user's projects. That task applies only the first class of a comma-separated `class` argument, so the later runs use `am instrument` directly. The long F2 run used a detached `nohup am instrument … > /data/local/tmp/f2b.txt` so a host session loss cannot stop it.
- Run labels: `textRunLabel=a16-fix9-0N` and `e1aLabel=r28` (the build uses NDK 28.2). Reports are under `Android/data/com.fluxx.android.debug/files/text/<label>/`.

Commands used:

```
gradle :app:connectedDebugAndroidTest :core-engine:connectedDebugAndroidTest --continue \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.fluxx.android.render.TextF2AcceptanceTest
adb shell am instrument -w -r -e class <Class[#method],...> -e textRunLabel a16-fix9-0N \
  com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Real bugs found by the device suites and fixed here (all inside this task's tier/atlas scope)

1. **Per-frame allocations in the preview loop** (F1 `fiveHundredGlyphsAllocateNothing…`: *Kotlin worker must allocate zero objects*). The measured window held 54,136 objects over 600 frames.
   - Source: an on-device ART per-thread counter diagnostic (temporary, not committed).
   - About 70 objects per frame came from the TEXT UI 6 tier policy in `applyTextTiers`:
     - `HashMap`, `ArrayList` and `Request` objects;
     - `Long`/`Int` boxing;
     - `filterNot().toSet()`;
     - `filter` / `lastOrNull`.
   - 19 per frame came from the settle check's `FrameRate.frameTimeUs` arithmetic.
   - 1 per frame came from a `{ false }` default-argument lambda.
   - 9c's empty JNI tier-request array added 1 more.
   - **Fix:**
     - per-layer glyph counts, handle lists and tier arrays are prepared once;
     - `SdfAtlasTiers.selectTier` and `degradeInPlace` are primitive forms (`select` and `degrade` keep their API and decisions; JVM tests check equivalence);
     - `textTiers` is only called when a handle's tier changes;
     - `textTierRequests` returns `null` and is skipped when every layer is on the base tier;
     - the frame span is cached per frame rate;
     - one shared `NEVER_CANCELLED` lambda is used as the default;
     - natively, external tier scans are skipped once a layout's keys have settled, and the layout vector is reused.
   - **Result on the A16:** `workerThreadAllocDelta` **0** and `nativeNewDelta` **0** in the F1 window. `workerThreadAllocDelta` is also 0 in every F2 report.
2. **The atlas packer rejected the TEXT UI 6 page cap** (F2 `characterRepresentativeColdAndThermal`: *Invalid atlas page budget*).
   - TEXT UI 6 configures 6 pages (24 MiB; 3 on low-RAM devices), and the JNI accepts 1–8. `packGlyphPages` still allowed only 4 (an E2 bound).
   - So a frame that had to grow the atlas past 4 pages threw instead of growing or raising the typed capacity error.
   - **Fix:** `MaxAtlasPages = 8`. The host `selftest` now asserts that 6 pages are accepted and 9 are rejected.
3. **Multi-page packing dropped a glyph's other tiers** (F2 `characterRepresentativeColdAndThermal`, second run: *Missing prepared glyph*). Fix 2 exposed this one.
   - `packGlyphPages` removed glyphs that had been placed on a page by `(fontId, glyphId)`, ignoring the ppem.
   - When the 48 ppem copy of a glyph landed on page 1, its 192/384 ppem copy, which did not fit on page 1, was discarded instead of carried to page 2.
   - The glyph was not counted in `rejected`, so `prepareMesh` found no placement for a required glyph.
   - This has been latent since TEXT UI 6 (tiers). It only shows once tiered text spills past one page.
   - **Fix:** placed glyphs are identified by bitmap identity (`const SdfGlyph*`).
   - The host `selftest` adds a two-tier case where the 384 ppem copies only fit on page 2. Every copy must be placed, with 0 rejected.

### Environment-blocked (not product failures)

- **Vulkan validation:** `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests (matrix, capacity, array growth, and the 30-cycle surface/renderer lifecycle), plus the final assertion of F1 and F2 performance, require `VK_LAYER_KHRONOS_validation`.
  - The official Khronos Android release (1.4.363.1; sha256 matches GitHub's digest) was downloaded to the host scratch folder.
  - Installing it into the debug app's data directory and enabling `gpu_debug_*` on the phone was blocked by the session's permission policy. Nothing was changed on the phone.
  - The commands for the user are listed in the final report.
- **`E1aGoldenExportTest`:** needs `app/src/androidTest/assets/e1a/source.mp4`, which is git-ignored local media that is not present.

### Findings (reported, not changed: design or limits outside this task)

- **60 fps is not met.** F2 frame time on the A16, p50 / p99, at FULL preview:
  - 660 glyphs: **33–37 / 40–44 ms**;
  - 720 glyphs, rich: 34 / 40–41 ms;
  - 4,096 glyphs (maximum, rich maximum, wrapped maximum): **330–341 / 354–365 ms** in run 05, and **434–439 / 449–480 ms** in run 06 (rich and imported maximum, after about 80 min of continuous load).

  The tests assert p99 ≤ 16.7 ms and fail ("report limits before lowering them"). Limits and fixtures were not changed.
- **Native allocations in the rich and wrapped paths:** `nativeNewDelta` was 624–1,277 per 600-frame window for rich-representative, and 2,063–3,618 for rich/wrapped-maximum. The plain F1/F2 fixtures are 0. This comes from the T1/T3 rich/wrapped mesh path, which this task did not touch.
- **The idle prewarm counts against F1's preparation counter.**
  - `TextIdlePrewarm.foreground` (the FIX pass, `85b9de4`) warms 12 bundled faces × 190 scalars on the **shared** base catalog, 1.5 s after the last layout preparation.
  - Rendering frames does not count as foreground activity, so it runs during playback.
  - Diagnostic on the A16: +572 rasterisations in the window while it runs, **+0** once it has finished. Atlas uploads, shapes and generation were 0 in both cases, so the render path itself is clean.
  - F1 and F2-representative "prepared counter 1 changed" is this. Whether the prewarm should pause during playback is a design decision for later.
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal` time out** under the 15-minute harness limit at the measured 4096-glyph frame time (see the run 06 results below).

### F2 results, run `a16-fix9-06` (APK with fixes 1–2; detached `am instrument`, 10 tests, 21:50 → 23:11)

`Tests run: 10, Failures: 8`.

- **Passed:**
  - `characterRandomAndReversePreEncodePixelsMatch`
  - `randomAndReversePreEncodePixelsMatch`

  Both have pre-encode pixels identical for random and reverse seeks (`matched: true`, 8 seek times).
- **Frame time.** All of these failures report "p99 exceeds 60fps; report limits before lowering them" and "Vulkan validation failure". The validation failure is environmental: the layer is absent, see above.

  | Fixture (pass 0 cold / pass 1 after 180 s thermal load) | glyphs | frame p50 ms | frame p99 ms | mesh p50 ms | native `new` Δ | Kotlin alloc Δ | worker IPC Δ |
  |---|---|---|---|---|---|---|---|
  | t1 rich maximum | 4096 | 438.9 / 437.6 | 477.5 / 470.4 | 10.5 / 10.5 | 3618 / 2063 | 0 / 0 | 0 / 0 |
  | t2 imported variable maximum | 4096 | 434.4 / 433.9 | 479.9 / 449.5 | 10.6 / 10.5 | 65593 / 24582 | 3 / 0 | 1 / 0 |
  | t2 imported variable representative | 700 | 39.6 / — | 53.8 / — | 1.6 / — | 27272 / — | 18 / — | 1 / — |
  | t3 wrapped representative | 720 | 35.6 / 34.8 | 43.6 / 255.9 | 1.7 / 1.6 | 1277 / 624 | 0 / 0 | 0 / 0 |
  | t4 modifiers representative | 720 | 94.0 / 93.9 | 1602 / 359.9 | 1.6 / 1.6 | 4422 / 624 | 0 / 0 | 0 / 0 |
  | t4 characters representative | 720 | 105.9 / — | 1631 / — | 2.1 / — | 39566 / — | 0 / — | 0 / — |

- **Also failed on prepared counters:** the rich, imported and modifier maximum or representative pass 0 reports "prepared counter 2/5 changed".
  - Counters 2 and 5 are atlas uploads and atlas generation (`TextDebugBridge.frameCounters`).
  - Pass 0 starts 120 frames after the first frame. Sharp tiers generated off-thread arrive inside the window, and the atlas re-uploads once to switch the layer up a tier. This is the TEXT UI 6 asynchronous tier design.
  - Pass 1 (after warm-up and thermal load) has no change on either counter.
- **Imported variable (maximum and representative):** pass 0 has 1 worker IPC (`FontWorkerClient.requestCount`, which is process-wide) and 3 and 18 render-thread Kotlin allocations inside the window. This fits one imported-tier fetch (9c): the render thread hands the request bytes from JNI to the tier executor, and the IPC runs there without blocking the frame. The exact frame was not traced.
  - It is asynchronous: the frame never waits on it.
  - The harness still counts any IPC during prepared evaluation as a failure. It is listed under Findings.
- **`characterRepresentativeColdAndThermal`:** *Missing prepared glyph* (bug 3 above, fixed).
- **`importedVariableRepresentativeColdAndThermal` (run 06):** pass 0 completed. Pass 1 failed with `check(RenderBridge.finish(session))`, meaning `finishFrame` returned false: either the 5 s GPU fence wait expired or `flushBatch` failed.
  - The log buffer had already wrapped when it was read.
  - It was re-run with the packer fix and logcat captured (below).
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal`:** `TimeoutException`.
  - The whole test runs under `future.get(15, MINUTES)`. At about 435 ms per 4096-glyph frame, two passes of 720 frames plus 180 s of thermal load and 120 warm-up frames come to about 14–15 min before cold preparation, so the limit expires.
  - This is a consequence of the frame-time finding, not a hang.

### Re-run after the packer fix (`a16-fix9-07`)

This run used the APK with fixes 1–3. Command:

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest#characterRepresentativeColdAndThermal,com.fluxx.android.render.TextF2AcceptanceTest#importedVariableRepresentativeColdAndThermal -e textRunLabel a16-fix9-07 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/f2c.txt 2>&1 &"
```

Result: `Tests run: 2, Failures: 2`. Both tests now **complete both passes**: no *Missing prepared glyph* and no `finish` failure. Logcat for the run (from 00:22, captured this time) has no `VK_ERROR`, device-lost or fence messages. The `finish` failure in run 06 did not reproduce, so it stays a finding. Both remaining failures are the known findings only (p99, validation, native allocations, cold-pass counters, one imported-tier IPC).

| Fixture (pass 0 / pass 1) | glyphs | frame p50 ms | frame p99 ms | mesh p50 ms | native `new` Δ | Kotlin alloc Δ | worker IPC Δ |
|---|---|---|---|---|---|---|---|
| t2 imported variable representative | 700 | 27.5 / 28.7 | 35.1 / 32.8 | 1.8 / 1.8 | 73996 / 4334 | 13 / 0 | 1 / 0 |
| t4 characters representative | 720 | 81.9 / 82.0 | 1607 / 165.5 | 2.2 / 2.1 | 38283 / 1884 | 0 / 0 | 0 / 0 |

`characterMaximumColdAndThermal` was not re-run. The 15-minute harness limit makes a 4096-glyph run at the measured frame time time out regardless of the fix (see above).

## Deviations from the TEXT FIX 9 spec

1. **Tiers above 48 ppem are built from coverage, not FreeType's SDF renderer** (9c).
   - The tier bitmap is the coverage-correct SDF (`sdf_coverage`) on FreeType's NORMAL-preset grid padded by the spread.
   - FreeType's SDF has holes at overlapping contours (9b) at every size.
   - At 48 ppem, FreeType output that passes the coverage check is used byte for byte, and only failing glyphs are rebuilt.
   - The host `sdfregress` puts bundled tier drift against the previous output at ≤ 0.53/255, with 0 holes.
2. **Vulkan validation was not enabled on the phone.**
   - Installing the layer and changing `gpu_debug_*` settings was blocked by this session's permission policy.
   - As a result:
     - `TextGpuProofTest` is not run;
     - four `TextGpuAcceptanceTest` tests are not run;
     - every performance test also reports "Vulkan validation failure".
3. **`E1aGoldenExportTest` was not run:** its source video asset is not in the checkout.
4. **The instrumented runs use `am instrument`, not only `connectedDebugAndroidTest`.**
   - The Gradle task only honours the first class in a list.
   - By default it uninstalls the app, which would delete the user's projects.
5. **The performance limits (60 fps p99, zero native allocations in the rich/wrapped paths, idle-prewarm counters) fail as findings.** As the spec requires, these were reported, not relaxed.

## Tests

- **JVM:** **377 tests, 0 failures** (`gradle testDebugUnitTest`). 357 at the start of TEXT FIX 9, plus 20 new:

  | Commit | New tests |
  |---|---|
  | 9a | `FontImportTest` × 7 |
  | 9b | `TextSdfDiskCacheTest.keyCarriesTheGeneratorVersionAndOlderBitmapsAreNeverReused` |
  | 9c | `ImportedTierTest` × 8 |
  | 9d | `PerFrameTierPathTest` × 4 |

- **Existing tests updated:** `ImportedTierTest.importedFacesAndCataloguesSelectTiersLikeBundledText` (9c's own test) now checks the prepared `tierHandles` line instead of the per-frame `map` expression it replaced. No other existing test was changed.
- **Host (`text-proof.exe core-engine/src/main/assets/fonts …`):** `selftest` PASS (including the 9d page-budget and two-tier packing cases), `sdfregress` PASS, `tierproof` PASS.
- **Instrumented (A16):**

  | Suite | Result |
  |---|---|
  | core-engine `TextCpuProofTest` | 2/2 |
  | `FontImportAcceptanceTest` | 4/4 |
  | `ImportedTierAcceptanceTest` | 3/3 |
  | `TextExplodeAcceptanceTest` | 4/4 |
  | `TextFixAcceptanceTest` | 2/2 |
  | `TextInspectorLayoutTest` | 3/3 |
  | `PaletteRepositoryTest` | 5/5 |
  | `LayerTransformationTest` | 3/3 |
  | `PaletteBrowserTest` | pass on rerun |
  | F1 parity | pass |
  | F1 zero-allocation window | allocations 0/0 after the fix; fails only on the idle-prewarm counter (finding) |
  | F2 | parity tests pass; performance tests fail on the findings above |
  | `TextGpuProofTest`, 4 × `TextGpuAcceptanceTest`, `E1aGoldenExportTest` | blocked (environment) |

## A16 manual test list

Install the debug APK with `adb install -r` (keeps projects). Then:

1. **Import a static font.** Fonts page → Import → pick a static `.ttf` or `.otf`.
   - "Importing font…" appears, then "Imported <family> (<n> faces)" for about 4 s.
   - The family appears in the list.
   - Apply it to a text layer.
2. **Import a variable font** (Playfair, Vibes or Source Serif variable).
   - It imports with its named instances and axes.
   - Weight and optical-size changes apply.
3. **Import errors are visible.**
   - Import a non-font file (for example a `.txt` renamed to `.ttf`).
   - A red error line stays until dismissed.
   - Leave the app during an import and come back: the status (success or error) is still shown, and the Fonts page is still open.
4. **Delete and re-import.**
   - Delete an imported family: layers that use it show the missing-font line.
   - Import the same file again: those layers render again without restarting.
5. **Playfair weights.** Playfair variable and the Playfair static faces at Regular, Medium, Bold and Black, upper and lower case, digits and symbols:
   - no black holes or filled counters (g, p, q, y, e, a, o, 8, @, &);
   - also at small sizes (24–48 px).
6. **Sharpness at 300, 500 and 1000 px.** Set the text size (or the layer scale) so that letters are about 300, 500 and 1000 px tall in the preview, for bundled Sans/Serif/Mono and for an imported static and variable font:
   - edges and corners stay crisp after a brief settle (≤ 1 s), with no blur and no rounded corners;
   - export a short clip and check the same frames in the exported video.
7. **Explode and animated sharpness.**
   - Apply Explode, or a per-character scale-up animator, to large imported text.
   - The magnified glyphs are as sharp as static text of that size.
   - No flicker when scrubbing back and forth across the animation.
8. **Worker crash tolerance.** The isolated `:font_worker` runs under its own uid, so a non-root `adb shell kill` cannot stop it. Use the instrumented check instead:
   `adb shell am instrument -w -e class com.fluxx.android.fonts.ImportedTierAcceptanceTest#workerCrashDegradesToTheLowerTierWithAnError com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
   It kills the worker mid-request, then checks that the lower tier stays in use with an error (no app crash) and that the next request succeeds. Afterwards, open the app and check that large imported text still renders sharply.
9. **Scrubbing performance with large imported text.**
   - A project with a 1080×1920 composition, one paragraph of about 600 characters in an imported variable font at 100+ px, and one animator.
   - Scrub quickly for 30 s: no stalls longer than a frame or two, no "font worker timed out" error, and no growth in app memory in Android Studio's profiler after the scrub.
10. **Regression.**
    - Bundled fonts and the Fonts page browse/search work.
    - Text inspector pages work.
    - Save and reopen a project with imported fonts: the fonts resolve and the text renders the same.
