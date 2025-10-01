# Build Troubleshooting Guide

## Issue: KAPT Build Failure

### Problem
```
Execution failed for task ':app:kaptReleaseKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask$KaptExecutionWorkAction
```

### Root Cause
KAPT (Kotlin Annotation Processing Tool) is deprecated and can cause build issues, especially with newer Kotlin versions and Room.

### Solution Applied ✅

#### 1. Switch from KAPT to KSP
**Before:**
```kotlin
plugins {
    kotlin("kapt")
}

dependencies {
    kapt(libs.androidx.room.compiler)
}
```

**After:**
```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.2.20-1.0.25"
}

dependencies {
    ksp(libs.androidx.room.compiler)
}
```

#### 2. Add KSP Configuration
```kotlin
android {
    // ... other config
    
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
    }
}
```

#### 3. Enable Schema Export
```kotlin
@Database(
    entities = [...],
    version = 1,
    exportSchema = true  // Changed from false
)
```

### Why KSP is Better

1. **Performance**: KSP is 2x faster than KAPT
2. **Compatibility**: Better support for newer Kotlin versions
3. **Reliability**: More stable annotation processing
4. **Future-proof**: KAPT is deprecated, KSP is the future

### Alternative Solutions (if KSP doesn't work)

#### Option 1: Use KAPT with specific configuration
```kotlin
plugins {
    kotlin("kapt")
}

android {
    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }
}
```

#### Option 2: Use Room without annotation processing
```kotlin
dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Remove ksp/kapt line
}
```

### Build Commands

#### Clean and Rebuild
```bash
./gradlew clean
./gradlew build
```

#### Debug Build Issues
```bash
./gradlew build --info
./gradlew build --debug
```

#### Check KSP Processing
```bash
./gradlew kspDebugKotlin --info
```

### Common Issues and Solutions

#### 1. KSP Version Compatibility
Make sure KSP version matches your Kotlin version:
```kotlin
id("com.google.devtools.ksp") version "2.2.20-1.0.25"
```

#### 2. Room Schema Location
Ensure the schema directory exists:
```bash
mkdir -p app/schemas
```

#### 3. Java Version Compatibility
Make sure you're using Java 21 (as configured in build.gradle.kts):
```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

### Verification Steps

1. **Clean Build**: `./gradlew clean`
2. **Check Dependencies**: Verify Room dependencies are correct
3. **Test Compilation**: `./gradlew compileDebugKotlin`
4. **Run Tests**: `./gradlew test`

### If Issues Persist

#### Option 1: Remove Room temporarily
Comment out Room dependencies and database code to isolate the issue:
```kotlin
// implementation(libs.androidx.room.runtime)
// implementation(libs.androidx.room.ktx)
// ksp(libs.androidx.room.compiler)
```

#### Option 2: Use different Room version
Try a different Room version in `gradle/libs.versions.toml`:
```toml
room = "2.5.0"  # Instead of 2.6.1
```

#### Option 3: Check Kotlin version compatibility
Ensure your Kotlin version is compatible with the Room version.

### Success Indicators

✅ **Build succeeds without errors**
✅ **Database classes compile**
✅ **Room entities are processed correctly**
✅ **Tests pass**

### Next Steps After Fix

1. **Test Database**: Run the simple database test
2. **Verify Entities**: Check that all entities compile
3. **Test DAOs**: Verify DAO interfaces work
4. **Integration Test**: Test database initialization

The switch to KSP should resolve the build issues and provide a more stable foundation for your database integration.
