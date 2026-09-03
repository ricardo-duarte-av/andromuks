import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin; the standalone kotlin.android plugin is removed.
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
    alias(libs.plugins.baselineprofile)  // use alias
}

// Release signing credentials are resolved from (in order): environment variables (CI Secrets),
// then a gitignored keystore.properties at the repo root (local). No secrets are committed.
// Keys: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. See keystore.properties.example.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSecret(key: String, default: String? = null): String? =
    System.getenv(key) ?: keystoreProps.getProperty(key) ?: default

android {
    namespace = "net.vrkknn.andromuks"
    compileSdk = 37

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
        versionName = "1.1.44"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Resolved from env (CI) or keystore.properties (local); never hardcoded. storeFile is
            // evaluated lazily, so debug builds work without any signing credentials present.
            storeFile = file(signingSecret("KEYSTORE_FILE", "gomuks.keystore")!!)
            storePassword = signingSecret("KEYSTORE_PASSWORD")
            keyAlias = signingSecret("KEY_ALIAS", "habitica")
            keyPassword = signingSecret("KEY_PASSWORD")
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

    // Side-by-side installable variants. Replaces the old CI `sed` hacks: each flavor sets its own
    // applicationId suffix, app name, FileProvider authority (via ${applicationId} in the manifest)
    // and Matrix-contacts authority (via the ${contactsAuthority} placeholder + BuildConfig field).
    flavorDimensions += "variant"
    productFlavors {
        create("base") {
            dimension = "variant"
            resValue("string", "app_name", "Andromuks")
            manifestPlaceholders["contactsAuthority"] = "net.vrkknn.andromuks.matrix.contacts"
            buildConfigField("String", "CONTACTS_AUTHORITY", "\"net.vrkknn.andromuks.matrix.contacts\"")
        }
        create("a") {
            dimension = "variant"
            applicationIdSuffix = ".a"
            resValue("string", "app_name", "Andromuks A")
            manifestPlaceholders["contactsAuthority"] = "net.vrkknn.andromuks.matrix.contacts.a"
            buildConfigField("String", "CONTACTS_AUTHORITY", "\"net.vrkknn.andromuks.matrix.contacts.a\"")
        }
        create("b") {
            dimension = "variant"
            applicationIdSuffix = ".b"
            resValue("string", "app_name", "Andromuks B")
            manifestPlaceholders["contactsAuthority"] = "net.vrkknn.andromuks.matrix.contacts.b"
            buildConfigField("String", "CONTACTS_AUTHORITY", "\"net.vrkknn.andromuks.matrix.contacts.b\"")
        }
        create("c") {
            dimension = "variant"
            applicationIdSuffix = ".c"
            resValue("string", "app_name", "Andromuks C")
            manifestPlaceholders["contactsAuthority"] = "net.vrkknn.andromuks.matrix.contacts.c"
            buildConfigField("String", "CONTACTS_AUTHORITY", "\"net.vrkknn.andromuks.matrix.contacts.c\"")
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
            // R8 obfuscates release stack traces; the Crashlytics Gradle plugin uploads the
            // mapping file so Crashlytics can deobfuscate them. This defaults to true, but we
            // set it explicitly so an accidental flip can't silently ship unreadable crashes.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = true
            }
        }
        debug {
            isDebuggable = true
            // Debug builds aren't minified and carry symbol info already, so skip the upload
            // (it would otherwise slow every debug build for no benefit).
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Backport java.time (and other JDK APIs) to minSdk 24. Without this, java.time usage
        // (e.g. ZonedDateTime/DateTimeFormatter in UserInfo) requires API 26 and crashes on 24/25.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // AGP 9 disables resValues by default; flavors set app_name via resValue(...).
        resValues = true
    }

    // Local unit tests (`./gradlew test`, and the `test` task in CI's lint job).
    //
    // The android.jar these run against is a STUB: every method throws
    // "Method ... not mocked" by default. That made anything touching android.util.Log
    // untestable, which is why the only unit tests that existed were of pure functions
    // deliberately extracted away from Android APIs.
    //
    // `isReturnDefaultValues` turns those throws into null/0/false. That is the right trade for
    // Log — but it would be a TRAP for org.json, whose stub would then silently return null
    // instead of failing loudly, so a parser test would "pass" while parsing nothing. The real
    // org.json on the testImplementation classpath (see dependencies) shadows the stub and closes
    // that hole. Keep the two together: neither is safe without the other.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // Android lint (complements detekt, which covers Kotlin style/Compose). The baseline records
    // the ~current backlog so CI fails only on NEW lint issues; it is created on the first run of
    // `:app:lintBaseDebug` (see bootstrap steps). SARIF is emitted for CI artifact upload.
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
        checkDependencies = false
        sarifReport = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)

        // Warnings-as-errors, opt-in via -PwarningsAsErrors=true. CI's lint job passes it; local
        // builds do not.
        //
        // Deliberately NOT on by default. The module is at zero warnings and this keeps it there,
        // but a Compose/AGP/Kotlin upgrade routinely deprecates something — and if that turned
        // every local build into a hard failure, the upgrade would have to be done in one sitting
        // with the deprecations fixed before anything would even run. On CI that trade is right
        // (a warning reaching master is what we're preventing); on a laptop mid-upgrade it isn't.
        //
        // Read through a provider so the configuration cache invalidates when the flag changes.
        allWarningsAsErrors.set(
            providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false),
        )
    }
}

// Opt-in Compose Compiler metrics + reports for diagnosing recomposition
// hotspots (unstable params, non-skippable composables, lambda capture).
// Enable with: ./gradlew assembleDebug -Pcompose.metrics=true
// Reports land in app/build/compose-metrics/ and app/build/compose-reports/.
if (project.hasProperty("compose.metrics")) {
    val metricsDir = layout.buildDirectory.dir("compose-metrics").get().asFile.absolutePath
    val reportsDir = layout.buildDirectory.dir("compose-reports").get().asFile.absolutePath
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$metricsDir",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$reportsDir",
        )
    }
}

dependencies {
    // Enables isCoreLibraryDesugaringEnabled above (java.time et al. on API < 26).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.biometric:biometric:1.1.0")
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
    implementation(libs.material.kolor)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    // HTML parsing for Matrix message markup. Pure JVM, so it also runs in unit tests — which is
    // what makes HtmlParser testable at all (see HtmlParserTest).
    implementation(libs.jsoup)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.androidx.compose.foundation.layout)

    // Compose foundation/animation are driven by the single compose-bom above (declared via the
    // version catalog). The previous explicit 1.7.8 / 1.8.0-alpha05 pins were obsolete — shared
    // element transitions are stable in the BOM — and were overriding the BOM with old versions.
    implementation(libs.androidx.compose.foundation)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    implementation("androidx.webkit:webkit:1.16.0")

    // Image loading (Coil 3 — coordinates io.coil-kt.coil3, package coil3). coil-network-okhttp
    // is required: Coil 3 core no longer loads from the network by default, and it routes image
    // requests through our shared OkHttpClient (auth cookie + ?encrypted= interceptor).
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-gif:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    // BlurHash - using local implementation
    // implementation("com.github.woltapp:blurhashkt:1.0.0")

    // LaTeX math rendering (MSC2191) - native JLaTeXMath port, renders to a Drawable (no WebView)
    implementation("ru.noties:jlatexmath-android:0.2.0")
    
    // ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    // OkHttp-backed data source so video/audio streams flow through our shared OkHttpClient
    // (and thus the EncryptedMediaRetryInterceptor's ?encrypted= flag correction).
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    
    // CameraX for in-app camera preview and capture
    val cameraxVersion = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Firebase Cloud Messaging. NOTE: the -ktx artifacts were removed in Firebase BOM 33+; the
    // Kotlin APIs are now in the main modules, so these are the plain (non-ktx) coordinates.
    implementation(platform("com.google.firebase:firebase-bom:34.14.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-analytics")
    // Crash + non-fatal error reporting. Collection is opt-in (disabled by default in the
    // manifest) and toggled at runtime via ErrorReportingCoordinator. BOM-managed version.
    implementation("com.google.firebase:firebase-crashlytics")
    // Performance Monitoring (startup, network, screen rendering, custom traces). Also opt-in,
    // toggled at runtime via PerformanceMonitoringCoordinator. BOM-managed version.
    implementation("com.google.firebase:firebase-perf")
    
    // WorkManager for periodic background tasks
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Glance — the home-screen room widget (net.vrkknn.andromuks.widget, docs/WIDGET.md).
    // Compose-for-RemoteViews: renders through the AppWidget IPC channel, so it is deliberately
    // NOT under the compose-bom platform above (Glance ships its own release train; the BOM still
    // governs its transitive compose-runtime).
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Google Maps for location sharing (MSC3488)
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.maps.android:maps-compose:8.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
    testImplementation(libs.junit)
    // MUST come before the android.jar stub on the unit-test classpath — see the testOptions
    // comment above. This is what makes JSON parsers unit-testable.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // LeakCanary auto-installs via ContentProvider when present on the classpath.
    // debugImplementation keeps it out of release builds entirely (no APK bloat, no overhead).
    // Self-reports retained-too-long Activity / Fragment / ViewModel instances in logcat and a
    // dedicated launcher icon.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    baselineProfile(project(":baselineprofile"))
}
