plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.procam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.procam.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumentation tests run against an optimised, non-debuggable build so the measured
    // processing times match what users get (debuggable builds run tight loops much slower).
    testBuildType = "benchmark"

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK is installable out of the box.
            // Replace with your own signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // On-device face detection (bundled model, works offline, no Play Services needed).
    implementation("com.google.mlkit:face-detection:16.1.7")
    // On-device person segmentation for Portrait (bundled model).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
