plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    namespace = "com.tx5dr.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tx5dr.bridge"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    androidResources {
        noCompress += setOf("tgz", "gz", "zst", "arm64", "sha256")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}


dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
