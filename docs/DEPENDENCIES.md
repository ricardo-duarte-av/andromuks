# Dependencies & build toolchain

Reference for how dependencies are declared and the non-obvious constraints discovered during the
mid-2026 "fully up to date" modernization. Read this before bumping the toolchain or major libs —
several upgrades have hard ordering/compatibility requirements that fail in non-obvious ways.

## Where versions live

- **`gradle/libs.versions.toml`** — the version catalog: AGP, Kotlin, Compose BOM, OkHttp, navigation,
  lifecycle, material3, baselineprofile plugin, material-kolor, test libs. Prefer this for anything
  referenced via `libs.*`.
- **`app/build.gradle.kts` `dependencies {}`** — hardcoded coordinates for libs not in the catalog
  (Firebase BOM, Coil, Media3, CameraX, Maps, WorkManager, webkit, biometric, serialization runtime,
  LeakCanary, etc.).
- **`build.gradle.kts` (root) `plugins {}`** — plugin versions applied `false`: google-services,
  firebase-crashlytics, firebase-perf.
- **`gradle/wrapper/gradle-wrapper.properties`** — the Gradle distribution.

Signing config and product flavors are documented in [../CLAUDE.md](../CLAUDE.md); the Firebase
Crashlytics/Performance plugins in [OBSERVABILITY.md](OBSERVABILITY.md).

## The single-Compose-BOM rule

Drive **all** Compose artifacts from the one `compose-bom` (declared in the catalog). Do **not** pin
individual Compose artifact versions (`foundation`, `ui`, `animation`, `material3`, `foundation-layout`)
— an explicit version silently overrides the BOM and can pin the whole UI to an old runtime. This bit
us once: explicit `foundation`/`ui` `1.7.8` + an `animation` alpha were overriding a 2026 BOM, so the
app actually ran a late-2024 Compose. If you need a Compose artifact, add it BOM-managed (no version),
e.g. `implementation(libs.androidx.compose.foundation)`.

`material3` is the one deliberate exception: it's pinned to a `1.5.0-alpha` in the catalog (currently
`1.5.0-alpha19`) because the app uses Material 3 **Expressive** components — `ExpressiveLoadingIndicator`,
`ExpressiveStatusRow`, `ExpressiveAvatarMaskModifier`, etc., under `@OptIn(ExperimentalMaterial3ExpressiveApi)`
(50+ usages). These are alpha-only and absent from the BOM's stable material3 (~1.4.x), so the pin is
**higher** than the BOM and must stay — dropping it breaks compilation. Bump it to the newest `1.5.0-alphaNN`
when convenient (Dependabot proposes newer pre-releases automatically since the pinned version is already a
pre-release); validate locally because alpha APIs can shift between alphas.

⚠️ **Cascade to compileSdk:** a material3 alpha can transitively drag the whole Compose stack to a newer
alpha that requires a higher `compileSdk`. `1.5.0-alpha19` pulled Compose `1.12.0-alpha02`, which requires
**compileSdk 37** (`CheckAarMetadata` fails otherwise). `compileSdk` is set to 37 in both `app` and
`baselineprofile`; `targetSdk` stays 36 (compileSdk only controls what APIs you compile against — no runtime
behaviour change). So bumping the material3 alpha may also require bumping `compileSdk` and having that
platform installed.

## AGP 9 migration constraints (learned the hard way)

AGP 9 is a real migration, not a bump. In order, the walls and their fixes:

1. **Gradle minimum** — AGP 9.2 requires Gradle **9.4.1+**. Bumping AGP without the wrapper fails at
   `version-check`.
2. **Built-in Kotlin is mandatory** — the standalone `org.jetbrains.kotlin.android` plugin is
   *incompatible* and must be **removed** from every module (app + `baselineprofile`). AGP provides
   Kotlin itself; keep the `kotlin.plugin.compose` and `kotlin.plugin.serialization` compiler plugins.
3. **`kotlinOptions {}` is gone** (removed in Kotlin 2.x) — use the top-level
   `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }` extension instead.
4. **firebase-perf plugin** must be **2.0.2+** — 1.4.2 uses the removed
   `com.android.build.api.transform.Transform` API (`Could not create plugin of type FirebasePerfPlugin`).
5. **baselineprofile / benchmark** must be on the **1.5.0-alpha** track for AGP 9.x — 1.4.x rejects
   AGP 9.2 with `Module ':app' is not a supported android module`.
6. **`buildFeatures.resValues` defaults to off in AGP 9** — re-enable it (`resValues = true`), since the
   product flavors set `app_name` via `resValue(...)` (`Product Flavor base contains custom resource
   values, but the feature is disabled`).

Bonus: AGP 9's newer R8 resolves the "An error occurred when parsing kotlin metadata" warnings that
Kotlin 2.3.20 triggers under AGP 8.13's older R8.

## Firebase: no more `-ktx`

Firebase BOM **33+** removed the `-ktx` artifacts (the Kotlin APIs moved into the main modules). Use the
plain coordinates: `firebase-messaging`, `firebase-analytics`, `firebase-crashlytics`, `firebase-perf`
(not `…-ktx`). A `…-ktx` dependency under BOM 34 resolves to nothing → `Could not find …:`.

## Coil 3 notes

Migrated from Coil 2. Key differences if you touch image loading:

- Coordinates `io.coil-kt.coil3:*`, package `coil3.*`. **Network loading is opt-in** — `coil-network-okhttp`
  is required and is wired through the shared `OkHttpClient` in `ImageLoaderSingleton` via
  `OkHttpNetworkFetcherFactory` (replaces the removed `.okHttpClient()`), so the auth-cookie /
  `?encrypted=` interceptors apply to all image loads.
- Renames: `ImageDecoderDecoder` → `AnimatedImageDecoder`; `MemoryCache.Builder()` takes no context
  (`maxSizePercent(context, …)` does); `DiskCache.directory` takes an `okio.Path`
  (`File.toOkioPath()`); `respectCacheHeaders(false)` is the new default (removed).
- `ImageResult.drawable` → `(result as? SuccessResult)?.image?.asDrawable(resources)`;
  `placeholder(Drawable)` → `placeholder(drawable.asImage())`.
- `crossfade`, `allowHardware`, `allowRgb565` are now **extension functions** — import them
  (`coil3.request.*`).
- **Auth cookies**: per-request `ImageRequest.Builder.addHeader("Cookie", …)` was removed *and* is
  redundant — `ImageLoaderSingleton`'s interceptor injects it for `/_gomuks/media/`. Do **not** strip
  `addHeader` from OkHttp `Request.Builder` chains (uploads/downloads in `MediaUploadUtils`,
  `VideoUploadUtils`, `NetworkUtils`, and the OkHttp paths in `MediaFunctions`/`UserInfo`/
  `UrlPreviewComposition`) — those genuinely need the cookie and are not covered by the Coil interceptor.

## Upgrade workflow

Validate **locally before CI** — much faster than the 4-flavor matrix (~15 min):

```bash
./gradlew :app:compileBaseDebugKotlin   # ~1–2 min, catches API/resolution errors
./gradlew :app:assembleBaseRelease      # exercises R8/minification
```

Then push and trigger the matrix (the workflows only auto-run on master/develop/main, so for a feature
branch use `gh workflow run build.yml --ref <branch>`). Docs-only commits are skipped by `paths-ignore`.

## Automated updates (Dependabot)

`.github/dependabot.yml` runs **weekly** and opens PRs for two ecosystems:

- **`gradle`** — app deps and version-catalog entries. Minor/patch updates are **grouped** into one PR
  (`gradle-minor-patch`); **majors get their own PR** because they usually need migration work (Coil 2→3,
  AGP 9, etc. — Dependabot opens the PR but a human does the migration; the build will be red until then).
  Because `material3` is pinned to a pre-release, Dependabot also proposes newer `material3` alphas.
- **`github-actions`** — bumps action versions; this is what gradually clears the Node 20 deprecation.

Every PR (Dependabot or human) is validated by **`.github/workflows/pr-check.yml`** — a lightweight job
that builds **only the `base` flavor** (`assembleBaseDebug` + `compileBaseReleaseKotlin`). It is
deliberately **secret-free** (debug auto-keystore + a release *compile*, no R8 signing) because
Dependabot PR runs don't get the regular Actions secrets. The full 4-flavor matrix + signing stays on
push to master (`build.yml`).

**Merges are manual.** Review each green PR and merge it yourself. (Auto-merge of patch/minor is possible
via `dependabot/fetch-metadata` + `gh pr merge --auto` plus a branch-protection rule requiring the
PR check, but we deliberately keep it manual for now.)

## Known "latest stable" ceilings (mid-2026)

Left at current versions because newer is alpha-only or already latest: **CameraX** 1.5.3,
**biometric** 1.1.0 (1.2.0 is alpha), **LeakCanary** 2.14 (3.0 is alpha), **material-kolor** 4.1.1,
**play-services-location** 21.3.0, **jlatexmath-android** 0.2.0 (unmaintained).
