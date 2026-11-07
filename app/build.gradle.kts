plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.snap2print.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snap2print.app"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 2
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build (local and GitHub), so new versions install over old ones.
    // For a Google Play release, replace it with your own private upload key (see README).
    signingConfigs {
        create("shared") {
            storeFile = file(System.getenv("SNAP2PRINT_KEYSTORE") ?: "snap2print-dev.jks")
            storePassword = System.getenv("SNAP2PRINT_KEYSTORE_PASSWORD") ?: "snap2print"
            keyAlias = System.getenv("SNAP2PRINT_KEY_ALIAS") ?: "snap2print"
            keyPassword = System.getenv("SNAP2PRINT_KEY_PASSWORD") ?: "snap2print"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
