// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
    id("com.google.firebase.firebase-perf") version "2.0.2" apply false
    alias(libs.plugins.baselineprofile) apply false  // add this
    alias(libs.plugins.detekt) apply false
}

// Detekt is the single Kotlin static-analysis + formatting gate for the whole project. The
// `detekt-rules-ktlint-wrapper` plugin embeds ktlint's rule engine (driven by the repo-root
// .editorconfig), and the compose-rules ruleset adds Jetpack Compose checks. Android's built-in
// `lint` runs separately (configured in app/build.gradle.kts). Applied to every module so new
// modules are covered automatically. See docs and config/detekt/detekt.yml.
subprojects {
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // Merge our overrides on top of detekt's bundled defaults instead of shipping a full
        // (huge) generated config that drifts out of date on every detekt upgrade.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Per-module baseline: records the pre-existing findings so CI fails only on NEW issues.
        // The file is created by the one-time `detektBaseline` task; a missing file is treated as
        // an empty baseline (all findings reported), so this is safe before bootstrap.
        baseline = file("detekt-baseline.xml")
        // Auto-correct is CLI-only in detekt 2.0: run `./gradlew detekt --auto-correct`.
    }

    // Resolve catalog entries via the public VersionCatalogsExtension: the generated type-safe
    // `libs` accessor is not reliably visible inside a subprojects {} closure.
    val libs = rootProject.extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")
    dependencies {
        add("detektPlugins", libs.findLibrary("detekt-ktlint-wrapper").get())
        add("detektPlugins", libs.findLibrary("compose-rules-detekt").get())
    }
}
