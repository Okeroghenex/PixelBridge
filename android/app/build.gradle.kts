import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pixelbridge.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pixelbridge.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "1.5.3"
    }

    // Release key lives outside the repo (~/.linktomac/signing.properties). Without it, release builds
    // fall back to the debug key so anyone can still build from source.
    val signingProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/.linktomac/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // -PdebugSigning: sign with the debug key, to update devices that still have a debug-key install.
            signingConfig = if (project.hasProperty("debugSigning")) signingConfigs.getByName("debug")
                else signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    // full: everything. lite: no SMS or notification access, so downloaded APKs aren't blocked by
    // Android's fraud protection. Same app ID and key, so people can switch between them.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") { dimension = "edition"; versionNameSuffix = "-lite" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Play services pulls in an old Fragment; activity-result APIs need >= 1.3.
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // QR scanning through Google Play services: no camera permission needed.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
