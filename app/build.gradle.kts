plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    alias(libs.plugins.baselineprofile)  // use alias
}

android {
    namespace = "net.vrkknn.andromuks"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.aguiarvieira.andromuks"
        minSdk = 24
        targetSdk = 36
        // Increment versionCode for each release (must be higher than previous version)
        // Uses BUILD_NUMBER env var if set (for CI/CD), otherwise uses timestamp-based version
        //versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() 
        //    ?: ((System.currentTimeMillis() / 1000).toInt()) // Unix timestamp in seconds (fits in Int until 2038)

        val now = System.currentTimeMillis()
        // Epoch offset: 2024-01-01 to reduce size
        val epochOffset = 1704067200000L

        // Value to make it larger than 1.0.1
        val playStoreOffset = 1800000000 

        // Final value calculation.
        versionCode = playStoreOffset + ((now - epochOffset) / 1000).toInt()


        // Update versionName for each release (e.g., 1.0, 1.1, 1.2, 2.0)
        versionName = "1.0.40"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keyAlias = "habitica"         // Alias of the key in the keystore
            keyPassword = "12345678"   // Password for the key
            storeFile = file("./gomuks.keystore")  // Keystore file path
            storePassword = "12345678"  // Keystore password
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material icons extended (for Reply, Mood, etc.)
    implementation("androidx.compose.material:material-icons-extended")
    // Material pull-to-refresh (stable)
    implementation("androidx.compose.material:material")
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.compose.foundation.layout)

    // Keep your existing BOM but ensure it's a 2024/2025 version
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))

    // Force these to 1.7.0 or 1.8.0 to get Shared Elements
    val compose_version = "1.7.0" 
    implementation("androidx.compose.animation:animation:1.8.0-alpha05")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.graphics:graphics-shapes:1.0.1")




    implementation("androidx.webkit:webkit:1.10.0")

    // Accompanist for navigation animations
    // implementation("com.google.accompanist:accompanist-navigation-animation:0.34.0")
    // Accompanist for system UI controller (status/navigation bars)
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    
    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")
    // BlurHash - using local implementation
    // implementation("com.github.woltapp:blurhashkt:1.0.0")
    
    // ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    
    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    
    // WorkManager for periodic background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    baselineProfile(project(":baselineprofile"))
}
