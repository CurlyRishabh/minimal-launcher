import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minimal.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minimal.launcher"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Only ship English resources (drops translations pulled in by AndroidX).
        @Suppress("DEPRECATION")
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so `assembleRelease` gives an installable APK
            // for personal use. Replace with your own keystore before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += listOf("/META-INF/*.version", "/META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only dependency. It pulls in androidx.core transitively.
    // No appcompat: the framework Activity + Theme.DeviceDefault keeps startup and APK lean.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
