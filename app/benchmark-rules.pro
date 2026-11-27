# MP1 A1: extra R8 rules for the `benchmark` build type only (release is unchanged).
#
# The benchmark variant runs the same in-process instrumented classes as debug (Aud1/Aud2/Mp1 benches). They call the
# app's own classes directly (EditorScreen, EditorViewModel, PreviewController, the decoder / renderer test hooks) and read
# a few fields by reflection (PreviewController.playback, StreamingPlayback.track / startFrame). R8 would otherwise remove
# or rename members only the tests use, so the app's own package is kept as is. Libraries (Compose, kotlinx, Media3,
# AndroidX) are shrunk and optimized exactly as in release; that, and the non-debuggable runtime, is what the variant
# measures. Names are not obfuscated, so stack traces from the benchmark stay readable.
-dontobfuscate
-keep class com.fluxx.android.** { *; }
-keep class fluxx.schema.** { *; }
