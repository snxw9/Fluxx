# Text layer checkpoint — E1a through E1c

**2026-10-01 update:** E1a render/playback/export smoke verified by the user on A16. E1b and E1c were explicitly authorized together and are implemented: vendored native shaping, static font bundle, cached CPU SDF, independent shaping references and PNG diagnostics. See the E1b/E1c results, diagnostics ledger and reproduction section below. Host CPU, Android debug Kotlin and both-ABI debug/release native compilation passed. Typography instrumented tests remain device-unverified. **Stop before E1d until the user completes the E1a 16 KB environment run and fixed golden-project comparison.** No Vulkan text pipeline has been implemented.

**Verifier correction:** the initial audit incorrectly required raw GNU_RELRO endpoints to be 16 KB aligned. Android rounds them before applying protection; a nonaligned endpoint alone is not incompatibility. Oboe/graphics-path source rebuilds remain, but earlier RELRO-only failure claims are withdrawn. The corrected verifier checks LOAD alignment/congruence and TEXTREL while inventorying RELRO; prior reports need regeneration. See [Bionic protection logic](https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/linker/linker_phdr.cpp), `_phdr_table_set_gnu_relro_prot`.

## Toolchain and packaging changes

- Both modules default to NDK r28c, `28.2.13676358`. CMake 3.22.1, C++17, `c++_shared`, arm64-v8a/x86_64, SDK levels and AGP 9.4.0 are retained.
- Build-tools is explicit at 37.0.0 (installed locally). `jniLibs.useLegacyPackaging = false` is explicit in the app. The source manifest has no conflicting `extractNativeLibs` declaration; AGP supplies it. The verifier requires **false in the compiled manifest**, rather than trusting the DSL.
- `fluxxengine` receives `-Wl,-z,max-page-size=16384` on the normal r28 path. This is intentionally explicit although r28 supplies the alignment default.
- Temporary Gradle property `fluxxNdkVersion=26.1.10909125` selects the r26 baseline in both modules, restores the original Maven Oboe and AndroidX graphics-path binaries, and omits the added engine linker flag. Remove the property for the r28 candidate. The APK records the selected NDK in `BuildConfig.NATIVE_NDK_VERSION`; this is configuration provenance, not a binary compiler attestation.
- E1a changed toolchain/packaging only; E1b/E1c CPU typography is recorded below. Text rendering, model and editor changes remain unimplemented.

The packaging checks follow [Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes). AGP is above 8.5.1 and build-tools above 35.0.0; versions alone are not acceptance evidence.

## Native dependency audit

| Input | Current handling / evidence |
| --- | --- |
| Oboe 1.11.0 | Rebuilt as PIC static source under r28; explicit r26 comparison mode retains its Maven AAR. Original arm64 RELRO-only rejection was a verifier false positive, not proven incompatibility. No TEXTREL was found. AAR SHA-256 `316f31ce92f07725a41556cb1ec0790b348e36b55791df9e5e44976e1f652c30`. |
| Oboe provenance | `core-engine/third_party/oboe/FLUXX_SOURCE.json`: archive/source hashes for 143 unmodified retained files; Apache-2.0 source and app notice. |
| AndroidX graphics-path 1.0.1 | Matching Kotlin/JNI rebuilt from commit `8a05a22af450d589ef911d772a001a49dcb05b71`; normal builds exclude the external module, while r26 retains it. Same class/library names and keep rules. Initial 1.0.1/1.1.0 RELRO-only rejections are withdrawn; no arbitrary version upgrade was adopted. |
| graphics-path provenance | `core-engine/third_party/graphics-path/FLUXX_SOURCE.json`: archive and 18 unmodified source hashes; original build file retained as reference; Apache-2.0 notices. Fluxx CMake wrapper preserves code-generation options/shared C++ runtime. |
| `/libs` | `.gitkeep` only; no prebuilt .so/.a. |
| Media3 | Ten inspected 1.11.1 AARs contain no .so/.a; inventory in `tools/e1a/results/media3-input-audit.json`. Packaged scan remains authoritative for resolved dependencies. |
| Native outputs | Current r28 debug/release ELF audits pass for engine, graphics-path and libc++ across both ABIs. New packaged APK/ZIP and runtime checks remain separate. |

The initial old debug APK SHA-256 was `0049fc88ecebe6cdaf42e57fd3c56e33d663d0742d3daa7e26a29ebca07f325c`: ZIP alignment and compiled `extractNativeLibs=false` passed, while engine/C++ runtime had genuine 4096-byte LOAD alignment. Its RELRO-only findings are superseded by the corrected verifier. This old-artifact report (`tools/e1a/results/existing-debug-native.json`) is not acceptance evidence for new builds.

The [graphics-path release history](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path-1.0.1) identifies the source revision. Static archives inherit their containing shared object's final alignment; verify every packaged .so, including transitive dependencies and libc++.

### Page-size source audit

Searched native application code and Kotlin audio code for `sysconf(_SC_PAGESIZE)`, `getpagesize`, `mmap`, offsets/lengths, `4096`, `0x1000`, `MAP_FIXED`, `mprotect`, and `PAGE_SIZE`. No app-owned page-size dependencies were found. Vulkan allocations continue to use Vulkan memory requirements.

The current disk-backed `AudioMixer` uses `RandomAccessFile.seek/readFully/write`, **not `FileChannel.map`**. Its offsets describe PCM samples, not native memory mapping. No alignment workaround is needed. Revisit this audit if mapping is introduced later.

Vendored Oboe's `4096` in `AudioStreamAAudio.cpp` is an audio capacity in frames for the legacy FAST track, not a byte/page alignment. Its `aaudio.mmap_*` property names select AAudio policy, not direct application `mmap` calls. These were left unchanged.

The vendored graphics-path sources contain none of the searched page-size dependencies. SDK 37's `android.jar` contains the `FastNative` annotation used by the unchanged Kotlin sources; source compilation passed during E1b/E1c.

### Clang diagnostics

The [build checks and focused fixes](#build-checks-and-focused-fixes) below are the current diagnostics ledger. Fix genuine diagnostics individually; do not globally suppress warnings. Debug/release native compilation passed; this does not establish packaged/runtime acceptance.

## Read-only artifact verifier

Requirements: Python 3.11+ (`py -3`), installed SDK build-tools 37.0.0 and NDK r28c. From repository root, after **you** build each APK:

```powershell
./tools/e1a/Verify-Native.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk -Sdk "$env:LOCALAPPDATA/Android/Sdk" -Json tools/e1a/results/debug-native.json
./tools/e1a/Verify-Native.ps1 -Apk app/build/outputs/apk/release/app-release.apk -Sdk "$env:LOCALAPPDATA/Android/Sdk" -Json tools/e1a/results/release-native.json
```

Use the actual generated release filename if different. The Python CLI also accepts repeated `--apk`, `--native-input` (AAR, `.so`, `.a`, or directory), and `--abi` for intentionally split APKs. Example additional input inventory:

```powershell
py -3 tools/e1a/verify_native.py --sdk "$env:LOCALAPPDATA/Android/Sdk" --native-input libs --apk app/build/outputs/apk/debug/app-debug.apk --json tools/e1a/results/native.json
```

The verifier inspects **every** packaged `.so`, checks both expected ABIs, hashes binaries/APKs, rejects compressed libraries and TEXTREL, checks LOAD alignment/congruence and inventories GNU_RELRO using `llvm-readelf -lW/-dW`, verifies the merged `extractNativeLibs=false`, and runs `zipalign -c -P 16 -v 4`. It never rewrites the APK. JSON schema 1 and a human summary are emitted; exit 0 = artifact pass, 1 = incompatibility, 2 = input/tool error. Runtime remains explicitly unverified even on an artifact pass. AAR inventory is not a Gradle dependency-resolution report; the final packaged scan covers what actually ships.

## Fixed golden regression — user-run

The instrumentation fixture is `E1aGoldenExportTest.exportFixedMixedProject`. It exports the actual `CompositionExporter` path: six layers (two Video, two Image, two Solid), overlapping audio with different gains and source in-points, animated position/scale/rotation/opacity, and more than four simultaneously visible layers. Content-driven duration is exactly 6 seconds at 640×360, 30 fps (180 frames). It also saves the project and pre-encode mix (288000 stereo sample frames at 48 kHz).

1. Install/use FFmpeg and FFprobe on the host. Generate the source **once**:

   ```powershell
   py -3 tools/e1a/make_golden_media.py
   ```

   Generated MP4 and hash/command manifest live in ignored `app/src/androidTest/assets/e1a/`. The generator refuses to overwrite media. Keep these identical bytes for all comparisons; generator versions are not assumed byte-reproducible. Generation was not run in the recorded E1a audit; check host FFmpeg availability before running it.

2. In Android Studio, set temporary project Gradle property `fluxxNdkVersion=26.1.10909125`. Build the r26 baseline and run only `E1aGoldenExportTest` with instrumentation arguments `-e e1aLabel r26 -e e1aEnvironment physical-4k`. Archive the baseline APK, source revision/diff and build log. Use a clean native rebuild when switching NDKs.
3. Copy the complete device result directory `Android/data/com.fluxx.android.debug/files/e1a/r26/` to `tools/e1a/results/r26/` via Device Explorer. Preserve it before any uninstall. The test intentionally refuses to overwrite an existing result directory; archive it before a repeat.
4. Remove the NDK override, install r28c through Android Studio if needed, rebuild, and repeat on the **same physical phone/OS/codec environment** with label `r28`. Copy its complete directory to `tools/e1a/results/r28/`. Do not change source media, project definition or app code between exports.
5. Compare:

   ```powershell
   py -3 tools/e1a/compare_golden.py --baseline tools/e1a/results/r26 --candidate tools/e1a/results/r28 --json tools/e1a/results/golden.json
   ```

The report validates source/model/export hashes, matching device fingerprint/page size, 180 frames, dimensions, per-frame timestamps and SHA-256, and PSNR for differing frames. Default acceptance is **each** differing frame >=50 dB; aggregate PSNR cannot hide one bad frame. Threshold changes require an explicit recorded decision, not lowering it until a regression passes. Raw audio mixes must hash identically and have exact expected sample counts. Exported audio is checked for equal decoded sample counts and duration within one sample; AAC padding is bounded to one 1024-sample frame around 6 seconds. Encoded MP4 byte equality is not required. Inspect/listen as well: numeric parity alone is not complete device acceptance.

`run.json` captures NDK configuration, normalized project hash, input/output hashes, device/OS fingerprint and actual runtime page size. Keep both APK hashes/build logs alongside reports. The source fixture is implemented but has **not been compiled or run**.

## Runtime matrix and manual checklist

Record device model, OS/build fingerprint, GPU/driver, APK hash, `adb shell getconf PAGE_SIZE`, environment type, logs and result for each run:

| Environment | Loader / alignment | Video AHardwareBuffer / MediaCodec on 16 KB |
| --- | --- | --- |
| A16 physical phone | Render/playback/export smoke confirmed by user; explicit page-size result and full regression matrix still required | Not evidence of a 16 KB run |
| 16 KB emulator | Pending | **Unverified by emulator testing**, even if it displays video |
| Physical device booted with 16 KB pages (e.g. supported Pixel) | Pending | Pending real-hardware playback, seeking, lifecycle and export |

An emulator demonstrates loader/alignment behavior only; its Vulkan/media stack does not establish the physical zero-copy path. If only an emulator is available, record that limitation explicitly and do not mark physical 16 KB hardware-buffer compatibility complete. Mixed export also includes CPU readback; do not describe that entire export path as zero-copy.

- [ ] Both debug and release packaged-native reports pass for arm64-v8a and x86_64.
- [ ] Golden r26/r28 comparison passes on the same 4GB device; archive exports, PCM, reports and APK hashes.
- [ ] Video/Image/Solid render correctly, including >4-layer composition and animated transforms/opacity.
- [ ] Full/Half/Quarter preview, expand-preview, seeking and playback show no regressions.
- [ ] Background/foreground, surface recreation and teardown show no new crash/linker/validation errors.
- [ ] Thumbnails and single-video fast-path export remain correct (separate from the mixed golden export).
- [ ] Compose path-dependent UI (timeline diamonds, curves, shapes/clips and inspector controls) remains correct with rebuilt graphics-path, on API 29–33 and API 34+ where available.
- [ ] Audio playback, mute, pause/resume and seeking show no new drift, doubled audio or corruption.
- [ ] 16 KB runtime evidence records emulator vs physical device honestly; record unresolved hardware-path coverage.
- [ ] Review and record each new compiler diagnostic individually.

## Verification boundary

All 11 host artifact/golden-tool regression tests passed during E1b/E1c. These use synthetic fixtures, not the user's actual exported golden project. A16 E1a smoke is user-confirmed; the 16 KB environment and fixed golden comparison remain mandatory before E1d. Detailed CPU shaping/SDF evidence follows.

## Text E1b / E1c — CPU implementation and verification

2026-10-01. E1a render, playback and export smoke checks were **confirmed by the user on Galaxy A16**. The user authorized E1b and E1c together. CPU typography is implemented; no text Vulkan resources, renderer injection, persistence or editor UI are added. **E1d remains blocked on the user's E1a 16 KB environment run and fixed golden-project comparison.** A16 smoke testing does not establish those gates.

### Reproducible inputs

`core-engine/third_party/TEXT_INPUTS.json` records resolved release commits, archive SHA-256, individual retained source hashes, static font hashes and license hashes. Builds use vendored files offline; the maintainer download script is never called by CMake/Gradle.

| Input | Pin | License |
| --- | --- | --- |
| FreeType | 2.14.1 | FTL; full attribution/license shipped |
| HarfBuzz | 14.3.1; tag actually resolved | MIT-style license in upstream COPYING |
| Inter Regular | 4.1 | OFL 1.1 |
| Noto Serif Regular | 2.014 | OFL 1.1 |
| JetBrains Mono Regular | 2.304 | OFL 1.1 |

All fonts are **static Regular TrueType outlines**, verified to contain `glyf` and no `fvar`. Assets and full notices live in `core-engine/src/main/assets/fonts/` and `licenses/`. FreeType is used under the FreeType License (FTL), not its alternative GPL. Portions of this software are copyright © 2025 The FreeType Project (www.freetype.org). All rights reserved.

HarfBuzz compiles the upstream amalgamated `src/harfbuzz.cc` as one translation unit. Trimming disables AAT, bitmap/color/drawing, math/meta, variable fonts, vertical layout, CFF and environment configuration. OpenType Latin shaping, kerning and ligatures remain. `HB_NO_DRAW` already implies no paint. Vertical layout is outside this proof; disabling it also removes an upstream compile dependency on the disabled variation implementation. Vendored sources are unmodified.

### Native ownership and shaping

The native `FontManager` owns copied font bytes, FreeType library/faces and HarfBuzz blob/face/font handles. Handles die before their backing bytes. JNI sessions lease a shared manager; in-flight calls retain ownership when another session closes. SDF/layout results are immutable leases and can outlive the manager. No GPU lifetime is introduced here.

Face operations and caches are protected by one mutex. Lock-wait time is measured. Worker-thread prewarming is required for later rendering; future E3 measurement must run off the main thread/debounce typing, since export can contend for this mutex. Font assets are available through each caller's AssetManager, including future thumbnail workers.

Shaping uses OpenType font functions, exact units-per-em scale, LTR/Latin/en and UTF-8 byte clusters. LF creates explicit lines; offsets in the flattened result retain global byte positions. Empty input has zero glyphs/lines; explicit blank lines survive. Missing font IDs resolve to `fluxx.sans`, without modifying the source document; missing codepoints retain glyph ID 0. Malformed UTF-8, NUL/CR and inputs over 16,384 UTF-8 bytes are rejected. These proof bounds do not establish the future editor's string limit.

Native output contains glyph IDs, advances, offsets, line advances and font ascent/descent/line gap in design units. A 64-entry layout cache avoids repeated shaping. The diagnostic JNI JSON API is **not** the future per-frame renderer submission API. E2 will keep layout handles keyed by layer/content revision and provide the shared geometry metrics interface.

### Independent shaping parity

Checked-in reference: `core-engine/src/androidTest/assets/text/shaping-reference.json`. It was generated by the official **hb-shape 14.3.1 Windows release executable**, independently of Fluxx's trimmed library. The file records tool/version/hash, font hashes, exact arguments and raw per-line output. Regeneration requires an explicit `--update-reference` argument; comparisons never rewrite expectations.

All six CPU host cases match glyph IDs, clusters, design-unit advances and X/Y offsets exactly:

- Noto Serif `office`: four glyphs, including the `ffi` ligature.
- Inter `AV`: kerning.
- Noto Serif `office\nAV gy`: explicit newline and global clusters.
- Inter `A` + U+10FFFF + `V`: missing-glyph preservation.
- Inter combining-mark fixture: nontrivial offsets.
- JetBrains Mono `AV office`: third bundled face.

Additional native self-tests passed for fallback, empty/blank lines, missing glyph, malformed UTF-8, pinned-cache eviction/lifetime and concurrent shaping/rasterization. Android `TextCpuProofTest` compares the same checked-in references through JNI and generates CPU diagnostics. Its sources compile; **instrumented tests have not been run on a device**.

### SDF policy, cache and capacity

FreeType unhinted outline SDFs use **48ppem, 8px spread, unsigned R8**. Production proof rasterization enables FreeType's SDF `overlaps` property. CPU samples reconstruct coverage at 8px (diagnostic), 12/14/16px and 72px. This is CPU quality evidence, not GPU scale or Quarter-preview acceptance.

The CPU cache retains immutable SDF bitmaps, with an 8 MiB unpinned LRU budget and bounded metadata. Active atlas/layout preparation leases pin their bitmaps; pinned allocations are not evicted to pretend a hard cap. Repacking consumes these CPU pixels directly. Two full repacks performed **zero additional rasterizations**. The fixture run retained approximately 5.04 MB of SDF cache data, including approximately 4.96 MB pinned for the capacity measurement.

The deterministic packer sorts by glyph height/width/font/ID and uses best-fit shelves plus one guard texel. The measured combined corpus is unique glyph IDs reached by U+0020–024F, U+1E00–1EFF and U+2000–206F in all three faces, including glyph 0. It contains 2,233 records, 47 with empty outlines. Counts below are nonempty glyph rectangles, not universal theoretical capacities:

| Corpus | 1024 × 1024 | 2048 × 2048 |
| --- | ---: | ---: |
| All three faces combined | **338** | **1,547** |
| Inter alone | 372 | 841 (all nonempty) |
| Noto Serif alone | 346 | 868 (all nonempty) |
| JetBrains Mono alone | 429 | 477 (all nonempty) |

The combined 1024 atlas rejected 1,848 nonempty rectangles; 2048 rejected 639. A lazy 2048 R8 atlas costs 4 MiB of texels versus 1 MiB for 1024 and is a sensible **E1d option**, but still cannot hold this entire combined corpus. E1d must pack the active working set, preserve leases/UV generations and define overflow handling. No GPU allocation has been made in this pass.

### PNGs and artifact observations

Local output: `tools/text/results/sdf/` (**137 PNGs**) and `report.json`. This generated directory is ignored by Git and reproducible with the commands below.

- `fixture-atlas-1024.png` and `latin-atlas-1024.png` / `latin-atlas-2048.png`.
- `glyphs/`: individual raw SDF glyphs with overlap handling on/off.
- `samples/`: kerning, ligature, newline, missing-glyph, contour and monospace samples at each sample size.
- `synthetic-overlap-off.png` / `synthetic-overlap-on.png`: deliberately intersecting rectangles.

Actual synthetic-overlap result: property-off produced false internal distance edges (interior minimum **8**); overlap-aware output removed them (interior minimum **248**, center **255**; 752 changed texels). The full-interior probe passes with overlap handling enabled. FreeType's property setter takes `FT_Bool`, but its getter writes `FT_Int`; the wrapper respects that asymmetry.

Visual inspection of the overlap-aware 12/14/16px and 72px font samples found no obvious holes or contour corruption. Small SDF text has expected softness; 8px remains diagnostic, not a claim of acceptable editor minimum size. Inter's missing glyph intentionally appears as its font-specific boxed **NO GLYPH** symbol, rather than an unlabelled tofu box. The report records no rasterization errors. Phone rendering quality still needs later verification.

### Build checks and focused fixes

Host MSVC CPU proof compiled successfully. Android r28c **debug and release native compilation passed for arm64-v8a and x86_64**, together with debug core-engine Kotlin, instrumented-test Kotlin and app Kotlin. No APK was packaged, installed or launched. All 1,425 retained source/font/license checksums passed. Build logs: `tools/text/results/host-build-1.log`, `android-debug.log`, `android-release.log`.

The corrected ELF audit passed all six shared objects in each build variant (engine, graphics-path and NDK C++ runtime across both ABIs): `android-debug-elf.json` and `android-release-elf.json`. This checks compiled ELF LOAD alignment/congruence and text relocations, **not APK ZIP packaging or runtime behavior**. Native symbol inspection confirms the arm64 debug dump JNI symbol exists in debug and is absent from release. All 11 host verifier/golden-tool regression tests pass; these synthetic tool tests are not the user's actual golden-project comparison.

| Observed issue | Focused resolution |
| --- | --- |
| HarfBuzz variation-disabled build referenced `gvar` from vertical-origin code | Disable vertical layout, which is outside this Latin horizontal proof; no upstream patch |
| Explicit `HB_NO_PAINT` duplicated the implication from `HB_NO_DRAW` | Remove redundant definition |
| MSVC exception-unwind diagnostic | Enable `/EHsc` for Fluxx text sources |
| Initial vendor flag filtering damaged NDK `-Werror=format-security` | Match only standalone `-Werror`; preserve the NDK security flag |
| Failed header probes from that configure cached `unistd.h`/`fcntl.h` as absent | Rerun only those CMake probes after fixing flags; both found; no source workaround |
| SDF property readback wrote through an undersized boolean | Use the actual `FT_Int` getter contract; host probe now validates readback |

Fluxx-owned native text keeps `-Wall -Wextra -Werror` (MSVC `/W4 /WX`). Third-party font targets retain upstream warning policy rather than inheriting Fluxx's standalone global `-Werror`; warnings are not globally disabled and NDK security diagnostics are retained. No genuine new Clang warning was suppressed. Logs are in `tools/text/results/`.

The existing E1a verifier falsely rejected r28 binaries (including NDK libc++) because it required raw RELRO endpoints to be 16 KB aligned. [Bionic rounds those endpoints](https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/linker/linker_phdr.cpp) in `_phdr_table_set_gnu_relro_prot`; that unsupported rejection was removed with a regression test. LOAD alignment/congruence and TEXTREL checks remain. The E1a checkpoint explicitly withdraws its older RELRO-only incompatibility conclusions. No compiler/linker security option was weakened to make the check pass.

### Reproduction (CPU only; no device install)

```powershell
py -3 tools/text/verify_inputs.py
py -3 tools/text/build_host.py
py -3 tools/text/shaping_parity.py --proof tools/text/results/host-build/Release/text-proof.exe
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts dump tools/text/results/sdf
```

The host script requires installed Visual Studio C++ Build Tools and SDK CMake 3.22.1. Optional compile-only Android checks: `py -3 tools/text/build_android.py` and `py -3 tools/text/build_android.py --release`. These never assemble/install APKs. Device diagnostics are explicitly user-run via `TextCpuProofTest`; output is `<test target external files>/text-e1bc/`, not the host directory.

### Remaining gates

- E1b/c: user review of CPU PNGs and Android instrumented parity/dump results.
- Before **E1d**: user's E1a 16 KB environment result and fixed r26/r28 golden-project video/audio comparison. If only an emulator runs, physical 16 KB AHardwareBuffer/MediaCodec behavior remains unverified.
- E1d/E1e: no implementation yet; GPU synchronization, per-renderer atlas ownership, debug renderer injection, scale/preview matrix and heap/GPU-resource teardown stress remain planned.
- E2/E3: outlines only. No text layer creation, FlatBuffers v9 change, geometry integration, typography UI or Text icon activation.
