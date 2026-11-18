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
