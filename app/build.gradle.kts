plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.novadeck.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.novadeck.control"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-DNDEBUG",
                    "-ffast-math",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-fvisibility=hidden"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("NOVADECK_KEYSTORE") ?: "novadeck-release.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("NOVADECK_STORE_PASS") ?: "novadeck2026"
                keyAlias = System.getenv("NOVADECK_KEY_ALIAS") ?: "novadeck"
                keyPassword = System.getenv("NOVADECK_KEY_PASS") ?: "novadeck2026"
            }
        }
    }

    buildTypes {
        val ksFile = file(System.getenv("NOVADECK_KEYSTORE") ?: "novadeck-release.jks")
        val sharedSigning = if (ksFile.exists()) {
            signingConfigs.getByName("release")
        } else {
            signingConfigs.getByName("debug")
        }

        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            signingConfig = sharedSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = sharedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
