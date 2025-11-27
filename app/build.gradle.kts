import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
}

// Helper to get properties from local.properties or environment variables
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

    // Order: System Env -> local.properties -> defaultValue
    return System.getenv(key) ?: localProperties.getProperty(key) ?: defaultValue
}

android {
    namespace = "com.dhanuk.photodoctorpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dhanuk.photodoctorpro"
        minSdk = 24
        targetSdk = 34

        val vCode = getProperty("APP_VERSION_CODE", "1").toIntOrNull() ?: 1
        val vName = getProperty("APP_VERSION_NAME", "1.0")

        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val admobAppId = getProperty("APP_ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId

        val oneSignalAppId = getProperty("APP_ONESIGNAL_APP_ID", "")
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"$oneSignalAppId\"")

        val interstitialId = getProperty("APP_ADMOB_INTERSTITIAL_ID", "ca-app-pub-3940256099942544/1033173712") // Default Test ID
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$interstitialId\"")

        val bannerId = getProperty("APP_ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111") // Default Test ID
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$bannerId\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = getProperty("KEYSTORE_FILE")
            if (keystorePath.isNotEmpty()) {
                storeFile = File(keystorePath)
                storePassword = getProperty("APP_KEYSTORE_PASSWORD")
                keyAlias = "mykey"
                keyPassword = getProperty("APP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // AdMob
    implementation(libs.play.services.ads)

    // OpenCV
    implementation(libs.opencv)

    // RealESRGAN
    // implementation(libs.realesrgan.mobile)


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // implementation(libs.reorderable)
    implementation(libs.onesignal)
}
