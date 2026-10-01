# Export ownership audit and regression reference

## Current boundary and evidence

Current export has two paths owned by `CompositionExporter`: the guarded single-video Media3 Transformer path (dependency 1.11.1), and a separate headless Vulkan/MediaCodec path for layered compositions. [Phase 2 backend](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md#dual-export-pipelines-compositionexporter) owns the current pipeline contract. Export uses an immutable project snapshot, independent renderer ownership and finalized output publication through `ExportService` to `Movies/Fluxx`.

User confirmation: exports worked on the user's phone on 2026-09-10; E1a render/playback/export smoke passed on A16 on 2026-10-01. Neither establishes exhaustive codec/lifecycle coverage, the fixed r26/r28 golden comparison, or 16 KB runtime compatibility. The [text checkpoint](../checkpoints/TEXT_LAYER.md) owns those remaining gates.

## Historical source audit - 2026-09-10

These findings concern the **removed** Phase 1 export-swapchain implementation, not 13 currently open defects. Its path was Activity -> preview decoder replacement -> VideoExporter -> codec input Surface -> singleton JNI/Vulkan export swapchain -> encoder drain -> external-files MP4. ExportService was then only a simulated progress loop. The replacement removed that swapchain/JNI path; its Media3 migration used 1.11.0 at the time. Historical source line numbers are omitted because the code has since changed.

| Finding | Ownership/validation lesson retained |
| --- | --- |
| URI updated only after durable permission succeeded; preview could differ from export | Keep session URI independently of grant persistence; report unavailable media |
| Preview surface destruction tore down the active export renderer | Export lifetime belongs to the service/job; await cancellation cleanup before releasing its dispatcher/resources |
| Presentation and encoder drain serialized on one coroutine | Avoid producer/backpressure deadlocks; the old stall was a plausible risk, not a device-confirmed diagnosis |
| Export surface capabilities/creation results unchecked; queue family hardcoded | Validate format, usage, alpha, queue support and every creation result; release partial resources and propagate failure |
| Present semaphores indexed by frame instead of acquired image | Submission completion does not prove presentation completion; follow acquired-image ownership |
| Hardware-buffer import failure ignored | Fail the frame with native operation/generation diagnostics rather than outputting black/stale content |
| Source timestamps discarded and output forced to 30fps | Preserve timing or explicitly resample to composition frames; don't repair time by rewriting encoded timestamps |
| Video-only mux omitted audio | Mix/mux audio against the same timeline |
| Successful files hard to find; partial files survived errors | Publish only finalized output, expose its location and clean failed/cancelled temporary files |
| Decoder stop had a 200ms join; callbacks/images could outlive teardown | Stop and join producers/callbacks, wait for GPU use, release independently, unblock cancelled channel waits |
| Load/sliders modified shared export staging state | Isolate preview and immutable export snapshots; no shared producer slot |
| Failed device initialization installed a fake VkDevice | Propagate initialization failure; never call Vulkan with placeholder handles |
| Codec selected without validating full dimensions/rate configuration | Report requested format and codec diagnostics; do not assume every device accepts the configuration |

The original path already used EOS, readiness/frame deadlines, channel error propagation, final-drain deadlines, muxer finalization and cancellation rethrow. These protections did not remove the ownership defects above. This was source review, not on-device reproduction of every failure.

## Regression checks

- Import through a provider without a persistent grant: preview/export must use the same visible source; reopening must report unavailable access clearly.
- Export short/long 24/30/60fps and VFR media; inspect frame timestamps, duration, audio, metadata and output visibility in both eligible fast-path and layered cases.
- Rotate/background/cancel/reopen/repeat export; verify service and renderer cleanup without affecting preview ownership.
- Exercise missing sources, unavailable/full output storage, unsupported encoder formats, and native initialization/import failure.
- Run Vulkan validation where applicable; retain renderer/decoder/exporter logs to separate setup, acquire/present and codec-drain failures.
- Run the fixed golden and page-size checks from the text checkpoint separately; smoke results cannot replace them.

References: [Khronos presentation semaphore lifetime](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html), [Android document grants](https://developer.android.com/training/data-storage/shared/documents-files). Fluxx's MediaStore browser is the product path; any remaining document selector is a fallback, not a requirement to adopt Android's photo picker UI.
