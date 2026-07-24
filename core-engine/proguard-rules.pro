# Proguard rules for core-engine library module.
# Keep JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}
