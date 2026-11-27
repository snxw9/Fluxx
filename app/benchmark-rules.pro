# MP1 A1: extra R8 rules for the `benchmark` build type only (release is unchanged).
#
# The benchmark variant runs the same in-process instrumented classes as debug (Aud1/Aud2/Mp1 benches). They call the
# app's own classes directly (EditorScreen, EditorViewModel, PreviewController, the decoder / renderer test hooks) and read
# a few fields by reflection (PreviewController.playback, StreamingPlayback.track / startFrame). R8 would otherwise remove
# or rename members only the tests use, so the app's own package is kept as is. Names are not obfuscated, so stack traces
# from the benchmark stay readable.
-dontobfuscate
-keep class com.fluxx.android.** { *; }
-keep class fluxx.schema.** { *; }

# The test APK links against the libraries inside the app APK (AGP leaves shared dependencies out of the test APK), so
# members only the test runner / Compose test rule use must stay, unchanged. Found one by one on the phone:
# androidx.tracing.Trace removed (NoClassDefFoundError), kotlinx.coroutines JobSupport.toString made final under
# allowoptimization (LinkageError: kotlinx-coroutines-test subclasses it), androidx.collection.mutableIntObjectMapOf
# removed (NoSuchMethodError). So AndroidX, Kotlin and kotlinx are kept as they are (no shrinking, no optimisation).
# Consequence for the numbers: this variant measures the NON-DEBUGGABLE runtime with release resources, not R8's
# optimisation of library code; a real release (these libraries optimised) should be at least as fast.
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
