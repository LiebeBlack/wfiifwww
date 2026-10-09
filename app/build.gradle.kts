plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)   // Kotlin 2.x Compose compiler Gradle plugin
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.wifiscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wifiscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core + lifecycle.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed versions).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines.
    implementation(libs.coroutines.android)

    // Unit tests.
    testImplementation(libs.junit)
}

// Optional release signing (GitHub Actions secrets path).
//
// A real signing config is only instantiated when app/release.properties
// exists (supplied via build-time secrets). In that case the release build type
// is signed with that config. Otherwise assembleRelease still succeeds and
// produces a valid unsigned release APK — minification and resource shrinking
// remain enabled so the artifact is still a genuine release build.
//
val releasePropsFile = rootProject.file("app/release.properties")
if (releasePropsFile.exists()) {
    val releaseProps = java.util.Properties()
    releasePropsFile.inputStream().use { releaseProps.load(it) }

    val releaseSigning = android.signingConfigs.create("release")
    releaseSigning.apply {
        storeFile = file(releaseProps.getProperty("storeFile", "../keystore.jks"))
        storePassword = releaseProps.getProperty("storePassword", "")
        keyAlias = releaseProps.getProperty("keyAlias", "")
        keyPassword = releaseProps.getProperty("keyPassword", "")
    }
    android.buildTypes.getByName("release").signingConfig = releaseSigning
}
