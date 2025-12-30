plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    id("kotlin-kapt")
}

android {
    namespace = "net.vrkknn.andromuks"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.aguiarvieira.andromuks"
        minSdk = 24
        targetSdk = 36
        // Increment versionCode for each release (must be higher than previous version)
        versionCode = (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 2)
        // Update versionName for each release (e.g., 1.0, 1.1, 1.2, 2.0)
        versionName = "1.0.1"

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

kapt {
    correctErrorTypes = true
    javacOptions {
        option("-source", "21")
        option("-target", "21")
    }
}

dependencies {

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

    implementation("androidx.compose.foundation:foundation")

    // Accompanist for navigation animations
    implementation("com.google.accompanist:accompanist-navigation-animation:0.34.0")
    // Accompanist for system UI controller (status/navigation bars)
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")
    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("io.coil-kt:coil-svg:2.5.0")
    // BlurHash - using local implementation
    // implementation("com.github.woltapp:blurhashkt:1.0.0")
    
    // ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    
    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    
    // Room (SQLite ORM) - using 2.7.0 for Kotlin 2.2.x compatibility
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    
    // WorkManager for periodic database maintenance
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Android Auto / Car App Library for messaging support
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
