# AGENTS.md — PicFix Pro

**NEVER EVER build APK/AAB locally.** Always push code to GitHub → let GitHub Actions build → monitor CI until green → download APK/AAB artifacts from Actions page.

Single-module Android app (Kotlin + Jetpack Compose). One Gradle module: `:app`.
Package: `com.dhanuk.photodoctorpro`. `compileSdk` 35, `minSdk` 24, `targetSdk` 35. JVM 1.8. `applicationId` and `namespace` match the package.

## Build / run commands

All builds go through the Gradle wrapper. There is no `gradle` wrapper property for `local.properties`; the file is gitignored.

- Debug build: `./gradlew assembleDebug`
- Release build (signed): `./gradlew assembleRelease bundleRelease`
- Install debug: `./gradlew installDebug`
- Lint: `./gradlew lint`
- Clean: `./gradlew clean`

## Configuration: `local.properties` (gitignored) and env

`app/build.gradle.kts` reads properties via `getProperty(name, default)`. Resolution order is: clean env var → `APP_<name>` env var → clean key in `local.properties` → `APP_<name>` in `local.properties` → default. CI uses `vars.X || secrets.X || secrets.APP_X`.

**Release build guard:** `assembleRelease` / `bundleRelease` will fail with a `GradleException` if `ADMOB_APP_ID`, `ADMOB_INTERSTITIAL_ID`, or `ADMOB_BANNER_ID` is the Google test ID. Blank AdMob IDs also fail the build. See the "AdMob" section below for the source of truth.

Key properties and defaults (from `app/build.gradle.kts`):
- `VERSION_CODE` (int, default `1`) — `versionCode`
- `VERSION_NAME` (string, default `"1.0"`) — `versionName`
- `ADMOB_APP_ID` (default `ca-app-pub-3940256099942544~3347511713` — Google test ID) — used as `manifestPlaceholders["ADMOB_APP_ID"]` and `BuildConfig.ADMOB_APP_ID`
- `ADMOB_INTERSTITIAL_ID` (default test ID)
- `ADMOB_BANNER_ID` (default test ID)
- `ONESIGNAL_APP_ID` (default `""`) — `BuildConfig.ONESIGNAL_APP_ID`. If empty, OneSignal is not initialized at runtime; push notifications are silently disabled.
- `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — release signing; `storeType` is hardcoded to `PKCS12`, `keyAlias` to `"mykey"`. `KEYSTORE_FILE` is the only required one; if empty, the release signing config is left without a store file and a release build will fail.

## CI / release flow

`.github/workflows/android_release.yml` runs on every push to `main` / `release`, plus `workflow_dispatch`. It decodes `KEYSTORE_BASE64` (or `APP_KEYSTORE_BASE64`) to `keystore.jks`, then runs `assembleRelease bundleRelease`, uploading `app-release.apk` and `app-release.aab` as artifacts. JDK 17 (Temurin), `gradle` cache enabled. For feature branches, trigger manually: `gh workflow run android_release.yml --ref <branch>`.

## Architecture notes

Entry points:
- `MainActivity` (`com.dhanuk.photodoctorpro.MainActivity`) — `ComponentActivity`, sets the Compose tree to `AppScaffold()` and initializes `AdManager`. Requests `POST_NOTIFICATIONS` on Tiramisu+. Calls `AdManager.cleanup()` in `onDestroy` to release the InterstitialAd reference.
- `PicFixApplication` — initializes `ThemeController`, launches OpenCV load asynchronously via `applicationScope`, and initializes OneSignal with `BuildConfig.ONESIGNAL_APP_ID` only when non-empty. `OpenCVInitialized` is a `@Volatile` flag set after async init completes.
- Navigation: `ui/navigation/AppNavigation.kt` — single `NavHost` starting at `"home"`. Route names are string literals (e.g., `"remove_background"`, `"enhance_image"`, `"privacy_policy"`, `"terms_and_conditions"`). `ui/navigation/AppScaffold.kt` is the top-level scaffold and bottom bar wrapper.

### ViewModel persistence

All 9 ViewModels accept `SavedStateHandle` as a constructor parameter (added in Phase 4). `ViewModelFactory` extends `ViewModelProvider.Factory` and uses `CreationExtras.createSavedStateHandle()` to extract the handle from the Compose `viewModel(factory = …)` call. Each ViewModel restores its persisted state in the initial `MutableStateFlow` value and writes back on each user mutation.

Persisted fields per ViewModel:
- `HistoryViewModel` — none (read-only from Room)
- `RemoveBackgroundViewModel` — `selectedImageUri`
- `ObjectEraserViewModel` — `selectedImageUri`, `brushSize`, `brushSoftness`
- `EnhanceImageViewModel` — `selectedImageUri`, `scaleFactor`
- `ImageToPdfViewModel` — `selectedImageUris` (list)
- `ColorAdjustmentsViewModel` — `selectedImageUri`, `brightness`, `contrast`, `saturation`, `warmth`
- `ResizeCompressViewModel` — `selectedUri`, `preset`, `quality`, `customWidth/Height/Text`, `maintainAspectRatio`
- `PerspectiveCropViewModel` (inline) — `selectedImageUri`
- `ExifStripperViewModel` (inline) — `selectedImageUri`

Bitmaps and ML-Kit results are NOT persisted — they are reloaded on restore if a Uri is present.

Source layout (under `app/src/main/java/com/dhanuk/photodoctorpro/`):
- `data/local/` — Room: `AppDatabase`, `HistoryDao`, `History` entity. Uses `kapt(libs.androidx.room.compiler)`.
- `data/repository/HistoryRepository.kt` — wraps the DAO.
- `ui/screens/` — one file per Compose screen + a paired `*ViewModel.kt` for the heavier ones (`HistoryViewModel`, `ObjectEraserViewModel`, `RemoveBackgroundViewModel`, `EnhanceImageViewModel`, `ImageToPdfViewModel`, `ColorAdjustmentsViewModel`, `ResizeCompressViewModel`). `ViewModelFactory.kt` provides a single shared factory. `PerspectiveCropViewModel`, `ExifStripperViewModel` are declared inline in their respective screen files.
- `ui/navigation/`, `ui/theme/`, `ui/components/ZoomableBox.kt`, `ui/components/rememberBitmap.kt`.
- `utils/` — `AdManager`, `ThemeController`, `UserPreferences` (DataStore-style prefs), `BitmapUtils`, `ImageEnhancer`, `FaceEnhancer`, `ESRGANHelper`.

ML assets (bundled in APK, `aaptOptions.noCompress += "tflite"` so `.tflite` files are not compressed):
- `app/src/main/assets/models/esrgan_x2.tflite`
- `app/src/main/assets/models/esrgan_x4.tflite`
- `app/src/main/assets/models/gfpgan.tflite`
- `ESRGANHelper` and `FaceEnhancer` both load from `assets/models/<file>`, prefer `GpuDelegate` when `CompatibilityList` reports support, otherwise fall back to 4 CPU threads. Both use a `ReentrantLock` (`interpLock`) to make `Interpreter.run()` thread-safe and protect `close()` from racing with inference. `close()` is wrapped in `interpLock.withLock`.

## Permissions, manifest, FileProvider

Permissions (`AndroidManifest.xml`): `INTERNET`, `READ_MEDIA_IMAGES`, legacy `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` capped via `android:maxSdkVersion`, `POST_NOTIFICATIONS`. `android:largeHeap="true"` is set. The app declares a `FileProvider` at `${applicationId}.provider` and `PicFixApplication` as the `Application` class.

FileProvider paths (`app/src/main/res/xml/provider_paths.xml`) are intentionally restricted to app-specific directories only — `external-files-path` (Pictures), `files-path` (Pictures), and `cache-path` (export/). Do not re-add `<external-path path=".">` or unrestricted `<cache-path path="/">`; these were removed for security in Phase 2F because they grant FileProvider access to the entire external storage and cache.

## AdMob

AdMob is frequency-capped to once per 3 minutes AND only after every 2 major user actions (`AD_FREQUENCY_CAP_MS = 3 * 60 * 1000`, `MAJOR_ACTION_COUNT_CAP = 2` in `AdManager`). `AdManager.cleanup()` is called from `MainActivity.onDestroy()` to drop the `InterstitialAd` reference.

The release build (CI) will fail with `GradleException` if any of the three AdMob IDs is the Google test ID or blank. Set all three via `local.properties`, env vars, or GitHub Actions secrets (`ADMOB_APP_ID`, `ADMOB_INTERSTITIAL_ID`, `ADMOB_BANNER_ID` or `APP_*` prefixed).

## Strings & i18n

`app/src/main/res/values/strings.xml` is the single source of truth for user-facing strings. New strings must be added there and consumed via `stringResource(R.string.…)`. Prefixes used in this repo: `action_*` (buttons/menu items), `cd_*` (content descriptions / a11y), `success_*` and `error_*` (snackbar/dialog text), `select_*` (empty-state copy), `original`/`optimized`/`enhanced` for screen labels. Formatted strings use positional placeholders (`%1$s`, `%1$d`, `%1$.1f`) and require `formatted="true"` for non-positional single-arg variants.

ProGuard: release uses `isMinifyEnabled = true` with `proguard-android-optimize.txt` plus `app/proguard-rules.pro`. The proguard rules already keep `org.tensorflow.lite.**`, `org.opencv.**`, and `coil.**`. Add a `-keep` rule here if you add a new library that gets stripped.

## Asset generation

`scripts/generate_icons.py` regenerates the launcher icon set from a single source PNG. Drop one `.png` into `app/src/main/assets/app_logo_input/` (any name) and run from the repo root:
```
python3 scripts/generate_icons.py
```
It writes `ic_launcher.png` and `ic_launcher_round.png` to each `mipmap-*dpi/` folder, `ic_launcher_foreground.png` to `drawable/`, `ic_launcher_background.xml` (white) to `values/`, and `ic_launcher.xml` / `ic_launcher_round.xml` to `mipmap-anydpi-v26/`. It overwrites the background XML unconditionally with `#FFFFFF`.

## Tests

There is no `app/src/test/` or `app/src/androidTest/` source set, and no JUnit/Espresso test files in-tree despite the JUnit + Espresso dependencies in `app/build.gradle.kts`. `./gradlew test` will succeed vacuously; do not assume a test suite exists. Add tests under those standard paths if you need them — the Gradle config and runner (`androidx.test.runner.AndroidJUnitRunner`) are already wired.

## Conventions specific to this repo

- Versioning is fed by env, not by hand-editing the manifest or `build.gradle.kts`. Bump `VERSION_CODE` / `VERSION_NAME` via `local.properties` or CI vars/secrets, not by changing the gradle defaults.
- The release keystore is expected to be PKCS12, alias `mykey` (hardcoded in `app/build.gradle.kts`). Mismatched `keyAlias` or `storeType` will silently produce an unsigned or wrongly-signed APK.
- The build script accepts both `KEYSTORE_FILE` and `APP_KEYSTORE_FILE` (and same for other props) — match whatever the surrounding CI / local convention is.
- `app/src/main/res/mipmap-*` and `drawable/ic_launcher_foreground.png` are generated. Re-run `scripts/generate_icons.py` rather than editing them by hand.
- Keep `org.tensorflow.lite.**`, `org.opencv.**`, and `coil.**` keep-rules in `proguard-rules.pro` when minification is on; new native/reflection-using libs will need their own `-keep` rules.
- All bitmaps should be loaded through `BitmapUtils.loadBitmapFromUri(uri, context, maxDim)` to avoid OOM on large images. Do not use `BitmapFactory.decodeStream` directly in screens.
- ViewModels that take user input must wrap `loadBitmapFromUri` (or other disk reads) in `withContext(Dispatchers.IO)` — the Compose UI thread must not block on I/O.
- `withLock` is required around any `Interpreter.run()` call and any `close()` call on the same interpreter; do not bypass `interpLock`.
- `catch (e: Exception)` only — never `catch (e: Throwable)` — in ViewModel coroutine bodies. `OutOfMemoryError` must propagate to the process.
