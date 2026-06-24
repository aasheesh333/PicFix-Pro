import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
}

// Helper to get properties from local.properties or environment variables
// It now checks for the key (GitHub Actions style) and APP_key (Jules environment style)
fun getProperty(key: String, defaultValue: String = ""): String {
    val rootProjectDir = rootProject.projectDir
    val localPropertiesFile = File(rootProjectDir, "local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    // Also check app-level local.properties
    val appLocalPropertiesFile = File(project.projectDir, "local.properties")
    if (appLocalPropertiesFile.exists()) {
        localProperties.load(FileInputStream(appLocalPropertiesFile))
    }

    // Check System Env (Clean Name) -> System Env (APP_ Prefix) -> local.properties (Clean) -> local.properties (APP_) -> defaultValue
    val cleanKey = key.removePrefix("APP_")
    val appPrefixedKey = "APP_$cleanKey"

    return System.getenv(cleanKey)
        ?: System.getenv(appPrefixedKey)
        ?: localProperties.getProperty(cleanKey)
        ?: localProperties.getProperty(appPrefixedKey)
        ?: defaultValue
}

android {
    namespace = "com.dhanuk.photodoctorpro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dhanuk.photodoctorpro"
        minSdk = 24
        targetSdk = 35

        val vCode = getProperty("VERSION_CODE", "2").toIntOrNull() ?: 2
        val vName = getProperty("VERSION_NAME", "1.1")

        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val admobAppId = getProperty("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")

        val oneSignalAppId = getProperty("ONESIGNAL_APP_ID", "")
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"$oneSignalAppId\"")

        val interstitialId = getProperty("ADMOB_INTERSTITIAL_ID", "ca-app-pub-3940256099942544/1033173712") // Default Test ID
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$interstitialId\"")

        val bannerId = getProperty("ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111") // Default Test ID
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$bannerId\"")

        // Validate AdMob IDs at config time. Real AdMob IDs must start with "ca-app-pub-" and must NOT be the Google test IDs
        // when shipping release builds.
        val googleTestAppId = "ca-app-pub-3940256099942544~3347511713"
        val googleTestInterstitialId = "ca-app-pub-3940256099942544/1033173712"
        val googleTestBannerId = "ca-app-pub-3940256099942544/6300978111"
        if (admobAppId == googleTestAppId || interstitialId == googleTestInterstitialId || bannerId == googleTestBannerId) {
            val taskPath = gradle.startParameter.taskNames.joinToString(" ")
            if (taskPath.contains("Release", ignoreCase = true) || taskPath.contains("Bundle", ignoreCase = true)) {
                throw GradleException(
                    "Release/Bundle build detected with Google test AdMob IDs. " +
                    "Override ADMOB_APP_ID / ADMOB_INTERSTITIAL_ID / ADMOB_BANNER_ID via env vars or local.properties " +
                    "before producing a release build."
                )
            } else {
                logger.warn(
                    "WARNING: Using Google test AdMob IDs. Override before shipping a release."
                )
            }
        }
        if (admobAppId.isBlank() || interstitialId.isBlank() || bannerId.isBlank()) {
            throw GradleException(
                "AdMob IDs must not be blank. Set ADMOB_APP_ID / ADMOB_INTERSTITIAL_ID / ADMOB_BANNER_ID."
            )
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = getProperty("KEYSTORE_FILE")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = getProperty("KEYSTORE_PASSWORD")
                keyAlias = "mykey"
                keyPassword = getProperty("KEY_PASSWORD")
                // Explicitly set type to avoid detection errors
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds always use Google test AdMob IDs so local dev installs serve test ads.
            // Override via env/local.properties if you need different IDs in a debug build.
            buildConfigField("String", "ADMOB_APP_ID", "\"${getProperty("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${getProperty("ADMOB_INTERSTITIAL_ID", "ca-app-pub-3940256099942544/1033173712")}\"")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${getProperty("ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111")}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    aaptOptions {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coil
    implementation(libs.coil.compose)

    // ML Kit
    implementation(libs.play.services.mlkit.subject.segmentation)
    implementation(libs.play.services.mlkit.face.detection)

    // EXIF
    implementation(libs.androidx.exifinterface)

    // TFLite
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.support)

    // AdMob + UMP Consent
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    // Play In-App Review
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    // OpenCV
    implementation(libs.opencv)

    // RealESRGAN
    // implementation(libs.realesrgan.mobile)


    // OneSignal
    implementation(libs.onesignal)

    // Splash screen
    implementation(libs.androidx.core.splashscreen)


    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.arch.core.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // implementation(libs.reorderable)
}
