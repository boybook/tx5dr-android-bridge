plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    }
}


dependencies {
    implementation("org.apache.commons:commons-compress:1.26.2")
}
