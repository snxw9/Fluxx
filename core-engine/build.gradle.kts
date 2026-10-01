import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

val nativeNdkVersion = providers.gradleProperty("fluxxNdkVersion").orElse("28.2.13676358").get()
val e1aBaseline = nativeNdkVersion == "26.1.10909125"

android {
    namespace = "com.fluxx.android.engine"
    compileSdk = 37
    ndkVersion = nativeNdkVersion
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        if (!e1aBaseline) consumerProguardFiles("third_party/graphics-path/proguard-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-Wall", "-Werror")
                arguments("-DANDROID_STL=c++_shared")
                // The baseline retains the original Maven binary; r28 rebuilds the same version.
                arguments("-DFLUXX_USE_PREBUILT_OBOE=${if (e1aBaseline) "ON" else "OFF"}")
                abiFilters("arm64-v8a", "x86_64")
            }
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        prefab = true
    }

    if (!e1aBaseline) {
        sourceSets {
            named("main") {
                java.srcDir("third_party/graphics-path/src/main/java")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    
    // E1a baseline only. Candidate builds use vendored Oboe 1.11.0 with the selected NDK.
    if (e1aBaseline) implementation(libs.oboe)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
