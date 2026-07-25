plugins {
    id("com.android.test")
    alias(libs.plugins.baselineprofile)
    // AGP 9 provides built-in Kotlin; the standalone kotlin("android") plugin is removed.
}

android {
    namespace = "pt.aguiarvieira.andromuks.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app now has product flavors; generate the baseline profile against the base flavor only.
        missingDimensionStrategy("variant", "base")
    }

    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Kotlin 2.x removed the AGP `kotlinOptions {}` DSL; use the KGP compilerOptions extension
// (mirrors the app module).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0-alpha06")
    // Declared explicitly rather than leaned on transitively via benchmark-macro: the profile
    // journey drives the UI through UiDevice, so a future benchmark-macro upgrade that stopped
    // exporting it would break this module with a confusing unresolved-reference error.
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}