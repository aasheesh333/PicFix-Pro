# AGENTS.md — PhotoDoctor Pro

Single-module Android app (Kotlin + Jetpack Compose). One Gradle module: `:app`.
Package: `com.dhanuk.photodoctorpro`. `compileSdk` 34, `minSdk` 24, `targetSdk` 34. JVM 1.8. `applicationId` and `namespace` match the package.

## Build / run commands

All builds go through the Gradle wrapper. There is no `gradle` wrapper property for `local.properties`; the file is gitignored.

- Debug build: `./gradlew assembleDebug`
- Release build (signed): `./gradlew assembleRelease bundleRelease`
- Install debug: `./gradlew installDebug`
- Lint: `./gradlew lint`
- Clean: `./gradlew clean`

## Configuration: `local.properties` (gitignored) and env

`app/build.gradle.kts` reads properties via `getProperty(name, default)`. Resolution order is: clean env var → `APP_<name>` env var → clean key in `local.properties` → `APP_<name>` in `local.properties` → default. CI uses `vars.X || secrets.X || secrets.APP_X`.

Key properties and defaults (from `app/build.gradle.kts`):
- `VERSION_CODE` (int, default `1`) — `versionCode`
- `VERSION_NAME` (string, default `"1.0"`) — `versionName`
- `ADMOB_APP_ID` (default `ca-app-pub-3940256099942544~3347511713` — Google test ID) — used as `manifestPlaceholders["ADMOB_APP_ID"]` and `BuildConfig.ADMOB_APP_ID`
- `ADMOB_INTERSTITIAL_ID` (default test ID)
- `ADMOB_BANNER_ID` (default test ID)
- `ONESIGNAL_APP_ID` (default `""`) — `BuildConfig.ONESIGNAL_APP_ID`
- `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — release signing; `storeType` is hardcoded to `PKCS12`, `keyAlias` to `"mykey"`. `KEYSTORE_FILE` is the only required one; if empty, the release signing config is left without a store file and a release build will fail.

## CI / release flow

`.github/workflows/android_release.yml` runs on every push. It decodes `KEYSTORE_BASE64` (or `APP_KEYSTORE_BASE64`) to `keystore.jks`, then runs `assembleRelease bundleRelease`, uploading `app-release.apk` and `app-release.aab` as artifacts. JDK 17 (Temurin), `gradle` cache enabled.

## Architecture notes

Entry points:
- `MainActivity` (`com.dhanuk.photodoctorpro.MainActivity`) — `ComponentActivity`, sets the Compose tree to `AppScaffold()` and initializes `AdManager`. Requests `POST_NOTIFICATIONS` on Tiramisu+.
- `PhotoDoctorApplication` — initializes `ThemeController`, `OpenCVLoader.initDebug()`, and OneSignal with `BuildConfig.ONESIGNAL_APP_ID`. OneSignal is started with `LogLevel.VERBOSE`; do not enable verbose in a real release build without re-checking.
- Navigation: `ui/navigation/AppNavigation.kt` — single `NavHost` starting at `"home"`. Route names are string literals (e.g., `"remove_background"`, `"enhance_image"`, `"meme_maker"`, `"privacy_policy"`, `"terms_and_conditions"`). `ui/navigation/AppScaffold.kt` is the top-level scaffold and bottom bar wrapper.

Source layout (under `app/src/main/java/com/dhanuk/photodoctorpro/`):
- `data/local/` — Room: `AppDatabase`, `HistoryDao`, `History` entity. Uses `kapt(libs.androidx.room.compiler)`.
- `data/repository/HistoryRepository.kt` — wraps the DAO.
- `ui/screens/` — one file per Compose screen + a paired `*ViewModel.kt` for the heavier ones (`HistoryViewModel`, `ObjectEraserViewModel`, `RemoveBackgroundViewModel`, `EnhanceImageViewModel`, `ImageToPdfViewModel`). `ViewModelFactory.kt` provides a single shared factory.
- `ui/navigation/`, `ui/theme/`, `ui/components/ZoomableBox.kt`.
- `utils/` — `AdManager`, `ThemeController`, `UserPreferences` (DataStore-style prefs), `BitmapUtils`, `ImageEnhancer`, `FaceEnhancer`, `ESRGANHelper`.

ML assets (bundled in APK, `aaptOptions.noCompress += "tflite"` so `.tflite` files are not compressed):
- `app/src/main/assets/models/esrgan_x2.tflite`
- `app/src/main/assets/models/esrgan_x4.tflite`
- `app/src/main/assets/models/gfpgan.tflite`
- `ESRGANHelper` loads from `assets/models/<file>`, prefers `GpuDelegate` when `CompatibilityList` reports support, otherwise falls back to 4 CPU threads.

Permissions (`AndroidManifest.xml`): `INTERNET`, `READ_MEDIA_IMAGES`, legacy `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` capped via `android:maxSdkVersion`, `POST_NOTIFICATIONS`. `android:largeHeap="true"` is set. The app declares a `FileProvider` at `${applicationId}.provider` and `PhotoDoctorApplication` as the `Application` class.

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
- AdMob/OneSignal IDs default to Google's documented test IDs when unset — do not ship a release without overriding them.
- The release keystore is expected to be PKCS12, alias `mykey` (hardcoded in `app/build.gradle.kts`). Mismatched `keyAlias` or `storeType` will silently produce an unsigned or wrongly-signed APK.
- The build script accepts both `KEYSTORE_FILE` and `APP_KEYSTORE_FILE` (and same for other props) — match whatever the surrounding CI / local convention is.
- `app/src/main/res/mipmap-*` and `drawable/ic_launcher_foreground.png` are generated. Re-run `scripts/generate_icons.py` rather than editing them by hand.
- Keep `org.tensorflow.lite.**`, `org.opencv.**`, and `coil.**` keep-rules in `proguard-rules.pro` when minification is on; new native/reflection-using libs will need their own `-keep` rules.
