# Consumer Proguard rules for core-engine library module.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.fluxx.android.engine.TextCapacityException { public <init>(java.lang.String); }
