# Android API 30 Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lower the app's `minSdk` from 37 to 30 (Android 11) and keep every conversion path working on API 30 through API 37.

**Architecture:** The app already compiles against API 37; only the *runtime floor* moves. Three code sites call API-33-only APIs, the foreground-service type `mediaProcessing` only exists from API 34, and `POST_NOTIFICATIONS` only exists from API 33. Each gets a runtime branch. The native libraries are untouched: `libavutil.so` imports `memfd_create`, which Android provides from API 30, so the existing API-35-targeted `.so` files load fine at `minSdk 30`.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2025.09.00, minSdk 21), AGP 9.5.1, Gradle 9.6.0, NDK 28.2.13676358, Android SDK Platform 37.

## Global Constraints

- `compileSdk = 37` and `targetSdk = 37` stay unchanged; only `minSdk` moves to `30`.
- `minSdk 30` is the floor: `libavutil.so` imports `memfd_create` (weak UND), which Android provides from API 30 only.
- Dependency floor is 21 and does not bind: activity-compose 1.11.0, compose 1.9.1, material3 1.3.2 and core 1.15.0 all declare `minSdkVersion="21"`.
- The native build is not re-run. `scripts/build-native.sh` keeps `${triple}35-clang`; the resulting libraries stay 16 KB page-aligned (`LOAD` align `0x4000`).
- No version control in this workspace (not a git repository), so no commit steps. Each task is verified with Gradle instead.
- The module compiles in Kotlin explicit API mode with `-Werror`: every public declaration needs an explicit visibility modifier, and a public function needs an explicit return type unless it has a block body returning `Unit`.
- Verification gate for every task: `./gradlew :app:lintDebug` reports zero `NewApi` errors.

---

## File Structure

- Modify: `app/build.gradle.kts` — the `minSdk` floor.
- Modify: `app/src/main/AndroidManifest.xml` — foreground-service types and the permission that backs them below API 34.
- Modify: `app/src/main/java/org/videoconverter/app/ConversionService.kt` — runtime foreground-service type selection.
- Modify: `app/src/main/java/org/videoconverter/app/MainActivity.kt` — three API-33 call sites.
- Modify: `README.md` — user-facing platform statement and toolchain line.
- Modify: `docs/implementation.md` — device requirement line.

---

### Task 1: Build floor and foreground-service manifest

**Files:**
- Modify: `app/build.gradle.kts:13`
- Modify: `app/src/main/AndroidManifest.xml:2-25`

**Interfaces:**
- Consumes: nothing.
- Produces: a manifest where `ConversionService` declares `android:foregroundServiceType="dataSync|mediaProcessing"` and the app holds `android.permission.FOREGROUND_SERVICE_DATA_SYNC`. Task 2 relies on both types being declared.

- [ ] **Step 1: Set the build floor**

In `app/build.gradle.kts`, inside `defaultConfig`, change:

```kotlin
        minSdk = 37
```

to:

```kotlin
        minSdk = 30
```

`compileSdk = 37` and `targetSdk = 37` stay as they are.

- [ ] **Step 2: Declare both foreground-service types**

In `app/src/main/AndroidManifest.xml`, add the dataSync permission next to the other `uses-permission` entries:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
```

and change the service declaration to:

```xml
        <service android:name=".ConversionService" android:exported="false"
            android:foregroundServiceType="dataSync|mediaProcessing" />
```

`dataSync` is the type every platform from API 30 understands; `mediaProcessing` is the type that Android 14+ offers for long-running media work. Declaring both means the runtime type chosen in Task 2 is always a subset of what the manifest declares, which is what the platform checks at `startForeground` time.

- [ ] **Step 3: Verify the manifest merges and the floor applies**

Run: `./gradlew :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

Run: `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep sdkVersion`
Expected: `sdkVersion:'30'` and `targetSdkVersion:'37'`.

---

### Task 2: Runtime foreground-service type

**Files:**
- Modify: `app/src/main/java/org/videoconverter/app/ConversionService.kt:60-65`

**Interfaces:**
- Consumes: the manifest types from Task 1 (`dataSync|mediaProcessing`).
- Produces: `ConversionService.foregroundServiceType(): Int`, returning `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (512) on API 34+ and `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` (1) below that.

- [ ] **Step 1: Add the type selection helper**

In `app/src/main/java/org/videoconverter/app/ConversionService.kt`, add the import:

```kotlin
import android.os.Build
```

and add this member function to `ConversionService`, immediately before `requestOf`:

```kotlin
    // Android 14 introduced the mediaProcessing type. Below that, dataSync is the
    // type the platform understands, and it is declared in the manifest alongside
    // mediaProcessing so the type passed here is always a subset of it.
    @SuppressLint("InlinedApi")
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
```

The `@SuppressLint("InlinedApi")` is required because `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` is a compile-time constant that lint marks as API 34+. The value is inlined into the class file, so the branch that selects it only runs on API 34+, where the platform defines the same type.

- [ ] **Step 2: Use the helper at the startForeground call**

Replace:

```kotlin
            startForeground(notificationId, notification("Preparing files", -1, selection.label),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
```

with:

```kotlin
            startForeground(notificationId, notification("Preparing files", -1, selection.label),
                foregroundServiceType())
```

- [ ] **Step 3: Verify the service still compiles and lint is clean**

Run: `./gradlew :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: MainActivity API-33 call sites

**Files:**
- Modify: `app/src/main/java/org/videoconverter/app/MainActivity.kt:170-174`
- Modify: `app/src/main/java/org/videoconverter/app/MainActivity.kt:233-242`
- Modify: `app/src/main/java/org/videoconverter/app/MainActivity.kt:348-355`

**Interfaces:**
- Consumes: nothing.
- Produces: `MainActivity.legacyStreamUris(intent: Intent): List<Uri>`, used only on API levels below 33.

- [ ] **Step 1: Guard the recents screenshot call**

In `onCreate`, replace:

```kotlin
        setRecentsScreenshotEnabled(false)
```

with:

```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
```

- [ ] **Step 2: Read shared-stream extras on API 30-32**

In `importShare`, replace the two typed `getParcelableExtra` calls:

```kotlin
            if (intent.action == Intent.ACTION_SEND)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            else addAll(intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
```

with:

```kotlin
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (intent.action == Intent.ACTION_SEND)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                else addAll(intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            } else addAll(legacyStreamUris(intent))
```

and add this helper next to `importShare`:

```kotlin
    @Suppress("DEPRECATION")
    private fun legacyStreamUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND)
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
        else uris += intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        return uris
    }
```

- [ ] **Step 3: Skip the notification permission below API 33**

In `prepareConversion`, replace:

```kotlin
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notificationAsked", false)
        ) {
```

with:

```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notificationAsked", false)
        ) {
```

Below API 33 the permission does not exist, so the check would always report denied and the app would request a permission the platform cannot grant.

- [ ] **Step 4: Verify lint reports no NewApi errors**

Run: `./gradlew :app:lintDebug`
Expected: `Lint found 0 errors` (warnings about dependency versions and `dataExtractionRules` remain; they are pre-existing and unrelated to the API floor).

---

### Task 4: Documentation

**Files:**
- Modify: `README.md:3`
- Modify: `README.md:11`
- Modify: `docs/implementation.md:3`

**Interfaces:**
- Consumes: the final floor from Task 1 (`minSdk 30`).
- Produces: docs that state the supported platform range.

- [ ] **Step 1: Update the README platform statement**

In `README.md`, change the opening line's platform clause from "for Android 17 (API 37)" to "for Android 11 and newer (API 30 and up)".

- [ ] **Step 2: Update the README toolchain line**

In `README.md`, keep "Android SDK Platform 37" (that is the *compile* platform) and state the floor explicitly: "Install a JDK (17 or 21), Android SDK Platform 37 (compile against; the app runs on Android 11 / API 30 and newer), Build Tools 36.0.0, NDK 28.2.13676358, and the host tools Bash, GNU Make, binutils, curl, tar, xz, pkg-config, and Python 3."

- [ ] **Step 3: Update the device requirement in the implementation doc**

In `docs/implementation.md`, change "Use a connected Android 17 device" to "Use a connected device on Android 11 or newer (API 30+; Android 17 recommended)".

- [ ] **Step 4: Verify no stale platform claims remain**

Run: `grep -rn "Android 17\|API 37" README.md docs/`
Expected: only the compile-SDK reference in `README.md` ("Android SDK Platform 37") and the "Android 17 recommended" note in `docs/implementation.md`.

---

### Task 5: Final verification

**Files:**
- No file changes.

- [ ] **Step 1: Clean build and lint**

Run: `./gradlew clean :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, lint reports 0 errors.

- [ ] **Step 2: Confirm the packaged floor**

Run: `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "sdkVersion|native-code"`
Expected: `sdkVersion:'30'`, `targetSdkVersion:'37'`, `native-code: 'arm64-v8a' 'x86_64'`.

- [ ] **Step 3: Confirm the native floor matches**

Run: `readelf --dyn-syms -W app/build/native/jniLibs/arm64-v8a/libavutil.so | grep memfd_create`
Expected: one `UND memfd_create@LIBC_R` entry, which Android provides from API 30 — the same floor as `minSdk`.

- [ ] **Step 4: Confirm the foreground-service types compiled into the manifest**

Run: `aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml | grep foregroundServiceType`
Expected: `android:foregroundServiceType(0x01010599)=0x00002001` (dataSync `0x1` plus mediaProcessing `0x200`).

---

### Task 6: Explicit API mode visibility (found during execution)

**Files:**
- Modify: `app/src/main/java/org/videoconverter/app/MainActivity.kt:68`
- Modify: `app/src/test/java/org/videoconverter/app/BatchProgressTest.kt`
- Modify: `app/src/test/java/org/videoconverter/app/BatchWorkersTest.kt`
- Modify: `app/src/androidTest/java/org/videoconverter/app/ConversionMatrixTest.kt:15,559`

**Interfaces:**
- Consumes: nothing.
- Produces: a module whose `main`, `test` and `androidTest` source sets all compile.

**Background:** the module compiles in Kotlin explicit API mode with `-Werror`. Every declaration in `main` except `MainActivity` was already `internal`; `MainActivity` was public without a modifier. Incremental compilation had hidden this, because the explicit-API check only runs on files the compiler re-analyzes. Touching `MainActivity.kt` in Task 3 surfaced the error.

- [ ] **Step 1: Give MainActivity the visibility the module uses everywhere else**

In `app/src/main/java/org/videoconverter/app/MainActivity.kt`, change:

```kotlin
class MainActivity : ComponentActivity() {
```

to:

```kotlin
internal class MainActivity : ComponentActivity() {
```

The manifest entry `.MainActivity` and the `MainActivity::class.java` references in `androidTest` keep working: an `internal` class is public in the bytecode, and the `androidTest` compilation is a friend module of `main`.

- [ ] **Step 2: Declare the test source sets explicitly**

The test classes and their test methods are JUnit4 entry points and must stay public on the JVM, so they get explicit `public` rather than `internal` (an `internal` member is name-mangled and JUnit4 would not discover it).

In `app/src/test/java/org/videoconverter/app/BatchProgressTest.kt`, make the class `public class BatchProgressTest {` and each test method `public fun name() {`. Block bodies returning `Unit` need no explicit return type, and adding `: Unit` would raise a `REDUNDANT_RETURN_UNIT_TYPE` warning that `-Werror` turns into a failure.

In `app/src/test/java/org/videoconverter/app/BatchWorkersTest.kt`, make the class `public class BatchWorkersTest {` and convert each expression-bodied test to a block body:

```kotlin
    @Test
    public fun selectedLimitBoundsWork() {
        runBlocking {
            ...
        }
    }
```

- [ ] **Step 3: Give the instrumentation class explicit visibility and drop a deprecated call**

In `app/src/androidTest/java/org/videoconverter/app/ConversionMatrixTest.kt`, change the class declaration to `public class ConversionMatrixTest : Instrumentation() {`, add the import `androidx.core.view.accessibility.AccessibilityNodeInfoCompat`, and replace

```kotlin
                    if (switch.isChecked == checked) return switch
```

with

```kotlin
                    if (AccessibilityNodeInfoCompat.wrap(switch).isChecked == checked) return switch
```

`AccessibilityNodeInfo.isChecked` is deprecated and `-Werror` rejects the warning; `AccessibilityNodeInfoCompat.isChecked` is the supported replacement and `androidx.core` is already on the classpath.

- [ ] **Step 4: Verify every source set compiles and the unit tests still run**

Run: `./gradlew :app:assembleDebugAndroidTest :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` with 9 executed unit tests (6 in `BatchProgressTest`, 3 in `BatchWorkersTest`), no failures.

---

## Self-Review

**Spec coverage:** Every item from the agreed change list is mapped: item 1 (minSdk) is Task 1 Step 1; items 2-4 (MainActivity guards) are Task 3 Steps 1-3; item 5 (manifest types and permission) is Task 1 Step 2; item 6 (runtime type selection) is Task 2; the documentation update is Task 4. Verification is Task 5.

**Placeholder scan:** No TBD, no "handle edge cases", no "similar to Task N". All code blocks contain the exact replacement text.

**Type consistency:** `foregroundServiceType(): Int` is defined in Task 2 and consumed in Task 2 Step 2. `legacyStreamUris(intent: Intent): List<Uri>` is defined in Task 3 Step 2 and consumed in the same step. `Build.VERSION_CODES.TIRAMISU` (33) and `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` (34) are used consistently.

**Known gap:** the device matrix in `docs/implementation.md` still requires a connected device to run the instrumentation suite; that suite has not been executed on API 30 hardware as part of this plan.

---

## Device Verification Results

Executed after the plan, on 2026-11-15, with the shipped build (APK md5 `3055d7a35a8c72fc8c76c1ebafbcfcf7`).

| Device | API | Suite | Result |
|---|---|---|---|
| `emulator-5560` (AVD `Pixel_5_API30`) | 30 | full conversion matrix | **passed** — host verifier: 2282/2282 conversions, 0 failures, 154 blocked (unsupported source codecs) |
| `emulator-5566` (AVD `Pixel_5_API33`) | 33 | full conversion matrix | **passed** — host verifier: 2282/2282 conversions, 0 failures, 154 blocked |
| `emulator-5562` (AVD `Pixel_5`) | 31 | conversion matrix | **passed** (`Conversion matrix passed`); the emulator exited before its exports could be pulled |
| `45301FDJG0007R` (Pixel 8 Pro) | 37 | regression matrix | foreground service confirmed `types=0x00002000` (mediaProcessing); matrix produced MP3→VORBIS outputs in one run, later runs hit the suite's 240 s per-batch deadline while the phone was saturated by unrelated processes (Firefox 151 % CPU, scrcpy, swap exhausted). No crash, no foreground-service error. |

Share-intent path (`ACTION_SEND` with a `content://media/...` URI, launched straight at the component):

- API 30 — legacy branch (`getParcelableExtra` without a class): the app stored the shared URI in its draft (`files/active-draft` plus `files/drafts/<id>`), no errors in logcat.
- API 33 — typed branch (`getParcelableExtra(name, Uri::class.java)`): same result.

Foreground-service type, measured:

- API 37 — `dumpsys activity services` reports `types=0x00002000` (mediaProcessing), `isForeground=true`.
- API 30 — the service ran as a foreground service with a live notification on channel `conversion` throughout the matrix.

Negative control on API 30, to find out whether the pre-34 branch is load-bearing:

- Build that always passes `mediaProcessing` (0x200) on API 30: matrix **passed**. The type is accepted there.
- Build that passes 0x1000, a bit the manifest does not declare: matrix **failed** with the app's own `Android could not start conversion` message.

Conclusion: the platform check is a subset test against the declared bitmask, and `aapt2` compiles `android:foregroundServiceType="dataSync|mediaProcessing"` to the integer `0x2001`, so pre-34 platforms read the same integer mask. Passing `mediaProcessing` on API 30 is accepted because `0x200` is a subset of `0x2001`. The `dataSync` branch is therefore not required for the service to start; it is kept because `dataSync` is the type those platforms actually define, and because the manifest must declare `dataSync` for the pre-34 branch to be a subset of the declared mask.

