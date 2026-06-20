# 🔍 PhotoDoctor Pro — Full Codebase Audit Report & Final Round Production Plan

**Repository:** `aasheesh333/PhotoDoctor-Pro`
**Branch:** `fix-admob-diagnostics-versioning`
**Commit:** `86dbec2`
**Audit Date:** June 21, 2026

---

## 📂 Files Audited (All 27 source files + config + resources)

| Category | Files |
|---|---|
| **Config** | `build.gradle.kts` (root + app), `settings.gradle.kts`, `proguard-rules.pro`, `.gitignore` |
| **Manifest** | `AndroidManifest.xml` |
| **Activity** | `MainActivity.kt` |
| **Utils** | `BitmapUtils.kt`, `AdManager.kt` |
| **Components** | `BannerAd.kt` |
| **Navigation** | `AppNavigation.kt`, `AppScaffold.kt`, `BottomNavigationBar.kt` |
| **Screens** | `HomeScreen.kt`, `EnhanceImageScreen.kt`, `EnhanceImageViewModel.kt`, `ObjectEraserScreen.kt`, `ObjectEraserViewModel.kt`, `RemoveBackgroundScreen.kt`, `RemoveBackgroundViewModel.kt`, `ImageToPdfScreen.kt`, `ImageToPdfViewModel.kt`, `HistoryScreen.kt`, `HistoryViewModel.kt`, `SettingsScreen.kt`, `PrivacyPolicyScreen.kt`, `TermsAndConditionsScreen.kt`, `ViewModelFactory.kt` |
| **Data** | `AppDatabase.kt`, `History.kt`, `HistoryDao.kt`, `HistoryRepository.kt` |
| **Theme** | `Color.kt`, `Theme.kt`, `Type.kt` |
| **Resources** | `strings.xml`, `colors.xml`, `themes.xml` |

---

## 🐛 BUGS FOUND (Categorized by Severity)

---

### 🔴 CRITICAL BUGS (App Crashes / Data Loss / Core Features Broken)

---

#### BUG #1: Object Eraser — Coordinate Mapping Mismatch (THE "CROP SIZE" BUG)

**File:** `ObjectEraserScreen.kt` + `ObjectEraserViewModel.kt`
**Severity:** CRITICAL
**User Symptom:** "Crop karta hu to size kuchh aur ho jata hai"

**Root Cause:**
The Image in `ObjectEraserScreen` is displayed using `Modifier.fillMaxSize()` which stretches the bitmap to fill the entire Box container. The user draws paths on this stretched image using Compose Canvas coordinates (screen pixels). However, in `ObjectEraserViewModel.createMask()`, the mask bitmap is created using the **original bitmap dimensions** (`originalBitmap.width, originalBitmap.height`). The path coordinates from the screen are NOT scaled to match the original bitmap dimensions.

**Result:**
- The mask is drawn at wrong positions relative to the actual image content
- Inpainting is applied to wrong areas
- The output appears to have "different size" or wrong content because the mask doesn't align

**Code Evidence:**
```kotlin
// ObjectEraserScreen.kt — Image fills the entire container (stretched)
Image(
    bitmap = uiState.originalBitmap!!.asImageBitmap(),
    modifier = Modifier.fillMaxSize()  // ← STRETCHES bitmap to container size
    ...
)

// Canvas also fillMaxSize — paths are in screen coordinates
Canvas(modifier = Modifier.fillMaxSize()) { ... }

// ObjectEraserViewModel.kt — Mask uses ORIGINAL bitmap dimensions
val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
// paths drawn here are in screen coords, but canvas is original bitmap size
```

**Fix:**
1. Use `ContentScale.Fit` on the Image so it maintains aspect ratio
2. Calculate the actual displayed image rect (offset + size) within the container
3. Scale path coordinates from screen space to bitmap space before creating the mask
4. Pass the scale/offset info to the ViewModel

---

#### BUG #2: Enhance Image — OOM Crash on 4x Upscale (THE "ADJUST CRASH" BUG)

**File:** `EnhanceImageViewModel.kt`
**Severity:** CRITICAL
**User Symptom:** "Adjust wale feature me app crash ho raha hai"

**Root Cause:**
`RealESRGAN(activity.assets).upscale(bitmap, 4)` performs a 4x upscale. For a 1000×1000 image, this creates a 4000×4000 bitmap = ~64MB in ARGB_8888 format. For larger images (e.g., 2000×2000 → 8000×8000 = ~256MB), this causes `OutOfMemoryError` and instant app crash.

**Code Evidence:**
```kotlin
val enhancedBitmap = realESRGAN!!.upscale(bitmap, 4)  // ← 4x upscale, no size check
```

**Additional Issues:**
- No try-catch around `OutOfMemoryError` (it's an Error, not Exception)
- No image size validation before processing
- No progress indication of how long this will take
- `realESRGAN!!` can NPE if initialization failed silently

**Fix:**
1. Check `bitmap.width * bitmap.height` before upscaling
2. For images > 500×500, either downscale first or use 2x instead of 4x
3. Catch `OutOfMemoryError` explicitly
4. Show estimated processing time
5. Add a cancel button

---

#### BUG #3: Object Eraser — OpenCV Not Checked Before Use (CRASH)

**File:** `ObjectEraserViewModel.kt`
**Severity:** CRITICAL

**Root Cause:**
`OpenCVLoader.initDebug()` is called in the `init` block. If it fails, an error message is set in state. But `eraseObjects()` never checks if OpenCV was successfully initialized before calling `Utils.bitmapToMat()`. If OpenCV isn't loaded, this will crash with `UnsatisfiedLinkError`.

**Code Evidence:**
```kotlin
init {
    if (!OpenCVLoader.initDebug()) {
        _uiState.value = _uiState.value.copy(error = "OpenCV initialization failed.")
    }
    // Error is set but NOT blocking — user can still tap "Erase"
}

fun eraseObjects() {
    // No check for OpenCV initialization status!
    val resultBitmap = applyInpainting(originalBitmap, maskBitmap)  // ← CRASH if OpenCV not loaded
}
```

**Fix:**
1. Track OpenCV initialization status in UI state
2. Disable the Erase button if OpenCV failed to load
3. Wrap OpenCV calls in try-catch for `UnsatisfiedLinkError`

---

#### BUG #4: Images Not Saving — Permission + Storage Issue (THE "SAVE NOT WORKING" BUG)

**File:** `MainActivity.kt`, `BitmapUtils.kt`, `AndroidManifest.xml`
**Severity:** CRITICAL
**User Symptom:** "Kisi kisi me images save hi nahi ho rahi hai"

**Root Cause (Multiple Issues):**

**4a. Wrong Permission Requested:**
`MainActivity.kt` requests `Manifest.permission.READ_EXTERNAL_STORAGE`, but the manifest declares `READ_MEDIA_IMAGES` (Android 13+). On Android 13+ (API 33+), `READ_EXTERNAL_STORAGE` is deprecated and ignored. The permission request does nothing, and the app can't read images on newer devices.

```kotlin
// MainActivity.kt
requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)  // ← WRONG on API 33+
```

**4b. No WRITE Permission:**
The app saves to `getExternalFilesDir(null)` which doesn't require WRITE permission, BUT files saved there are NOT visible in the phone's gallery. Users think the image wasn't saved because they can't find it.

**4c. No MediaScanner Connection:**
After saving a file, no `MediaScannerConnection.scanFile()` is called, so the file doesn't appear in gallery apps.

**4d. No Error Feedback on Save Failure:**
If `getExternalFilesDir(null)` returns null (rare but possible on full storage), `File(null, fileName)` creates a file in the wrong location or throws.

**Fix:**
1. Request `READ_MEDIA_IMAGES` on API 33+, `READ_EXTERNAL_STORAGE` on API < 33
2. Use `MediaStore` API on API 29+ to save to gallery (visible to users)
3. Fall back to `getExternalFilesDir` on older APIs
4. Call `MediaScannerConnection.scanFile()` after saving
5. Show a Toast/Snackbar with the file path after successful save
6. Add proper error handling for null directory

---

#### BUG #5: `context as Activity` Unsafe Cast (CRASH)

**File:** `ObjectEraserScreen.kt`, `EnhanceImageScreen.kt`, `RemoveBackgroundScreen.kt`, `ImageToPdfScreen.kt`
**Severity:** HIGH

**Root Cause:**
All screens do `val activity = context as Activity`. In Compose, `LocalContext.current` is usually an `Activity`, but this can fail if the context is wrapped (e.g., `ContextThemeWrapper`). This will throw `ClassCastException`.

**Fix:**
Create a utility function:
```kotlin
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
```

---

### 🟠 HIGH SEVERITY BUGS (Functional Issues / Poor UX)

---

#### BUG #6: No Back Button on Feature Screens

**File:** All feature screens (`RemoveBackgroundScreen.kt`, `EnhanceImageScreen.kt`, `ImageToPdfScreen.kt`)
**Severity:** HIGH

**Issue:**
`ObjectEraserScreen` has a TopAppBar with actions but no back navigation icon. `RemoveBackgroundScreen`, `EnhanceImageScreen`, and `ImageToPdfScreen` have TopAppBars with only titles — no back button at all. Users are trapped on these screens unless they use the bottom nav bar (which doesn't navigate back properly).

**Fix:**
Add `navigationIcon` to all feature screen TopAppBars:
```kotlin
TopAppBar(
    title = { Text(...) },
    navigationIcon = {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
    }
)
```

---

#### BUG #7: Bottom Navigation Bar Shows on All Screens

**File:** `AppScaffold.kt`
**Severity:** HIGH

**Issue:**
The bottom navigation bar (Home, History, Settings) is shown on ALL screens including feature screens and legal screens. It should only be visible on the three main tabs.

**Fix:**
Conditionally show the bottom bar based on the current route:
```kotlin
val navBackStackEntry by navController.currentBackStackEntryAsState()
val currentRoute = navBackStackEntry?.destination?.route
val showBottomBar = currentRoute in listOf("home", "history", "settings")

Scaffold(
    bottomBar = { if (showBottomBar) BottomNavigationBar(navController) }
)
```

---

#### BUG #8: AdMob Test IDs in Production

**File:** `AdManager.kt`, `BannerAd.kt`, `AndroidManifest.xml`
**Severity:** HIGH

**Issue:**
All ad unit IDs are Google's test IDs:
- App ID: `ca-app-pub-3940256099942544~3347511713` (test)
- Interstitial: `ca-app-pub-3940256099942544/1033173712` (test)
- Banner: `ca-app-pub-3940256099942544/6300978111` (test)

Using test ads in production means no revenue and Google may flag the app.

**Fix:**
Replace all test IDs with real AdMob ad unit IDs. Use BuildConfig fields to differentiate debug vs release:
```kotlin
buildTypes {
    debug {
        buildConfigField("String", "AD_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
    }
    release {
        buildConfigField("String", "AD_APP_ID", "\"ca-app-pub-XXXXXXXX~XXXXXXXX\"")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-XXXXXXXX/XXXXXXXX\"")
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-XXXXXXXX/XXXXXXXX\"")
    }
}
```

---

#### BUG #9: BitmapUtils.loadBitmapFromUri — No OOM Protection

**File:** `BitmapUtils.kt`
**Severity:** HIGH

**Issue:**
`BitmapFactory.decodeStream(it)` loads the full-resolution image into memory. A 4000×3000 photo = ~48MB in ARGB_8888. Multiple such loads will cause OOM.

**Code:**
```kotlin
suspend fun loadBitmapFromUri(uri: Uri, context: Context): Bitmap? = withContext(Dispatchers.IO) {
    return@withContext context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)  // ← No sampling, no OOM protection
    }
}
```

**Fix:**
1. First decode with `inJustDecodeBounds = true` to get image dimensions
2. Calculate `inSampleSize` based on target resolution
3. Decode again with the sample size
4. Catch `OutOfMemoryError`

---

#### BUG #10: Object Eraser — Image Stretched (No ContentScale)

**File:** `ObjectEraserScreen.kt`
**Severity:** HIGH

**Issue:**
The Image composable uses `Modifier.fillMaxSize()` without specifying `contentScale`. Default is `ContentScale.Fit` for Image, but the Canvas overlay is also `fillMaxSize()` which doesn't match the actual image display area. The brush strokes appear in wrong positions.

**Fix:**
Use `ContentScale.Fit` explicitly and calculate the displayed image bounds for the Canvas overlay.

---

#### BUG #11: No ProGuard/R8 Minification (Production Security Issue)

**File:** `app/build.gradle.kts`
**Severity:** HIGH

**Issue:**
`isMinifyEnabled = false` in the release build type. The APK ships with full unobfuscated code. All ProGuard rules in `proguard-rules.pro` are commented out. Libraries like OpenCV, RealESRGAN, ML Kit, and Room need keep rules.

**Fix:**
1. Set `isMinifyEnabled = true` for release
2. Add proper ProGuard keep rules for OpenCV, RealESRGAN, ML Kit, Room, Coil
3. Test the release build thoroughly

---

#### BUG #12: RemoveBackground — foregroundBitmap Dimension Mismatch

**File:** `RemoveBackgroundViewModel.kt`
**Severity:** HIGH

**Issue:**
ML Kit's `SubjectSegmentation` returns a `foregroundBitmap` that may have different dimensions than the original image. `processMask()` creates a result bitmap with `originalBitmap.width/height` but draws `foregroundBitmap` at (0,0) without scaling. If dimensions differ, the result is misaligned or cropped.

**Fix:**
```kotlin
val scaledFg = Bitmap.createScaledBitmap(foregroundBitmap, originalBitmap.width, originalBitmap.height, true)
```

---

#### BUG #13: ImageToPdf — Memory Leak (Bitmaps Not Recycled)

**File:** `ImageToPdfViewModel.kt`
**Severity:** HIGH

**Issue:**
In `createPdf()`, bitmaps are loaded for each image but never recycled. For 10+ images, this accumulates hundreds of MB of bitmap memory.

**Fix:**
Recycle each bitmap after drawing it to the PDF page:
```kotlin
page.canvas.drawBitmap(bitmap, 0f, 0f, null)
pdfDocument.finishPage(page)
bitmap.recycle()  // ← Add this
```

---

#### BUG #14: ImageToPdf — Inconsistent Page Sizes

**File:** `ImageToPdfViewModel.kt`
**Severity:** MEDIUM

**Issue:**
Each PDF page is created with `bitmap.width, bitmap.height`. If images have different dimensions, the PDF has inconsistent page sizes. A 4000×3000 photo and a 800×600 photo will have wildly different page sizes in the same PDF.

**Fix:**
Use a standard page size (A4: 595×842 points) and scale images to fit within it.

---

#### BUG #15: ImageToPdf — pdfCreationSuccess State Not Reset

**File:** `ImageToPdfViewModel.kt`, `ImageToPdfScreen.kt`
**Severity:** MEDIUM

**Issue:**
After PDF creation, `pdfCreationSuccess = true` triggers `navController.popBackStack()` in a `LaunchedEffect(Unit)`. But the state is never reset to `false`. If the user navigates back to ImageToPdf, the `LaunchedEffect` fires again immediately and pops back.

**Fix:**
Reset state after navigation:
```kotlin
LaunchedEffect(uiState.pdfCreationSuccess) {
    if (uiState.pdfCreationSuccess) {
        viewModel.resetSuccess()
        navController.popBackStack()
    }
}
```

---

### 🟡 MEDIUM SEVERITY BUGS

---

#### BUG #16: HomeScreen — pressed State Never Reset

**File:** `HomeScreen.kt`
**Severity:** MEDIUM

**Issue:**
In `FeatureCard`, `pressed = true` is set on click but never reset to `false`. The card stays at 0.95x scale permanently after first tap.

**Fix:**
Use `animateFloatAsState` with a press-release pattern, or reset `pressed` after the animation:
```kotlin
.clickable {
    pressed = true
    navController.navigate(feature.route)
    // Reset after a short delay
    scope.launch {
        delay(100)
        pressed = false
    }
}
```

---

#### BUG #17: Dark Mode Not Implemented

**File:** `Theme.kt`
**Severity:** MEDIUM

**Issue:**
`PhotoDoctorProTheme` accepts `darkTheme` parameter but always uses `LightColorScheme`. The `isAppearanceLightStatusBars = darkTheme` line is also backwards — when dark theme is active, status bar icons should be light (not dark).

**Fix:**
Create a `DarkColorScheme` and use it when `darkTheme` is true. Fix the status bar icon logic.

---

#### BUG #18: No Database Migration Strategy

**File:** `AppDatabase.kt`
**Severity:** MEDIUM

**Issue:**
No migration strategy defined. If the database schema changes in a future update, the app will crash with `IllegalStateException`.

**Fix:**
Add `fallbackToDestructiveMigration()` for now, or define proper migrations:
```kotlin
Room.databaseBuilder(...)
    .fallbackToDestructiveMigration()
    .build()
```

---

#### BUG #19: Version Info Hardcoded

**File:** `SettingsScreen.kt`, `strings.xml`
**Severity:** MEDIUM

**Issue:**
Settings shows "Version 1.0" from string resources. This won't update when the version changes in `build.gradle.kts`.

**Fix:**
Use `BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` dynamically.

---

#### BUG #20: RemoveBackground — No Loading State on Save

**File:** `RemoveBackgroundViewModel.kt`
**Severity:** MEDIUM

**Issue:**
`saveImage()` doesn't set `isLoading = true`, so the save button remains enabled. Users can tap it multiple times, creating duplicate saves.

**Fix:**
Set `isLoading = true` at the start of `saveImage()` and `false` in a `finally` block.

---

#### BUG #21: Object Eraser — Erased Image Saved as JPEG (Loses Quality)

**File:** `ObjectEraserViewModel.kt`
**Severity:** MEDIUM

**Issue:**
Erased images are saved as JPEG with 95% quality. JPEG compression introduces artifacts, especially around the inpainted areas. The file extension is `.jpg`.

**Fix:**
Save as PNG for lossless quality, or offer the user a choice of format.

---

#### BUG #22: No File Existence Check in History

**File:** `HistoryScreen.kt`
**Severity:** MEDIUM

**Issue:**
History items show `filePath` but don't verify the file still exists. If the app's cache is cleared, the file is gone but the history entry remains. Tapping a history item does nothing — no way to view or share the result.

**Fix:**
1. Check file existence before displaying
2. Add a click handler to open/share the file
3. Add a swipe-to-delete on history items

---

#### BUG #23: No Sharing Functionality

**File:** All feature screens
**Severity:** MEDIUM

**Issue:**
After processing an image, the only option is "Save". There's no share button to send the result directly to other apps.

**Fix:**
Add a share button using `Intent.ACTION_SEND`:
```kotlin
val shareIntent = Intent().apply {
    action = Intent.ACTION_SEND
    type = "image/*"
    putExtra(Intent.EXTRA_STREAM, uri)
}
context.startActivity(Intent.createChooser(shareIntent, "Share image"))
```

---

#### BUG #24: ImageToPdf — Reorder Logic Bug

**File:** `ImageToPdfViewModel.kt`
**Severity:** MEDIUM

**Issue:**
`onImageReordered(from, to)` uses `currentList.add(to, currentList.removeAt(from))`. When dragging down, the indices shift after removal, causing the item to land one position off.

**Fix:**
Adjust the target index when dragging downward:
```kotlin
fun onImageReordered(from: Int, to: Int) {
    val currentList = _uiState.value.selectedImageUris.toMutableList()
    val item = currentList.removeAt(from)
    val adjustedTo = if (from < to) to - 1 else to
    currentList.add(adjustedTo, item)
    _uiState.value = _uiState.value.copy(selectedImageUris = currentList)
}
```

---

### 🟢 LOW SEVERITY / CODE QUALITY ISSUES

---

#### BUG #25: No App Icon (mipmap) in Repo

**File:** `AndroidManifest.xml` references `@mipmap/ic_launcher`
**Issue:** No `mipmap` directory exists in the repo. The app likely uses a default icon or the build fails.

---

#### BUG #26: No Splash Screen

**Issue:** No splash screen API for Android 12+. The app shows a blank screen during cold start.

---

#### BUG #27: No Global Error Handling

**Issue:** No `Thread.setDefaultUncaughtExceptionHandler`. Uncaught exceptions crash the app with no user feedback.

---

#### BUG #28: Accessibility Issues

**Issue:** Many missing `contentDescription` values, no `semantics` modifiers, no minimum touch target verification.

---

#### BUG #29: No Landscape Support

**Issue:** All screens designed for portrait only. No `landscape` layouts or rotation handling.

---

#### BUG #30: Bitmaps Never Recycled in ViewModels

**Issue:** ViewModels hold bitmap references that aren't cleared on `onCleared()`. Can cause memory leaks if the ViewModel outlives the screen.

---

#### BUG #31: No Coroutine Exception Handling

**Issue:** `viewModelScope.launch` blocks throughout the app don't have a `CoroutineExceptionHandler`. Unhandled exceptions crash the app.

---

#### BUG #32: RemoveBackgroundScreen — ImageView Name Conflict

**File:** `RemoveBackgroundScreen.kt`
**Issue:** A private composable named `ImageView` conflicts with the Android widget `android.widget.ImageView`. Could cause import confusion.

---

#### BUG #33: No Progress Feedback for Long Operations

**Issue:** Enhance Image and Object Eraser show only a `CircularProgressIndicator` with no progress percentage or estimated time.

---

#### BUG #34: No Image Preview Before Saving

**Issue:** After processing, users can save but can't preview the result full-screen or compare before/after.

---

#### BUG #35: No Undo/Redo in Object Eraser After Processing

**Issue:** After inpainting, paths are cleared. If the user doesn't like the result, they must start over. No undo of the inpainting itself.

---

#### BUG #36: EnhanceImageScreen — ImageView Composable Not Defined

**File:** `EnhanceImageScreen.kt`
**Issue:** The screen uses `ImageView(bitmap = ...)` but no `ImageView` composable is defined in this file. It must be imported from another file, but there's no import for it. This could be a compilation error or it's relying on the one from `RemoveBackgroundScreen.kt` (which is private and wouldn't be accessible).

---

#### BUG #37: settings.gradle.kts — Mixed DSL Syntax

**File:** `settings.gradle.kts`
**Issue:** Uses `maven { url 'https://jitpack.io' }` (Groovy syntax) inside a `.kts` (Kotlin DSL) file. Should be `maven { url = uri("https://jitpack.io") }`.

---

#### BUG #38: No google-services.json

**Issue:** The file is in `.gitignore` but is needed for AdMob/ Firebase. If not present on the build machine, the build may fail or ads won't work.

---

#### BUG #39: Reorderable Dependency Placement

**File:** `app/build.gradle.kts`
**Issue:** `implementation(libs.reorderable)` is placed after all test/debug implementations, outside the logical grouping. This is cosmetic but indicates the dependency was added hastily.

---

#### BUG #40: No Unit Tests or UI Tests

**Issue:** No actual test implementations exist. The test dependencies are declared but no test files are present.

---

## 📋 DETAILED PRODUCTION-READY PLAN

### Phase 1: Critical Bug Fixes (Must Do Before Any Release)

| # | Task | Files | Priority | Est. Effort |
|---|---|---|---|---|
| 1.1 | Fix Object Eraser coordinate mapping — scale path coords from screen to bitmap space | `ObjectEraserScreen.kt`, `ObjectEraserViewModel.kt` | P0 | 4h |
| 1.2 | Fix Enhance Image OOM — add size check, downscale large images, catch OOM error | `EnhanceImageViewModel.kt` | P0 | 3h |
| 1.3 | Fix permission request — use READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE below | `MainActivity.kt` | P0 | 1h |
| 1.4 | Fix image saving — use MediaStore on API 29+, add MediaScanner, show save confirmation | `BitmapUtils.kt`, all ViewModels | P0 | 4h |
| 1.5 | Fix OpenCV init check — disable Erase button if OpenCV failed, catch UnsatisfiedLinkError | `ObjectEraserViewModel.kt`, `ObjectEraserScreen.kt` | P0 | 2h |
| 1.6 | Fix unsafe `context as Activity` cast — use safe cast utility | All screens | P0 | 1h |
| 1.7 | Fix BitmapUtils.loadBitmapFromUri — add inSampleSize for large images, catch OOM | `BitmapUtils.kt` | P0 | 2h |

### Phase 2: High Priority Fixes

| # | Task | Files | Priority | Est. Effort |
|---|---|---|---|---|
| 2.1 | Add back button to all feature screen TopAppBars | All feature screens | P1 | 1h |
| 2.2 | Conditionally show bottom nav bar only on Home/History/Settings | `AppScaffold.kt` | P1 | 1h |
| 2.3 | Replace AdMob test IDs with real IDs, use BuildConfig for debug/release | `AdManager.kt`, `BannerAd.kt`, `AndroidManifest.xml`, `build.gradle.kts` | P1 | 2h |
| 2.4 | Enable ProGuard/R8 minification, add keep rules for all libraries | `build.gradle.kts`, `proguard-rules.pro` | P1 | 3h |
| 2.5 | Fix RemoveBackground dimension mismatch — scale foregroundBitmap to original size | `RemoveBackgroundViewModel.kt` | P1 | 1h |
| 2.6 | Fix ImageToPdf memory leak — recycle bitmaps after drawing | `ImageToPdfViewModel.kt` | P1 | 1h |
| 2.7 | Fix ImageToPdf page sizes — use standard A4 size with scaled images | `ImageToPdfViewModel.kt` | P1 | 2h |
| 2.8 | Fix ImageToPdf pdfCreationSuccess state reset | `ImageToPdfViewModel.kt`, `ImageToPdfScreen.kt` | P1 | 0.5h |
| 2.9 | Fix ObjectEraser Image ContentScale — use ContentScale.Fit | `ObjectEraserScreen.kt` | P1 | 1h |
| 2.10 | Fix EnhanceImageScreen missing ImageView composable | `EnhanceImageScreen.kt` | P1 | 1h |

### Phase 3: Medium Priority Fixes

| # | Task | Files | Priority | Est. Effort |
|---|---|---|---|---|
| 3.1 | Fix HomeScreen pressed state reset | `HomeScreen.kt` | P2 | 0.5h |
| 3.2 | Implement dark mode color scheme | `Theme.kt`, `Color.kt` | P2 | 2h |
| 3.3 | Add database migration strategy | `AppDatabase.kt` | P2 | 0.5h |
| 3.4 | Use BuildConfig for version info in Settings | `SettingsScreen.kt` | P2 | 0.5h |
| 3.5 | Add loading state to RemoveBackground saveImage | `RemoveBackgroundViewModel.kt` | P2 | 0.5h |
| 3.6 | Save erased images as PNG instead of JPEG | `ObjectEraserViewModel.kt` | P2 | 0.5h |
| 3.7 | Add file existence check + click handler in History | `HistoryScreen.kt` | P2 | 2h |
| 3.8 | Add share functionality to all feature screens | All feature screens | P2 | 2h |
| 3.9 | Fix ImageToPdf reorder logic | `ImageToPdfViewModel.kt` | P2 | 0.5h |
| 3.10 | Fix settings.gradle.kts Kotlin DSL syntax | `settings.gradle.kts` | P2 | 0.25h |

### Phase 4: Polish & Production Readiness

| # | Task | Files | Priority | Est. Effort |
|---|---|---|---|---|
| 4.1 | Add app icon (adaptive icon for Android 13+) | `res/mipmap-*` | P2 | 2h |
| 4.2 | Add splash screen API (Android 12+) | `MainActivity.kt`, themes | P2 | 1h |
| 4.3 | Add global exception handler with user-friendly error dialog | `MainActivity.kt` | P2 | 1h |
| 4.4 | Add CoroutineExceptionHandler to all viewModelScope.launch | All ViewModels | P2 | 1h |
| 4.5 | Recycle bitmaps in ViewModel onCleared() | All ViewModels | P2 | 1h |
| 4.6 | Add before/after comparison slider for processed images | Feature screens | P3 | 3h |
| 4.7 | Add accessibility: contentDescription, semantics, touch targets | All screens | P3 | 2h |
| 4.8 | Add landscape layout support | All screens | P3 | 3h |
| 4.9 | Add progress percentage for enhance/erase operations | Feature screens | P3 | 2h |
| 4.10 | Add undo for inpainting result in Object Eraser | `ObjectEraserViewModel.kt` | P3 | 2h |
| 4.11 | Write unit tests for BitmapUtils, HistoryRepository, ViewModels | `test/` | P3 | 4h |
| 4.12 | Write UI tests for critical flows | `androidTest/` | P3 | 4h |

### Phase 5: Pre-Launch Checklist

- [ ] Replace all AdMob test IDs with production IDs
- [ ] Enable R8/ProGuard minification
- [ ] Test on Android 8.0 (API 26) — minimum supported
- [ ] Test on Android 13 (API 33) — permission changes
- [ ] Test on Android 14 (API 34) — target SDK
- [ ] Test on a low-RAM device (2GB) for OOM
- [ ] Test with very large images (4000×3000+)
- [ ] Test with transparent PNGs
- [ ] Test all features in landscape mode
- [ ] Verify saved images appear in gallery
- [ ] Verify history entries link to valid files
- [ ] Verify ads load and show correctly
- [ ] Verify interstitial ad frequency capping works
- [ ] Generate signed release APK/AAB
- [ ] Run lint checks and fix all warnings
- [ ] Check APK size (should be < 50MB with OpenCV)
- [ ] Update versionCode and versionName
- [ ] Prepare Play Store listing (screenshots, description, etc.)
- [ ] Review privacy policy for accuracy
- [ ] Review terms and conditions for completeness

---

## 📊 Summary

| Severity | Count |
|---|---|
| 🔴 Critical | 5 |
| 🟠 High | 10 |
| 🟡 Medium | 9 |
| 🟢 Low/Quality | 16 |
| **Total Issues** | **40** |

| Phase | Tasks | Est. Total Effort |
|---|---|---|
| Phase 1 (Critical) | 7 | ~17 hours |
| Phase 2 (High) | 10 | ~13.5 hours |
| Phase 3 (Medium) | 10 | ~10.5 hours |
| Phase 4 (Polish) | 12 | ~24 hours |
| Phase 5 (Pre-launch) | 20 checklist items | ~4 hours |
| **Total** | **59 items** | **~69 hours** |

---

## 🎯 Your Reported Bugs — Root Cause Summary

| Your Report | Root Cause | Bug # |
|---|---|---|
| "Crop karta hu to size kuchh aur ho jata hai" | Object Eraser: screen coordinates not mapped to bitmap coordinates. Mask is drawn at wrong positions, causing wrong inpainting and apparent size change. | #1, #10 |
| "Adjust wale feature me app crash" | Enhance Image: RealESRGAN 4x upscale causes OutOfMemoryError on normal-sized images. | #2 |
| "Images save hi nahi ho rahi" | Wrong permission requested on Android 13+. Files saved to app-private storage not visible in gallery. No MediaScanner call. | #4 |

---

*This plan was generated by an automated A-to-Z codebase audit of the PhotoDoctor-Pro repository on the `fix-admob-diagnostics-versioning` branch at commit `86dbec2`.*
